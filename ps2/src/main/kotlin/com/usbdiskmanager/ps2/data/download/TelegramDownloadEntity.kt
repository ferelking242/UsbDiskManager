package com.usbdiskmanager.ps2.data.download

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class TgDownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }

@Entity(tableName = "telegram_downloads")
data class TelegramDownloadEntity(
    @PrimaryKey val id: String,
    val channelUsername: String,
    val messageId: Int,                             // Telegram URL post number (for UI / deep links)
    val fileName: String,
    val fileSizeBytes: Long,
    val tdlibFileId: Int = 0,
    val tdlibChatId: Long = 0,
    /** Actual TDLib message ID of the file message.
     *  Needed for getMessage() — the URL post number is NOT valid there. */
    @ColumnInfo(name = "tdlib_full_message_id", defaultValue = "0")
    val tdlibFullMessageId: Long = 0L,
    val destPath: String,
    val status: TgDownloadStatus = TgDownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TelegramDownloadDao {

    @Query("SELECT * FROM telegram_downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<TelegramDownloadEntity>>

    @Query(
        "SELECT * FROM telegram_downloads " +
            "WHERE status NOT IN ('DONE','ERROR') ORDER BY createdAt"
    )
    suspend fun getPendingDownloads(): List<TelegramDownloadEntity>

    @Query("SELECT * FROM telegram_downloads WHERE id = :id LIMIT 1")
    suspend fun getDownload(id: String): TelegramDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TelegramDownloadEntity)

    @Query(
        "UPDATE telegram_downloads " +
            "SET status = :status, bytesDownloaded = :bytes WHERE id = :id"
    )
    suspend fun updateProgress(id: String, status: TgDownloadStatus, bytes: Long)

    @Query(
        "UPDATE telegram_downloads " +
            "SET status = :status, errorMessage = :error WHERE id = :id"
    )
    suspend fun updateStatus(id: String, status: TgDownloadStatus, error: String? = null)

    @Query(
        "UPDATE telegram_downloads " +
            "SET tdlibFileId = :fileId, tdlibChatId = :chatId WHERE id = :id"
    )
    suspend fun updateFileInfo(id: String, fileId: Int, chatId: Long)
}
