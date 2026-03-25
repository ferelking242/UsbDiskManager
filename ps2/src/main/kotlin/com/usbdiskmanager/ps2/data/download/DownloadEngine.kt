package com.usbdiskmanager.ps2.data.download

import android.os.Environment
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.domain.model.DownloadStatus
import com.usbdiskmanager.ps2.domain.model.Ps2Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class DownloadEngine @Inject constructor() {

    companion object {
        private const val BUFFER_SIZE = 128 * 1024 // 128 KB
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    /**
     * Start or resume a download. Emits progress snapshots of [Ps2Download].
     * Supports HTTP byte-range resume if the server supports it.
     */
    fun download(item: Ps2Download): Flow<Ps2Download> = flow {
        val outputFile = File(item.outputPath)
        outputFile.parentFile?.mkdirs()

        val resumeFrom = if (outputFile.exists()) outputFile.length() else 0L
        var download = item.copy(downloadedBytes = resumeFrom, status = DownloadStatus.DOWNLOADING)
        emit(download)

        try {
            val conn = openConnection(item.url, resumeFrom)
            val responseCode = conn.responseCode
            val serverSupportsRange = responseCode == 206 || (responseCode == 200 && resumeFrom == 0L)

            if (!serverSupportsRange && responseCode != 200) {
                emit(download.copy(
                    status = DownloadStatus.ERROR,
                    errorMessage = "HTTP $responseCode"
                ))
                conn.disconnect()
                return@flow
            }

            val contentLength = conn.contentLengthLong.takeIf { it > 0 }
            val totalBytes = if (responseCode == 206) {
                resumeFrom + (contentLength ?: -1L)
            } else {
                contentLength ?: -1L
            }

            download = download.copy(totalBytes = totalBytes)

            val raf = RandomAccessFile(outputFile, "rw")
            if (responseCode == 206) raf.seek(resumeFrom) else raf.seek(0)

            val buf = ByteArray(BUFFER_SIZE)
            var written = resumeFrom
            val startTime = System.currentTimeMillis()
            var lastSpeedCheck = startTime
            var bytesAtLastCheck = written

            conn.inputStream.use { input ->
                raf.use { ra ->
                    while (coroutineContext.isActive) {
                        val read = input.read(buf)
                        if (read == -1) break
                        ra.write(buf, 0, read)
                        written += read

                        val now = System.currentTimeMillis()
                        if (now - lastSpeedCheck >= 500) {
                            val elapsed = (now - lastSpeedCheck) / 1000.0
                            val speedBps = (written - bytesAtLastCheck) / elapsed
                            download = download.copy(
                                downloadedBytes = written,
                                totalBytes = totalBytes,
                                speedBps = speedBps,
                                status = DownloadStatus.DOWNLOADING
                            )
                            emit(download)
                            lastSpeedCheck = now
                            bytesAtLastCheck = written
                        }
                    }
                }
            }

            conn.disconnect()

            val finalStatus = if (!coroutineContext.isActive) {
                DownloadStatus.PAUSED
            } else {
                DownloadStatus.COMPLETED
            }
            emit(download.copy(downloadedBytes = written, status = finalStatus, speedBps = 0.0))

        } catch (e: Exception) {
            Timber.e(e, "Download failed: ${item.url}")
            emit(download.copy(
                status = DownloadStatus.ERROR,
                errorMessage = e.message ?: "Unknown error"
            ))
        }
    }.flowOn(Dispatchers.IO)

    private fun openConnection(urlStr: String, resumeFrom: Long): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "UsbDiskManager/1.0 Android")
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        conn.connect()
        return conn
    }

    fun resolveFileName(url: String): String {
        return try {
            val path = URL(url).path
            val name = path.substringAfterLast('/')
            if (name.isNotBlank() && name.contains('.')) name
            else "download_${System.currentTimeMillis()}.iso"
        } catch (_: Exception) {
            "download_${System.currentTimeMillis()}.iso"
        }
    }

    fun defaultOutputPath(fileName: String): String {
        return "${IsoScanner.DEFAULT_ISO_DIR}/$fileName"
    }
}
