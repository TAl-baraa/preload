package com.preload.app

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: DownloadJob)

    @Update
    suspend fun update(job: DownloadJob)

    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadJob>>

    @Query(
        "SELECT * FROM download_jobs " +
            "WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt ASC",
    )
    fun observeActiveAndQueued(): Flow<List<DownloadJob>>

    @Query("SELECT COUNT(*) FROM download_jobs WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM download_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextQueued(): DownloadJob?

    @Query(
        "UPDATE download_jobs SET status = 'DOWNLOADING', startedAt = :startedAt, " +
            "completedAt = NULL, errorMessage = NULL WHERE id = :id AND status = 'QUEUED'",
    )
    suspend fun markDownloading(id: String, startedAt: Long): Int

    @Query(
        "UPDATE download_jobs SET progressPercent = :percent, downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, bytesPerSecond = :bytesPerSecond, etaSeconds = :etaSeconds " +
            "WHERE id = :id AND status = 'DOWNLOADING'",
    )
    suspend fun updateProgress(
        id: String,
        percent: Float?,
        downloadedBytes: Long?,
        totalBytes: Long?,
        bytesPerSecond: Long?,
        etaSeconds: Long?,
    )

    @Query(
        "UPDATE download_jobs SET status = 'COMPLETED', progressPercent = 100, " +
            "downloadedBytes = :sizeBytes, totalBytes = :sizeBytes, bytesPerSecond = NULL, " +
            "etaSeconds = 0, outputUri = :outputUri, outputFileName = :outputFileName, " +
            "mimeType = :mimeType, errorMessage = NULL, completedAt = :completedAt WHERE id = :id",
    )
    suspend fun markCompleted(
        id: String,
        sizeBytes: Long,
        outputUri: String,
        outputFileName: String,
        mimeType: String,
        completedAt: Long,
    )

    @Query(
        "UPDATE download_jobs SET status = 'FAILED', bytesPerSecond = NULL, etaSeconds = NULL, " +
            "errorMessage = :errorMessage, completedAt = :completedAt WHERE id = :id",
    )
    suspend fun markFailed(id: String, errorMessage: String?, completedAt: Long)

    @Query(
        "UPDATE download_jobs SET status = 'CANCELLED', bytesPerSecond = NULL, etaSeconds = NULL, " +
            "completedAt = :completedAt WHERE id = :id",
    )
    suspend fun markCancelled(id: String, completedAt: Long)

    @Query(
        "UPDATE download_jobs SET status = 'CANCELLED', completedAt = :completedAt " +
            "WHERE id = :id AND status = 'QUEUED'",
    )
    suspend fun cancelQueued(id: String, completedAt: Long): Int

    @Query(
        "UPDATE download_jobs SET status = 'QUEUED', startedAt = NULL, completedAt = NULL, " +
            "progressPercent = NULL, downloadedBytes = NULL, totalBytes = estimatedBytes, " +
            "bytesPerSecond = NULL, etaSeconds = NULL, errorMessage = NULL " +
            "WHERE status = 'DOWNLOADING'",
    )
    suspend fun resetStaleDownloading(): Int

    @Query(
        "UPDATE download_jobs SET status = 'QUEUED', progressPercent = NULL, " +
            "downloadedBytes = NULL, totalBytes = estimatedBytes, bytesPerSecond = NULL, " +
            "etaSeconds = NULL, outputUri = NULL, outputFileName = NULL, errorMessage = NULL, " +
            "startedAt = NULL, completedAt = NULL " +
            "WHERE id = :id AND status IN ('FAILED', 'CANCELLED')",
    )
    suspend fun retryTerminal(id: String): Int

    @Query(
        "DELETE FROM download_jobs WHERE id = :id " +
            "AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun deleteTerminal(id: String): Int

    @Delete
    suspend fun delete(job: DownloadJob)
}
