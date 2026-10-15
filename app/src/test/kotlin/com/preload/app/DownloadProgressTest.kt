package com.preload.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    @Test
    fun parsesTypicalBinaryProgressLine() {
        val progress = DownloadProgressParser.parse(
            callbackPercent = 42.3f,
            callbackEtaSeconds = 7L,
            rawLine = "[download]  42.3% of 30.41MiB at 2.72MiB/s ETA 00:07",
        )!!

        assertEquals(42.3f, progress.percent)
        assertEquals(31_887_196L, progress.totalBytes)
        assertEquals(2_852_127L, progress.bytesPerSecond)
        assertEquals(7L, progress.etaSeconds)
        assertEquals(13_488_284L, progress.downloadedBytes)
        assertTrue(progress.sizeUsesBinaryUnits)
        assertTrue(progress.speedUsesBinaryUnits)
    }

    @Test
    fun keepsDecimalSpeedUnitsIndependentFromBinarySizeUnits() {
        val progress = DownloadProgressParser.parse(
            callbackPercent = 50f,
            callbackEtaSeconds = 62L,
            rawLine = "[download] 50% of ~ 10MiB at 2.5MB/s ETA 01:02",
        )!!

        assertEquals(10_485_760L, progress.totalBytes)
        assertEquals(2_500_000L, progress.bytesPerSecond)
        assertTrue(progress.sizeUsesBinaryUnits)
        assertFalse(progress.speedUsesBinaryUnits)
    }

    @Test
    fun acceptsHourEtaAndFallsBackWhenCallbackCannotParseIt() {
        val progress = DownloadProgressParser.parse(
            callbackPercent = -1f,
            callbackEtaSeconds = -1L,
            rawLine = "[download]  1.0% of 1.00GiB at 1.00MiB/s ETA 01:02:03",
        )!!

        assertEquals(1f, progress.percent)
        assertEquals(3_723L, progress.etaSeconds)
    }

    @Test
    fun unknownFieldsStayNullAndUnrelatedLinesAreIgnored() {
        val progress = DownloadProgressParser.parse(
            callbackPercent = 10f,
            callbackEtaSeconds = -1L,
            rawLine = "[download] 10.0% of unknown size at Unknown speed",
        )!!

        assertEquals(10f, progress.percent)
        assertNull(progress.downloadedBytes)
        assertNull(progress.totalBytes)
        assertNull(progress.bytesPerSecond)
        assertNull(progress.etaSeconds)
        assertNull(DownloadProgressParser.parse(10f, 20L, "[youtube] Extracting URL"))
    }
}
