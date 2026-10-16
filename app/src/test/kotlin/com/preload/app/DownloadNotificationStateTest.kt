package com.preload.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNotificationStateTest {
    @Test
    fun emptyQueueHasNoNotificationState() {
        assertNull(notificationState(emptyList()))
    }

    @Test
    fun activeDownloadProvidesProgressAndQueuedCount() {
        val active = notificationJob("active", DownloadStatus.DOWNLOADING, 1L).copy(
            progressPercent = 35.8f,
            bytesPerSecond = 4_400_000,
            etaSeconds = 61,
        )
        val queued = notificationJob("next", DownloadStatus.QUEUED, 2L)

        val state = notificationState(listOf(queued, active))!!

        assertEquals("active", state.activeJobId)
        assertEquals(35, state.percent)
        assertEquals(4_400_000L, state.bytesPerSecond)
        assertEquals(61L, state.etaSeconds)
        assertEquals(1, state.queuedCount)
    }

    @Test
    fun oldestQueuedJobIsShownAsIndeterminatePreparation() {
        val newer = notificationJob("newer", DownloadStatus.QUEUED, 2L)
        val older = notificationJob("older", DownloadStatus.QUEUED, 1L)

        val state = notificationState(listOf(newer, older))!!

        assertEquals("older", state.title)
        assertNull(state.activeJobId)
        assertNull(state.percent)
        assertEquals(2, state.queuedCount)
    }
}

private fun notificationJob(id: String, status: DownloadStatus, createdAt: Long) = DownloadJob(
    id = id,
    sourceUrl = id,
    title = id,
    uploader = null,
    thumbnailUrl = null,
    durationSeconds = null,
    formatId = id,
    formatLabel = "360p • MP4",
    extension = "mp4",
    mimeType = null,
    estimatedBytes = 100,
    mediaKind = DownloadMediaKind.VIDEO_AUDIO,
    status = status,
    progressPercent = null,
    downloadedBytes = null,
    totalBytes = 100,
    bytesPerSecond = null,
    etaSeconds = null,
    outputUri = null,
    outputFileName = null,
    errorMessage = null,
    createdAt = createdAt,
    startedAt = null,
    completedAt = null,
)
