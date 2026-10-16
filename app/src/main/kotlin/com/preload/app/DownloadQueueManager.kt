package com.preload.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

interface DownloadExecutor {
    suspend fun execute(
        url: String,
        title: String,
        format: SelectableFormat,
        onProgress: (DownloadProgress) -> Unit,
    ): QueueDownloadResult

    fun cancel(): Boolean
}

data class QueueDownloadResult(
    val fileName: String,
    val sizeBytes: Long,
    val outputUri: String,
    val mimeType: String,
)

class VideoDownloadExecutor(private val downloader: VideoDownloader) : DownloadExecutor {
    override suspend fun execute(
        url: String,
        title: String,
        format: SelectableFormat,
        onProgress: (DownloadProgress) -> Unit,
    ): QueueDownloadResult = downloader.download(url, title, format, onProgress).let { file ->
        QueueDownloadResult(
            fileName = file.fileName,
            sizeBytes = file.sizeBytes,
            outputUri = file.contentUri.toString(),
            mimeType = file.mimeType,
        )
    }

    override fun cancel(): Boolean = downloader.cancel()
}

class DownloadQueueManager(
    private val dao: DownloadJobDao,
    private val downloader: DownloadExecutor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val onWorkAvailable: (() -> Unit)? = null,
    private val reportError: (String, Throwable) -> Unit = { message, error ->
        Log.e(TAG, message, error)
    },
) {
    val jobs: Flow<List<DownloadJob>> = dao.observeAll()
    val activeAndQueuedJobs: Flow<List<DownloadJob>> = dao.observeActiveAndQueued()

    private val processingMutex = Mutex()
    private val recoveryStarted = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)

    @Volatile
    private var activeJobId: String? = null

    fun start() {
        pauseRequested.set(false)
        scope.launch {
            if (recoveryStarted.compareAndSet(false, true)) dao.resetStaleDownloading()
            processQueue()
        }
    }

    suspend fun enqueue(
        sourceUrl: String,
        video: AnalyzedVideo,
        format: SelectableFormat,
        formatLabel: String,
    ): String {
        require(format.hasAudio) { "Only formats containing audio can be queued" }
        val id = UUID.randomUUID().toString()
        dao.insert(
            DownloadJob(
                id = id,
                sourceUrl = sourceUrl,
                title = video.title.ifBlank { "Untitled video" },
                uploader = video.uploader,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds?.toLong(),
                formatId = format.formatId,
                formatLabel = formatLabel,
                extension = format.extension,
                mimeType = null,
                estimatedBytes = format.fileSizeBytes,
                mediaKind = if (format.hasVideo) {
                    DownloadMediaKind.VIDEO_AUDIO
                } else {
                    DownloadMediaKind.AUDIO
                },
                status = DownloadStatus.QUEUED,
                progressPercent = null,
                downloadedBytes = null,
                totalBytes = format.fileSizeBytes,
                bytesPerSecond = null,
                etaSeconds = null,
                outputUri = null,
                outputFileName = null,
                errorMessage = null,
                createdAt = now(),
                startedAt = null,
                completedAt = null,
            ),
        )
        signalWorkAvailable()
        return id
    }

    suspend fun cancel(jobId: String): Boolean {
        if (activeJobId == jobId) return downloader.cancel()
        return dao.cancelQueued(jobId, now()) > 0
    }

    suspend fun retry(jobId: String): Boolean {
        val retried = dao.retryTerminal(jobId) > 0
        if (retried) signalWorkAvailable()
        return retried
    }

    suspend fun removeFromHistory(jobId: String): Boolean = dao.deleteTerminal(jobId) > 0

    fun stopForSystemTimeout(): Boolean {
        pauseRequested.set(true)
        return activeJobId?.let { downloader.cancel() } ?: false
    }

    private fun signalWorkAvailable() {
        onWorkAvailable?.invoke() ?: start()
    }

    private suspend fun processQueue() = processingMutex.withLock {
        while (true) {
            if (pauseRequested.get()) return@withLock
            val job = dao.getNextQueued() ?: return@withLock
            if (dao.markDownloading(job.id, now()) == 0) continue
            activeJobId = job.id
            try {
                val progressWriter = ProgressWriter(job.id)
                val file = downloader.execute(
                    url = job.sourceUrl,
                    title = job.title,
                    format = job.toSelectableFormat(),
                    onProgress = progressWriter::offer,
                )
                dao.markCompleted(
                    id = job.id,
                    sizeBytes = file.sizeBytes,
                    outputUri = file.outputUri,
                    outputFileName = file.fileName,
                    mimeType = file.mimeType,
                    completedAt = now(),
                )
            } catch (_: DownloadCanceledException) {
                dao.markCancelled(job.id, now())
            } catch (error: Throwable) {
                reportError("Queued download failed: ${job.id}", error)
                dao.markFailed(job.id, error.safeQueueMessage(), now())
            } finally {
                activeJobId = null
            }
        }
    }

    private inner class ProgressWriter(private val jobId: String) {
        private var lastWriteAt = 0L
        private var lastPercent: Int? = null

        @Synchronized
        fun offer(progress: DownloadProgress) {
            val timestamp = now()
            val roundedPercent = progress.percent?.toInt()
            if (roundedPercent == lastPercent && timestamp - lastWriteAt < PROGRESS_WRITE_INTERVAL_MS) return
            lastWriteAt = timestamp
            lastPercent = roundedPercent
            scope.launch {
                dao.updateProgress(
                    id = jobId,
                    percent = progress.percent,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    bytesPerSecond = progress.bytesPerSecond,
                    etaSeconds = progress.etaSeconds,
                )
            }
        }
    }

    private companion object {
        const val TAG = "PreloadQueue"
        const val PROGRESS_WRITE_INTERVAL_MS = 350L
    }
}

internal fun DownloadJob.toSelectableFormat() = SelectableFormat(
    formatId = formatId,
    extension = extension,
    width = null,
    height = null,
    fps = null,
    videoCodec = null,
    audioCodec = null,
    bitrateKbps = null,
    fileSizeBytes = estimatedBytes,
    fileSizeIsApproximate = true,
    hasVideo = mediaKind == DownloadMediaKind.VIDEO_AUDIO,
    hasAudio = true,
)

private fun Throwable.safeQueueMessage(): String? = message
    ?.lineSequence()
    ?.firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.take(240)
