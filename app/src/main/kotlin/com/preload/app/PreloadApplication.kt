package com.preload.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PreloadApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { PreloadDatabase.getInstance(this) }
    val downloadQueue by lazy {
        DownloadQueueManager(
            dao = database.downloadJobDao(),
            downloader = VideoDownloadExecutor(VideoDownloader(this)),
            onWorkAvailable = { DownloadForegroundService.start(this) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            if (database.downloadJobDao().pendingCount() > 0) {
                DownloadForegroundService.start(this@PreloadApplication)
            }
        }
    }
}
