package com.preload.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

data class PublishedMedia(
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val destinationLabel: String,
    val sizeBytes: Long,
)

class MediaStorePublisher(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun publish(source: File): PublishedMedia {
        require(source.isFile && source.length() > 0L) { "Source media file is missing or empty" }
        val mimeType = mimeTypeFor(source.name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishModern(source, mimeType)
        } else {
            publishLegacy(source, mimeType)
        }
    }

    private fun publishModern(source: File, mimeType: String): PublishedMedia {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DESTINATION_FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned no Uri")
        Log.i(TAG, "MediaStore insert Uri: $uri")

        try {
            val bytesCopied = resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open MediaStore output stream")
            Log.i(TAG, "Copied $bytesCopied bytes to MediaStore")
            if (bytesCopied != source.length() || bytesCopied <= 0L) {
                throw IOException("Published byte count does not match the source file")
            }

            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, published, null, null) != 1) {
                throw IOException("Could not finalize the MediaStore item")
            }
            verifyModern(uri, source.name, mimeType, relativePath, bytesCopied)
            Log.i(TAG, "MediaStore publish succeeded: $uri")
            return PublishedMedia(uri, source.name, mimeType, DESTINATION_LABEL, bytesCopied)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Log.e(TAG, "MediaStore publish failed for ${source.name}", error)
            throw error
        }
    }

    private fun verifyModern(
        uri: Uri,
        expectedName: String,
        expectedMimeType: String,
        expectedRelativePath: String,
        expectedSize: Long,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("Published MediaStore item was not found")
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            if (name != expectedName || mime != expectedMimeType ||
                path.trimEnd('/') != expectedRelativePath || size != expectedSize
            ) {
                throw IOException("Published MediaStore metadata verification failed")
            }
        } ?: throw IOException("Could not verify the published MediaStore item")
    }

    @Suppress("DEPRECATION")
    private suspend fun publishLegacy(source: File, mimeType: String): PublishedMedia {
        val downloadDirectory = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve(DESTINATION_FOLDER)
        if (!downloadDirectory.exists() && !downloadDirectory.mkdirs()) {
            throw IOException("Could not create the public download directory")
        }
        val destination = downloadDirectory.resolve(source.name)
        try {
            val bytesCopied = source.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Copied $bytesCopied bytes to legacy public Downloads")
            if (bytesCopied != source.length() || bytesCopied <= 0L) {
                throw IOException("Published byte count does not match the source file")
            }
            val uri = scanLegacyFile(destination, mimeType)
                ?: throw IOException("Media scanner returned no content Uri")
            Log.i(TAG, "Legacy MediaStore publish succeeded: $uri")
            return PublishedMedia(uri, destination.name, mimeType, DESTINATION_LABEL, bytesCopied)
        } catch (error: Throwable) {
            if (destination.exists() && !destination.delete()) {
                Log.w(TAG, "Could not clean up incomplete legacy destination: $destination")
            }
            Log.e(TAG, "Legacy publish failed for ${source.name}", error)
            throw error
        }
    }

    private suspend fun scanLegacyFile(file: File, mimeType: String): Uri? =
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
            ) { _, uri ->
                if (continuation.isActive) continuation.resume(uri)
            }
        }

    private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "PreloadPublisher"
        const val DESTINATION_FOLDER = "Preload"
        const val DESTINATION_LABEL = "Downloads/Preload"
    }
}
