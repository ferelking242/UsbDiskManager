package com.usbdiskmanager.ps2.data.db

import androidx.room.*
import com.usbdiskmanager.ps2.domain.model.ConversionJob
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionJobDao {
    @Query("SELECT * FROM conversion_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversionJob>>

    @Query("SELECT * FROM conversion_jobs WHERE isoPath = :isoPath")
    suspend fun getByPath(isoPath: String): ConversionJob?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ConversionJob)

    @Query("UPDATE conversion_jobs SET bytesWritten = :bytes, currentPart = :part, status = :status, updatedAt = :now WHERE isoPath = :path")
    suspend fun updateProgress(path: String, bytes: Long, part: Int, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversion_jobs SET status = :status, errorMessage = :error, updatedAt = :now WHERE isoPath = :path")
    suspend fun updateStatus(path: String, status: String, error: String = "", now: Long = System.currentTimeMillis())

    @Query("DELETE FROM conversion_jobs WHERE isoPath = :isoPath")
    suspend fun delete(isoPath: String)

    @Query("SELECT * FROM conversion_jobs WHERE status IN ('RUNNING', 'PAUSED') ORDER BY updatedAt DESC")
    suspend fun getResumable(): List<ConversionJob>
}
