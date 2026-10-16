package com.preload.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueManagerTest {
    @Test
    fun staleDownloadingIsResetAndRetried() = runBlocking {
        val stale = job("stale", 1L, DownloadStatus.DOWNLOADING)
        val dao = FakeDownloadJobDao(listOf(stale))
        val executor = FakeExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadQueueManager(dao, executor, scope, { 10L }) { _, _ -> }

        manager.start()
        await { dao.job("stale")?.status == DownloadStatus.COMPLETED }

        assertEquals(listOf("stale"), executor.startedIds)
        assertEquals(1, dao.resetCount)
        scope.cancel()
    }

    @Test
    fun queuedJobsRunOldestFirstAndContinueAfterFailure() = runBlocking {
        val dao = FakeDownloadJobDao(
            listOf(
                job("newest", 30L),
                job("oldest", 10L),
                job("middle", 20L),
            ),
        )
        val executor = FakeExecutor(failIds = setOf("oldest"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadQueueManager(dao, executor, scope, { 40L }) { _, _ -> }

        manager.start()
        await { dao.jobs.value.all { it.status in terminalStatuses } }

        assertEquals(listOf("oldest", "middle", "newest"), executor.startedIds)
        assertEquals(DownloadStatus.FAILED, dao.job("oldest")?.status)
        assertEquals(DownloadStatus.COMPLETED, dao.job("middle")?.status)
        scope.cancel()
    }

    @Test
    fun cancellingActiveStartsNextAndOnlyOneRunsAtOnce() = runBlocking {
        val dao = FakeDownloadJobDao(listOf(job("first", 1L), job("second", 2L)))
        val executor = FakeExecutor(blockId = "first")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadQueueManager(dao, executor, scope, { 50L }) { _, _ -> }

        manager.start()
        await { dao.job("first")?.status == DownloadStatus.DOWNLOADING }
        assertTrue(manager.cancel("first"))
        await { dao.job("second")?.status == DownloadStatus.COMPLETED }

        assertEquals(DownloadStatus.CANCELLED, dao.job("first")?.status)
        assertEquals(listOf("first", "second"), executor.startedIds)
        assertEquals(1, executor.maxConcurrent)
        scope.cancel()
    }

    @Test
    fun queuedJobCanBeCancelledWithoutStarting() = runBlocking {
        val dao = FakeDownloadJobDao(listOf(job("active", 1L), job("queued", 2L)))
        val executor = FakeExecutor(blockId = "active")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadQueueManager(dao, executor, scope, { 60L }) { _, _ -> }

        manager.start()
        await { dao.job("active")?.status == DownloadStatus.DOWNLOADING }
        assertTrue(manager.cancel("queued"))
        assertEquals(DownloadStatus.CANCELLED, dao.job("queued")?.status)
        assertEquals(listOf("active"), executor.startedIds)
        manager.cancel("active")
        scope.cancel()
    }

    @Test
    fun retryTerminalJobResetsItAndRunsThroughQueue() = runBlocking {
        val failed = job("retry", 1L, DownloadStatus.FAILED).copy(
            progressPercent = 42f,
            downloadedBytes = 42L,
            errorMessage = "network",
            completedAt = 9L,
        )
        val dao = FakeDownloadJobDao(listOf(failed))
        val executor = FakeExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadQueueManager(dao, executor, scope, { 70L }) { _, _ -> }

        assertTrue(manager.retry("retry"))
        await { dao.job("retry")?.status == DownloadStatus.COMPLETED }

        val retried = dao.job("retry")!!
        assertEquals(null, retried.errorMessage)
        assertEquals(listOf("retry"), executor.startedIds)
        scope.cancel()
    }

    @Test
    fun removeHistoryOnlyDeletesDatabaseRecord() = runBlocking {
        val completed = job("completed", 1L, DownloadStatus.COMPLETED).copy(
            outputUri = "content://media/completed",
            outputFileName = "completed.mp4",
        )
        val dao = FakeDownloadJobDao(listOf(completed))
        val executor = FakeExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DownloadQueueManager(dao, executor, scope, { 80L }) { _, _ -> }

        assertTrue(manager.removeFromHistory("completed"))

        assertEquals(null, dao.job("completed"))
        assertTrue(executor.startedIds.isEmpty())
        scope.cancel()
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3_000L) {
            while (!condition()) delay(10L)
        }
    }

    private fun job(id: String, createdAt: Long, status: DownloadStatus = DownloadStatus.QUEUED) =
        DownloadJob(
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
            estimatedBytes = 100L,
            mediaKind = DownloadMediaKind.VIDEO_AUDIO,
            status = status,
            progressPercent = null,
            downloadedBytes = null,
            totalBytes = 100L,
            bytesPerSecond = null,
            etaSeconds = null,
            outputUri = null,
            outputFileName = null,
            errorMessage = null,
            createdAt = createdAt,
            startedAt = null,
            completedAt = null,
        )

    private companion object {
        val terminalStatuses = setOf(
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )
    }
}

private class FakeExecutor(
    private val failIds: Set<String> = emptySet(),
    private val blockId: String? = null,
) : DownloadExecutor {
    val startedIds = mutableListOf<String>()
    var maxConcurrent = 0
        private set
    private var concurrent = 0
    @Volatile private var cancelRequested = false

    override suspend fun execute(
        url: String,
        title: String,
        format: SelectableFormat,
        onProgress: (DownloadProgress) -> Unit,
    ): QueueDownloadResult {
        synchronized(this) {
            startedIds += url
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
        }
        try {
            if (url == blockId) {
                while (!cancelRequested) delay(10L)
                throw DownloadCanceledException()
            }
            if (url in failIds) error("expected failure")
            return QueueDownloadResult("$url.mp4", 100L, "content://test/$url", "video/mp4")
        } finally {
            synchronized(this) { concurrent-- }
        }
    }

    override fun cancel(): Boolean {
        cancelRequested = true
        return true
    }
}

private class FakeDownloadJobDao(initial: List<DownloadJob>) : DownloadJobDao {
    val jobs = MutableStateFlow(initial)
    var resetCount = 0

    @Synchronized
    private fun mutate(block: (MutableList<DownloadJob>) -> Unit) {
        jobs.value = jobs.value.toMutableList().also(block)
    }

    fun job(id: String): DownloadJob? = jobs.value.firstOrNull { it.id == id }

    override suspend fun insert(job: DownloadJob) = mutate { it += job }
    override suspend fun update(job: DownloadJob) = mutate { list ->
        list.indexOfFirst { it.id == job.id }.takeIf { it >= 0 }?.let { list[it] = job }
    }
    override fun observeAll(): Flow<List<DownloadJob>> = jobs
    override fun observeActiveAndQueued(): Flow<List<DownloadJob>> = jobs
    override suspend fun getNextQueued(): DownloadJob? = jobs.value
        .filter { it.status == DownloadStatus.QUEUED }
        .minByOrNull { it.createdAt }

    override suspend fun markDownloading(id: String, startedAt: Long): Int {
        if (job(id)?.status != DownloadStatus.QUEUED) return 0
        mutate { list -> list.replace(id) { it.copy(status = DownloadStatus.DOWNLOADING, startedAt = startedAt) } }
        return 1
    }

    override suspend fun updateProgress(
        id: String,
        percent: Float?,
        downloadedBytes: Long?,
        totalBytes: Long?,
        bytesPerSecond: Long?,
        etaSeconds: Long?,
    ) = mutate { list ->
        list.replace(id) {
            if (it.status == DownloadStatus.DOWNLOADING) it.copy(
                progressPercent = percent,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
                etaSeconds = etaSeconds,
            ) else it
        }
    }

    override suspend fun markCompleted(
        id: String,
        sizeBytes: Long,
        outputUri: String,
        outputFileName: String,
        mimeType: String,
        completedAt: Long,
    ) = mutate { list ->
        list.replace(id) {
            it.copy(status = DownloadStatus.COMPLETED, outputUri = outputUri, completedAt = completedAt)
        }
    }

    override suspend fun markFailed(id: String, errorMessage: String?, completedAt: Long) = mutate { list ->
        list.replace(id) { it.copy(status = DownloadStatus.FAILED, errorMessage = errorMessage) }
    }

    override suspend fun markCancelled(id: String, completedAt: Long) = mutate { list ->
        list.replace(id) { it.copy(status = DownloadStatus.CANCELLED, completedAt = completedAt) }
    }

    override suspend fun cancelQueued(id: String, completedAt: Long): Int {
        if (job(id)?.status != DownloadStatus.QUEUED) return 0
        markCancelled(id, completedAt)
        return 1
    }

    override suspend fun resetStaleDownloading(): Int {
        val count = jobs.value.count { it.status == DownloadStatus.DOWNLOADING }
        mutate { list -> list.indices.forEach { index ->
            if (list[index].status == DownloadStatus.DOWNLOADING) {
                list[index] = list[index].copy(status = DownloadStatus.QUEUED, startedAt = null)
            }
        } }
        resetCount += count
        return count
    }

    override suspend fun retryTerminal(id: String): Int {
        if (job(id)?.status !in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)) return 0
        mutate { list ->
            list.replace(id) {
                it.copy(
                    status = DownloadStatus.QUEUED,
                    progressPercent = null,
                    downloadedBytes = null,
                    totalBytes = it.estimatedBytes,
                    bytesPerSecond = null,
                    etaSeconds = null,
                    outputUri = null,
                    outputFileName = null,
                    errorMessage = null,
                    startedAt = null,
                    completedAt = null,
                )
            }
        }
        return 1
    }

    override suspend fun deleteTerminal(id: String): Int {
        if (job(id)?.status !in setOf(
                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED,
            )
        ) return 0
        mutate { it.removeAll { item -> item.id == id } }
        return 1
    }

    override suspend fun delete(job: DownloadJob) = mutate { it.removeAll { item -> item.id == job.id } }

    private fun MutableList<DownloadJob>.replace(id: String, transform: (DownloadJob) -> DownloadJob) {
        val index = indexOfFirst { it.id == id }
        if (index >= 0) this[index] = transform(this[index])
    }
}
