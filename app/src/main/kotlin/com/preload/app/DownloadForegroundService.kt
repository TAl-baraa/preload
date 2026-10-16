package com.preload.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val application by lazy { applicationContext as PreloadApplication }
    private val queue by lazy { application.downloadQueue }
    private val dao by lazy { application.database.downloadJobDao() }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var observationJob: Job? = null
    private var latestStartId = 0
    private var hasSeenWork = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observationJob = serviceScope.launch {
            queue.activeAndQueuedJobs.collectLatest { jobs ->
                val state = notificationState(jobs)
                if (state == null) {
                    if (hasSeenWork) stopServiceCleanly(latestStartId)
                    return@collectLatest
                }
                hasSeenWork = true
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val initial = buildPreparingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_JOB_ID)?.let { jobId ->
                serviceScope.launch { queue.cancel(jobId) }
            }
        }

        queue.start()
        serviceScope.launch {
            delay(750L)
            if (dao.pendingCount() == 0) stopServiceCleanly(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.stopForSystemTimeout()
        stopServiceCleanly(startId)
    }

    private fun buildPreparingNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_download_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_preparing_download))
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()

    private fun buildNotification(state: DownloadNotificationState): Notification {
        val details = buildList {
            state.percent?.let { add(getString(R.string.download_percent_value, it)) }
            state.bytesPerSecond?.let { add(getString(R.string.download_speed_value, formatBytes(it))) }
            state.etaSeconds?.let { add(formatDuration(it)) }
            if (state.queuedCount > 0) add(getString(R.string.notification_queued_count, state.queuedCount))
        }.joinToString(" • ")
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(getString(R.string.notification_downloading_title, state.title))
            .setContentText(details.ifBlank { getString(R.string.downloading) })
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)

        state.percent?.let { builder.setProgress(100, it, false) }
            ?: builder.setProgress(0, 0, true)
        state.activeJobId?.let { builder.addAction(cancelAction(it)) }
        return builder.build()
    }

    private fun cancelAction(jobId: String): Notification.Action {
        val intent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_JOB_ID, jobId)
        }
        val pendingIntent = PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_download_notification),
            getString(R.string.cancel),
            pendingIntent,
        ).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_REQUEST_CODE,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_downloads_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun stopServiceCleanly(startId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelfResult(startId)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val OPEN_REQUEST_CODE = 1002
        private const val CANCEL_REQUEST_CODE = 1003
        private const val ACTION_START = "com.preload.app.action.START_DOWNLOADS"
        private const val ACTION_CANCEL = "com.preload.app.action.CANCEL_DOWNLOAD"
        private const val EXTRA_JOB_ID = "job_id"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                android.util.Log.e("PreloadDownloadService", "Could not start foreground service", error)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 10) "%.0f MB".format(megabytes) else "%.1f MB".format(megabytes)
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
