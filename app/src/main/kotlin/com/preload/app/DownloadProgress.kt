package com.preload.app

import kotlin.math.abs
import kotlin.math.roundToLong

data class DownloadProgress(
    val percent: Float? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val sizeUsesBinaryUnits: Boolean = true,
    val speedUsesBinaryUnits: Boolean = true,
)

object DownloadProgressParser {
    private val percentPattern = Regex("""^\[download]\s+(\d{1,3}(?:\.\d+)?)%""")
    private val totalPattern = Regex(
        """\bof\s+~?\s*(\d+(?:\.\d+)?)\s*(KiB|MiB|GiB|B|kB|KB|MB|GB)\b""",
    )
    private val speedPattern = Regex(
        """\bat\s+(\d+(?:\.\d+)?)\s*(KiB|MiB|GiB|B|kB|KB|MB|GB)/s\b""",
    )
    private val etaPattern = Regex("""\bETA\s+(\d+):(\d{2})(?::(\d{2}))?\b""")

    fun parse(callbackPercent: Float, callbackEtaSeconds: Long, rawLine: String): DownloadProgress? {
        val line = rawLine.trim()
        if (!line.startsWith("[download]")) return null

        val rawPercent = percentPattern.find(line)?.groupValues?.get(1)?.toFloatOrNull()
            ?.takeIf { it in 0f..100f }
            ?: return null
        val percent = callbackPercent
            .takeIf { it.isFinite() && abs(it - rawPercent) < 0.11f }
            ?: rawPercent
        val totalMatch = totalPattern.find(line)
        val speedMatch = speedPattern.find(line)
        val total = parseByteValue(totalMatch)
        val speed = parseByteValue(speedMatch)
        val parsedEta = parseEta(line)
        // youtubedl-android 0.18.1 names this callback value etaInSeconds and
        // StreamProcessExtractor derives it from this same line's ETA field.
        val eta = callbackEtaSeconds
            .takeIf { parsedEta != null && it in 0..MAX_ETA_SECONDS }
            ?: parsedEta
        val sizeUsesBinaryUnits = totalMatch?.usesBinaryUnits() ?: true
        val speedUsesBinaryUnits = speedMatch?.usesBinaryUnits() ?: true

        return DownloadProgress(
            percent = percent,
            // Approximation for presentation only; completion validation uses the finalized file.
            downloadedBytes = total?.let { (it * (percent / 100.0)).roundToLong() },
            totalBytes = total,
            bytesPerSecond = speed,
            etaSeconds = eta,
            sizeUsesBinaryUnits = sizeUsesBinaryUnits,
            speedUsesBinaryUnits = speedUsesBinaryUnits,
        )
    }

    private fun parseByteValue(match: MatchResult?): Long? {
        match ?: return null
        val value = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        val multiplier = when (match.groupValues[2]) {
            "B" -> 1.0
            "KiB" -> 1024.0
            "MiB" -> 1024.0 * 1024.0
            "GiB" -> 1024.0 * 1024.0 * 1024.0
            "kB", "KB" -> 1_000.0
            "MB" -> 1_000_000.0
            "GB" -> 1_000_000_000.0
            else -> return null
        }
        val bytes = value * multiplier
        return bytes
            .takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }
            ?.roundToLong()
    }

    private fun parseEta(line: String): Long? {
        val match = etaPattern.find(line) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        val third = match.groupValues[3].toLongOrNull()
        val seconds = if (third == null) first * 60L + second else first * 3600L + second * 60L + third
        return seconds.takeIf { it in 0..MAX_ETA_SECONDS }
    }

    private const val MAX_ETA_SECONDS = 7L * 24L * 60L * 60L
}

private fun MatchResult.usesBinaryUnits(): Boolean = groupValues[2].contains('i')

fun formatProgressBytes(bytes: Long, binary: Boolean): String {
    val step = if (binary) 1024.0 else 1000.0
    val labels = if (binary) listOf("B", "KiB", "MiB", "GiB") else listOf("B", "KB", "MB", "GB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    while (value >= step && unitIndex < labels.lastIndex) {
        value /= step
        unitIndex++
    }
    val formatted = when {
        unitIndex == 0 -> "%.0f".format(value)
        value >= 100 -> "%.0f".format(value)
        value >= 10 -> "%.1f".format(value)
        else -> "%.2f".format(value).trimEnd('0').trimEnd('.')
    }
    return "$formatted ${labels[unitIndex]}"
}

fun formatEta(seconds: Long): String {
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%02d:%02d".format(minutes, remainingSeconds)
}
