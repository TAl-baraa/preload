package com.preload.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class DownloadedFile(
    val fileName: String,
    val sizeBytes: Long,
    val contentUri: Uri,
    val mimeType: String,
    val destinationLabel: String,
)

class DownloadCanceledException : Exception()
class DownloadPublishException(cause: Throwable) :
    IOException("Downloaded, but could not save to Downloads", cause)

class VideoDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val publisher = MediaStorePublisher(appContext)
    private val processLock = Any()

    @Volatile
    private var activeProcessId: String? = null

    @Volatile
    private var canceledProcessId: String? = null

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { YtDlpRuntime.ensureReady(appContext) }
    }

    suspend fun download(
        url: String,
        title: String,
        format: SelectableFormat,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        require(format.hasAudio) {
            "Only formats containing audio can be downloaded"
        }

        YtDlpRuntime.ensureReady(appContext)
        val downloadRoot = appContext
            .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("Preload")
            ?: throw IOException("External app storage is unavailable")
        if (!downloadRoot.exists() && !downloadRoot.mkdirs()) {
            throw IOException("Could not create the download directory")
        }

        val baseName = "${safeFileName(title)}-${System.currentTimeMillis()}"
        val outputTemplate = File(downloadRoot, "$baseName.%(ext)s").absolutePath
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-f", format.formatId)
            addOption("-o", outputTemplate)
        }
        val processId = "preload-${UUID.randomUUID()}"

        synchronized(processLock) {
            check(activeProcessId == null) { "A download is already running" }
            activeProcessId = processId
            canceledProcessId = null
        }

        try {
            val executeError = try {
                val response = YoutubeDL.getInstance().execute(
                    request,
                    processId,
                ) { progress, etaSeconds, line ->
                    Log.d(TAG, "yt-dlp progress: $line")
                    DownloadProgressParser.parse(progress, etaSeconds, line)?.let(onProgress)
                }
                if (response.exitCode != 0) {
                    Log.e(TAG, "yt-dlp failed (${response.exitCode}): ${response.err}")
                    throw IOException(response.err.safeLastLine() ?: "yt-dlp exited with an error")
                }
                null
            } catch (error: YoutubeDL.CanceledException) {
                throw DownloadCanceledException()
            } catch (error: Throwable) {
                error
            }

            if (isCancellationRequested(processId)) {
                throw DownloadCanceledException()
            }

            val resultFile = if (executeError == null) {
                discoverCompletedOutput(downloadRoot, baseName, format.extension)
            } else {
                if (format.hasVideo) throw executeError
                recoverFinalizedAudio(
                    downloadRoot = downloadRoot,
                    baseName = baseName,
                    expectedExtension = format.extension,
                    processId = processId,
                    executeError = executeError,
                )
            }

            if (executeError == null && !format.hasVideo) {
                validateAudioMedia(resultFile)
            }

            if (resultFile.length() <= 0L) {
                throw IOException("The downloaded file is empty")
            }
            val sourceSize = resultFile.length()
            Log.i(TAG, "Download source: ${resultFile.absolutePath}")
            Log.i(TAG, "Download source size: $sourceSize bytes")

            val published = try {
                publisher.publish(resultFile, hasVideo = format.hasVideo)
            } catch (error: Throwable) {
                Log.e(TAG, "Downloaded source preserved after publish failure: ${resultFile.absolutePath}", error)
                throw DownloadPublishException(error)
            }
            val sourceDeleted = resultFile.delete()
            Log.i(TAG, "Source cleanup result for ${resultFile.absolutePath}: $sourceDeleted")

            DownloadedFile(
                fileName = published.displayName,
                sizeBytes = published.sizeBytes,
                contentUri = published.contentUri,
                mimeType = published.mimeType,
                destinationLabel = published.destinationLabel,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Download failed", error)
            throw error
        } finally {
            synchronized(processLock) {
                if (activeProcessId == processId) activeProcessId = null
                if (canceledProcessId == processId) canceledProcessId = null
            }
        }
    }

    fun cancel(): Boolean {
        val processId = synchronized(processLock) {
            activeProcessId?.also { canceledProcessId = it }
        } ?: return false
        val destroyed = YoutubeDL.getInstance().destroyProcessById(processId)
        if (!destroyed) {
            synchronized(processLock) {
                if (canceledProcessId == processId) canceledProcessId = null
            }
        }
        return destroyed
    }

    private fun recoverFinalizedAudio(
        downloadRoot: File,
        baseName: String,
        expectedExtension: String?,
        processId: String,
        executeError: Throwable,
    ): File {
        if (isCancellationRequested(processId)) throw DownloadCanceledException()

        val resultFile = try {
            discoverCompletedOutput(downloadRoot, baseName, expectedExtension)
        } catch (validationError: Throwable) {
            executeError.addSuppressed(validationError)
            throw executeError
        }
        val initialSize = resultFile.length()
        Thread.sleep(FILE_STABILITY_INTERVAL_MS)

        if (isCancellationRequested(processId)) throw DownloadCanceledException()
        if (!resultFile.isFile || resultFile.length() != initialSize || initialSize <= 0L) {
            throw executeError
        }

        try {
            validateAudioMedia(resultFile)
        } catch (validationError: Throwable) {
            executeError.addSuppressed(validationError)
            throw executeError
        }
        Log.w(
            TAG,
            "yt-dlp reported an error after finalizing audio output; " +
                "validated final media file and continuing: ${resultFile.absolutePath}",
            executeError,
        )
        return resultFile
    }

    private fun discoverCompletedOutput(
        downloadRoot: File,
        baseName: String,
        expectedExtension: String?,
    ): File {
        val normalizedExtension = expectedExtension
            ?.trimStart('.')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("The selected format has no expected file extension")
        val matchingFiles = downloadRoot.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$baseName.") }
            .orEmpty()
        if (matchingFiles.any {
                val name = it.name.lowercase()
                name.contains(".part") || name.endsWith(".ytdl")
            }
        ) {
            throw IOException("yt-dlp left a temporary download artifact")
        }
        val finalFiles = matchingFiles.filter {
            it.extension.equals(normalizedExtension, ignoreCase = true)
        }
        if (finalFiles.size != 1) {
            throw IOException("Expected one finalized .$normalizedExtension output, found ${finalFiles.size}")
        }
        return finalFiles.single()
    }

    private fun validateAudioMedia(file: File) {
        var hasAudioTrack = false
        var trackDurationUs: Long? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val track = extractor.getTrackFormat(index)
                val mime = track.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    hasAudioTrack = true
                    if (track.containsKey(MediaFormat.KEY_DURATION)) {
                        trackDurationUs = track.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
        } finally {
            extractor.release()
        }
        if (!hasAudioTrack) throw IOException("Finalized media contains no audio track")

        val metadataDurationMs = MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
        if (trackDurationUs != null && trackDurationUs!! <= 0L) {
            throw IOException("Finalized audio track has an invalid duration")
        }
        if (metadataDurationMs != null && metadataDurationMs <= 0L) {
            throw IOException("Finalized media has an invalid duration")
        }
        Log.i(
            TAG,
            "Validated audio media: track=true, " +
                "durationMs=${metadataDurationMs ?: trackDurationUs?.div(1_000L) ?: "unavailable"}",
        )
    }

    private fun isCancellationRequested(processId: String): Boolean =
        synchronized(processLock) { canceledProcessId == processId }

    private fun safeFileName(title: String): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(120)
        return cleaned.ifBlank { "Preload video" }
    }

    private companion object {
        const val TAG = "PreloadDownloader"
        const val FILE_STABILITY_INTERVAL_MS = 500L
    }
}

private fun String?.safeLastLine(): String? = this
    ?.lineSequence()
    ?.lastOrNull { it.isNotBlank() }
    ?.trim()
    ?.take(240)
