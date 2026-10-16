package com.preload.app

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadMediaKind {
    VIDEO_AUDIO,
    AUDIO,
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Entity(tableName = "download_jobs")
data class DownloadJob(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val formatId: String,
    val formatLabel: String,
    val extension: String?,
    val mimeType: String?,
    val estimatedBytes: Long?,
    val mediaKind: DownloadMediaKind,
    val status: DownloadStatus,
    val progressPercent: Float?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
    val etaSeconds: Long?,
    val outputUri: String?,
    val outputFileName: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
)
