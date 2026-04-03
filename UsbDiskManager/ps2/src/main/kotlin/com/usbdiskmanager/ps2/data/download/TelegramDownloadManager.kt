package com.usbdiskmanager.ps2.data.download

import android.content.Context
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.telegram.TDLibClient
import com.usbdiskmanager.ps2.telegram.TelegramGamePost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ─── Progress model ───────────────────────────────────────────────────────────

data class TgDownloadProgress(
    val id: String,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: TgDownloadStatus,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isDone: Boolean get() = status == TgDownloadStatus.DONE
    val speedFormatted: String
        get() = when {
            speedBytesPerSec > 1_000_000 -> "%.1f MB/s".format(speedBytesPerSec / 1e6)
            speedBytesPerSec > 1_000     -> "%.0f KB/s".format(speedBytesPerSec / 1e3)
            else                         -> "${speedBytesPerSec} B/s"
        }
    val etaSeconds: Long
        get() = if (speedBytesPerSec > 0 && totalBytes > bytesDownloaded)
            (totalBytes - bytesDownloaded) / speedBytesPerSec else -1L
}

// ─── Manager ──────────────────────────────────────────────────────────────────

@Singleton
class TelegramDownloadManager @Inject constructor(
    private val tdlib: TDLibClient,
    private val dao: TelegramDownloadDao,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _downloads = MutableStateFlow<Map<String, TgDownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, TgDownloadProgress>> = _downloads.asStateFlow()

    private val activeJobs    = mutableMapOf<String, Job>()
    private val speedTracker  = mutableMapOf<String, Pair<Long, Long>>()

    init {
        scope.launch { observeFileUpdates() }
        scope.launch { resumePending() }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun enqueue(post: TelegramGamePost) {
        val id = downloadId(post.channelUsername, post.messageId)
        scope.launch {
            val existing = dao.getDownload(id)
            if (existing?.status == TgDownloadStatus.DONE) return@launch
            if (activeJobs[id]?.isActive == true) return@launch

            val destDir  = File(IsoScanner.BASE_DIR).also { it.mkdirs() }
            val entity   = TelegramDownloadEntity(
                id                  = id,
                channelUsername     = post.channelUsername,
                messageId           = post.messageId,
                fileName            = post.fileName,
                fileSizeBytes       = post.fileSizeBytes,
                // Store the real TDLib message ID so getMessage() works correctly
                tdlibFullMessageId  = post.tdlibFirstFileMessageId,
                destPath            = File(destDir, post.fileName).absolutePath
            )
            dao.upsert(entity)
            setProgress(entity, 0L, TgDownloadStatus.QUEUED)
            startFetch(entity)
        }
    }

    fun cancel(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        scope.launch {
            val entity = dao.getDownload(id) ?: return@launch
            if (entity.tdlibFileId > 0) tdlib.cancelDownload(entity.tdlibFileId)
            dao.updateStatus(id, TgDownloadStatus.ERROR, "Annulé")
            _downloads.update {
                it + (id to TgDownloadProgress(
                    id, entity.fileName, 0L, entity.fileSizeBytes,
                    TgDownloadStatus.ERROR, error = "Annulé"
                ))
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startFetch(entity: TelegramDownloadEntity) {
        val job = scope.launch {
            try {
                dao.updateProgress(entity.id, TgDownloadStatus.DOWNLOADING, 0L)
                setProgress(entity, 0L, TgDownloadStatus.DOWNLOADING)

                val chat   = tdlib.searchPublicChat(entity.channelUsername)

                // Use the stored TDLib full message ID if available;
                // fall back to converting the URL post number (best-effort).
                val msgId = when {
                    entity.tdlibFullMessageId > 0L -> entity.tdlibFullMessageId
                    else -> entity.messageId.toLong() shl 20
                }
                val message = tdlib.getMessage(chat.id, msgId)
                val fileId  = extractFileId(message)
                    ?: error("No downloadable file in message for ${entity.fileName}")

                dao.updateFileInfo(entity.id, fileId, chat.id)
                tdlib.startDownload(fileId, priority = 32)

                Timber.d("TDLib download started: ${entity.fileName}, fileId=$fileId")
            } catch (e: kotlinx.coroutines.CancellationException) {
                Timber.d("Download cancelled: ${entity.fileName}")
            } catch (e: Exception) {
                Timber.e(e, "Download failed: ${entity.fileName}")
                dao.updateStatus(entity.id, TgDownloadStatus.ERROR, e.message)
                _downloads.update {
                    it + (entity.id to TgDownloadProgress(
                        entity.id, entity.fileName, 0L, entity.fileSizeBytes,
                        TgDownloadStatus.ERROR, error = e.message
                    ))
                }
            }
        }
        activeJobs[entity.id] = job
    }

    private suspend fun resumePending() {
        if (!tdlib.isReady) {
            tdlib.authState.first { it is TdApi.AuthorizationStateReady }
        }
        val pending = dao.getPendingDownloads()
        Timber.d("Resuming ${pending.size} pending TDLib downloads")
        for (dl in pending) {
            if (dl.tdlibFileId > 0) {
                try {
                    val file = tdlib.getFile(dl.tdlibFileId)
                    if (file.local?.isDownloadingCompleted == true && file.local?.path != null) {
                        finalize(dl.id, file.local!!.path!!)
                    } else {
                        setProgress(dl, dl.bytesDownloaded, TgDownloadStatus.DOWNLOADING)
                        tdlib.startDownload(dl.tdlibFileId, priority = 32)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Could not resume ${dl.fileName}")
                    startFetch(dl)
                }
            } else {
                startFetch(dl)
            }
        }
    }

    private suspend fun observeFileUpdates() {
        tdlib.fileUpdates.collect { update ->
            handleFileUpdate(update)
        }
    }

    private suspend fun handleFileUpdate(update: TdApi.UpdateFile) {
        val file   = update.file
        val all    = dao.getPendingDownloads()
        val entity = all.firstOrNull { it.tdlibFileId == file.id } ?: return

        val downloaded = file.local?.downloadedSize ?: 0L
        val total      = file.expectedSize.takeIf { it > 0L } ?: entity.fileSizeBytes
        val isComplete = file.local?.isDownloadingCompleted ?: false

        if (isComplete && file.local?.path != null) {
            finalize(entity.id, file.local!!.path!!)
        } else if (file.local?.isDownloadingActive == true) {
            val now              = System.currentTimeMillis()
            val (lastBytes, lastTime) = speedTracker[entity.id] ?: Pair(0L, now)
            val speed = if (now > lastTime)
                ((downloaded - lastBytes) * 1000L) / (now - lastTime) else 0L
            speedTracker[entity.id] = Pair(downloaded, now)

            dao.updateProgress(entity.id, TgDownloadStatus.DOWNLOADING, downloaded)
            _downloads.update {
                it + (entity.id to TgDownloadProgress(
                    id               = entity.id,
                    fileName         = entity.fileName,
                    bytesDownloaded  = downloaded,
                    totalBytes       = total,
                    status           = TgDownloadStatus.DOWNLOADING,
                    speedBytesPerSec = speed.coerceAtLeast(0L)
                ))
            }
        }
    }

    private suspend fun finalize(id: String, tdlibPath: String) {
        val entity = dao.getDownload(id) ?: return
        try {
            val src = File(tdlibPath)
            val dst = File(entity.destPath)
            if (src.exists() && src.canonicalPath != dst.canonicalPath) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
            dao.updateStatus(id, TgDownloadStatus.DONE)
            speedTracker.remove(id)
            activeJobs.remove(id)
            _downloads.update {
                it + (id to TgDownloadProgress(
                    id              = id,
                    fileName        = entity.fileName,
                    bytesDownloaded = entity.fileSizeBytes,
                    totalBytes      = entity.fileSizeBytes,
                    status          = TgDownloadStatus.DONE
                ))
            }
            Timber.i("Download done: ${entity.fileName} → ${entity.destPath}")
        } catch (e: Exception) {
            Timber.e(e, "Finalize error: ${entity.fileName}")
            dao.updateStatus(id, TgDownloadStatus.ERROR, e.message)
        }
    }

    private fun setProgress(entity: TelegramDownloadEntity, bytes: Long, status: TgDownloadStatus) {
        _downloads.update {
            it + (entity.id to TgDownloadProgress(
                id              = entity.id,
                fileName        = entity.fileName,
                bytesDownloaded = bytes,
                totalBytes      = entity.fileSizeBytes,
                status          = status
            ))
        }
    }

    private fun extractFileId(message: TdApi.Message): Int? = when (val c = message.content) {
        is TdApi.MessageDocument -> c.document?.document?.id
        is TdApi.MessageVideo    -> c.video?.video?.id
        is TdApi.MessageAudio    -> c.audio?.audio?.id
        else -> null
    }

    companion object {
        fun downloadId(channelUsername: String, messageId: Int) =
            "${channelUsername}_${messageId}"
    }
}
