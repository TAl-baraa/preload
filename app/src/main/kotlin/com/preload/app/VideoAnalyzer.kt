package com.preload.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AnalyzedVideo(
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val site: String?,
    val webpageUrl: String?,
    val formats: List<SelectableFormat>,
)

data class SelectableFormat(
    val formatId: String,
    val extension: String?,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val bitrateKbps: Int?,
    val fileSizeBytes: Long?,
    val fileSizeIsApproximate: Boolean,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

class VideoAnalyzer(context: Context) {
    private val appContext = context.applicationContext
    private val initializationLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var initializationFailure: Throwable? = null

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { ensureInitialized() }
    }

    suspend fun analyze(url: String): AnalyzedVideo = withContext(Dispatchers.IO) {
        ensureInitialized()
        val info = YoutubeDL.getInstance().getInfo(url)

        AnalyzedVideo(
            title = info.title.orEmpty(),
            uploader = info.uploader?.takeIf { it.isNotBlank() },
            thumbnailUrl = info.thumbnail?.takeIf { it.isNotBlank() },
            durationSeconds = info.duration.takeIf { it > 0 },
            site = info.extractor?.takeIf { it.isNotBlank() },
            webpageUrl = info.webpageUrl?.takeIf { it.isNotBlank() },
            formats = selectUsefulFormats(
                info.formats.orEmpty().mapNotNull { format ->
                    val videoCodec = format.vcodec.normalizedCodec()
                    val audioCodec = format.acodec.normalizedCodec()
                    val hasVideo = videoCodec != null
                    val hasAudio = audioCodec != null
                    val formatId = format.formatId?.takeIf { it.isNotBlank() }

                    if (formatId == null || (!hasVideo && !hasAudio)) return@mapNotNull null

                    val exactSize = format.fileSize.takeIf { it > 0 }
                    val approximateSize = format.fileSizeApproximate.takeIf { it > 0 }
                    SelectableFormat(
                        formatId = formatId,
                        extension = format.ext?.takeIf { it.isNotBlank() }?.lowercase(),
                        width = format.width.takeIf { it > 0 },
                        height = format.height.takeIf { it > 0 },
                        fps = format.fps.takeIf { it > 0 },
                        videoCodec = videoCodec,
                        audioCodec = audioCodec,
                        bitrateKbps = format.tbr.takeIf { it > 0 }
                            ?: format.abr.takeIf { it > 0 },
                        fileSizeBytes = exactSize ?: approximateSize,
                        fileSizeIsApproximate = exactSize == null && approximateSize != null,
                        hasVideo = hasVideo,
                        hasAudio = hasAudio,
                    )
                },
            ),
        )
    }

    private fun selectUsefulFormats(formats: List<SelectableFormat>): List<SelectableFormat> {
        val videoFormats = formats
            .filter { it.hasVideo && it.height != null }
            .groupBy { it.height }
            .values
            .mapNotNull { sameResolution ->
                sameResolution.maxWithOrNull(
                    compareBy<SelectableFormat> { videoPreferenceScore(it) }
                        .thenBy { it.bitrateKbps ?: 0 }
                        .thenBy { it.fps ?: 0 },
                )
            }
            .sortedByDescending { it.height }

        val bestAudio = formats
            .filter { !it.hasVideo && it.hasAudio }
            .maxWithOrNull(
                compareBy<SelectableFormat> { it.bitrateKbps ?: 0 }
                    .thenBy { audioCompatibilityScore(it) },
            )

        return videoFormats + listOfNotNull(bestAudio)
    }

    private fun videoPreferenceScore(format: SelectableFormat): Int {
        var score = 0
        if (format.extension == "mp4") score += 50
        if (format.videoCodec?.startsWith("avc", ignoreCase = true) == true ||
            format.videoCodec?.startsWith("h264", ignoreCase = true) == true
        ) score += 40
        if (format.hasAudio) score += 25
        if (format.audioCodec?.startsWith("mp4a", ignoreCase = true) == true ||
            format.audioCodec?.startsWith("aac", ignoreCase = true) == true
        ) score += 15
        if (format.fileSizeBytes != null) score += 1
        return score
    }

    private fun audioCompatibilityScore(format: SelectableFormat): Int = when {
        format.extension == "m4a" -> 2
        format.audioCodec?.startsWith("mp4a", ignoreCase = true) == true -> 1
        else -> 0
    }

    private fun ensureInitialized() {
        if (initialized) return
        initializationFailure?.let { throw IllegalStateException("yt-dlp initialization failed", it) }

        synchronized(initializationLock) {
            if (initialized) return
            initializationFailure?.let { throw IllegalStateException("yt-dlp initialization failed", it) }

            try {
                YoutubeDL.getInstance().init(appContext)
                initialized = true
            } catch (error: Throwable) {
                initializationFailure = error
                throw error
            }
        }
    }
}

private fun String?.normalizedCodec(): String? = this
    ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
