package com.preload.app

import android.app.Application

class PreloadApplication : Application() {
    val database by lazy { PreloadDatabase.getInstance(this) }
    val downloadQueue by lazy {
        DownloadQueueManager(
            dao = database.downloadJobDao(),
            downloader = VideoDownloadExecutor(VideoDownloader(this)),
        )
    }

    override fun onCreate() {
        super.onCreate()
        downloadQueue.start()
    }
}
