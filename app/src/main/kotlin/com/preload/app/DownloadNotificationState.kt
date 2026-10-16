package com.preload.app

data class DownloadNotificationState(
    val activeJobId: String?,
    val title: String,
    val percent: Int?,
    val bytesPerSecond: Long?,
    val etaSeconds: Long?,
    val queuedCount: Int,
)

internal fun notificationState(jobs: List<DownloadJob>): DownloadNotificationState? {
    val active = jobs.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
    val queued = jobs.filter { it.status == DownloadStatus.QUEUED }
    val displayed = active ?: queued.minByOrNull { it.createdAt } ?: return null
    return DownloadNotificationState(
        activeJobId = active?.id,
        title = displayed.title,
        percent = active?.progressPercent?.toInt()?.coerceIn(0, 100),
        bytesPerSecond = active?.bytesPerSecond,
        etaSeconds = active?.etaSeconds,
        queuedCount = queued.size,
    )
}
