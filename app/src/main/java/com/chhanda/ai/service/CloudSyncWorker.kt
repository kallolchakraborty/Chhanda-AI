package com.chhanda.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.chhanda.ai.data.sync.CloudSyncManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CloudSyncEntryPoint {
        fun cloudSyncManager(): CloudSyncManager
    }

    companion object {
        private const val CHANNEL_ID = "cloud_sync_channel"
        private const val NOTIFICATION_ID = 9009
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
        
        if (account == null) {
            // No Google account, no sync will happen
            return Result.success()
        }

        val entryPoint = EntryPointAccessors.fromApplication(context, CloudSyncEntryPoint::class.java)
        val cloudSyncManager = entryPoint.cloudSyncManager()

        createNotificationChannel()
        try {
            setForeground(createForegroundInfo("Syncing chats to Google Drive..."))
        } catch (e: Exception) {
            // Ignore foreground exception in case worker is not run in foreground
        }

        val success = cloudSyncManager.backupHistory(account)
        return if (success) {
            showCompletionNotification(true)
            Result.success()
        } else {
            showCompletionNotification(false)
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Cloud Sync", NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Cloud Sync")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(success: Boolean) {
        val title = if (success) "Cloud Sync Complete" else "Cloud Sync Failed"
        val text = if (success) "Your chats were securely backed up to Google Drive." else "Failed to back up chats. Will try again later."
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
}
