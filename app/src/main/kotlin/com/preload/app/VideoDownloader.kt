package com.preload.app

import android.content.Context
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

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().init(appContext) }
    }

    suspend fun download(
        url: String,
        title: String,
        format: SelectableFormat,
        onProgress: (Float) -> Unit,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        require(format.hasVideo && format.hasAudio) {
            "Only combined video and audio formats can be downloaded"
        }

        YoutubeDL.getInstance().init(appContext)
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
        }

        try {
            val response = YoutubeDL.getInstance().execute(
                request,
                processId,
            ) { progress, _, _ ->
                onProgress(progress.coerceIn(0f, 100f))
            }
            if (response.exitCode != 0) {
                Log.e(TAG, "yt-dlp failed (${response.exitCode}): ${response.err}")
                throw IOException(response.err.safeLastLine() ?: "yt-dlp exited with an error")
            }

            val resultFile = downloadRoot.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.name.startsWith("$baseName.") &&
                        !file.name.endsWith(".part")
                }
                ?.maxByOrNull(File::length)
                ?: throw IOException("yt-dlp completed without producing a media file")

            if (resultFile.length() <= 0L) {
                throw IOException("The downloaded file is empty")
            }
            val sourceSize = resultFile.length()
            Log.i(TAG, "Download source: ${resultFile.absolutePath}")
            Log.i(TAG, "Download source size: $sourceSize bytes")

            val published = try {
                publisher.publish(resultFile)
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
        } catch (error: YoutubeDL.CanceledException) {
            throw DownloadCanceledException()
        } catch (error: Throwable) {
            Log.e(TAG, "Download failed", error)
            throw error
        } finally {
            synchronized(processLock) {
                if (activeProcessId == processId) activeProcessId = null
            }
        }
    }

    fun cancel(): Boolean {
        val processId = synchronized(processLock) { activeProcessId } ?: return false
        return YoutubeDL.getInstance().destroyProcessById(processId)
    }

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
    }
}

private fun String?.safeLastLine(): String? = this
    ?.lineSequence()
    ?.lastOrNull { it.isNotBlank() }
    ?.trim()
    ?.take(240)
