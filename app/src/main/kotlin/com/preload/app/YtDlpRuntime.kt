package com.preload.app

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL

object YtDlpRuntime {
    private const val TAG = "PreloadYtDlp"
    const val LIBRARY_VERSION = "0.18.1"

    @Volatile
    private var ready = false

    fun ensureReady(context: Context) {
        if (ready) return

        synchronized(this) {
            if (ready) return

            val appContext = context.applicationContext
            val youtubeDL = YoutubeDL.getInstance()
            youtubeDL.init(appContext)
            val bundledOrInstalledVersion = youtubeDL.versionName(appContext)
            Log.i(
                TAG,
                "youtubedl-android=$LIBRARY_VERSION, yt-dlp before update=$bundledOrInstalledVersion",
            )

            val updateStatus = runCatching {
                youtubeDL.updateYoutubeDL(
                    appContext,
                    YoutubeDL.UpdateChannel.STABLE,
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not update yt-dlp stable; continuing with installed runtime", error)
            }.getOrNull()
            val effectiveVersion = youtubeDL.versionName(appContext)
            Log.i(
                TAG,
                "yt-dlp stable update status=${updateStatus ?: "FAILED"}, " +
                    "effective version=$effectiveVersion",
            )
            ready = true
        }
    }
}
