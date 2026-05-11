package com.chhanda.ai.service

import android.content.Context
import android.util.Log
import androidx.work.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.app.NotificationCompat
import com.chhanda.ai.R

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        const val KEY_TOKEN = "token"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val NOTIFICATION_ID = 8008
        const val CHANNEL_ID = "download_channel"
    }

    override suspend fun doWork(): Result {
        val urlStr = inputData.getString(KEY_URL) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN) ?: ""

        val dir = applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, filename)
        val tempFile = File(dir, "$filename.download")

        try {
            setForeground(createForegroundInfo(0))
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service initiation failed: ${e.message}. Continuing in background.")
        }

        return try {
            downloadFile(urlStr, tempFile, file, token)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Download critical failure", e)
            Result.failure(workDataOf(KEY_STATUS to (e.message ?: "Network or Filesystem error")))
        }
    }

    private suspend fun downloadFile(urlStr: String, tempFile: File, finalFile: File, token: String) {
        var currentUrl = urlStr
        var useToken = token.isNotEmpty()
        var connection: HttpURLConnection
        var responseCode: Int
        
        // 1. Resolve redirects and handle Auth
        var redirectCount = 0
        do {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            
            if (currentUrl.contains("huggingface.co") && useToken) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            
            responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val loc = connection.getHeaderField("Location") ?: break
                val nextUrl = URL(URL(currentUrl), loc).toString()
                Log.d(TAG, "Redirecting to: $nextUrl")
                currentUrl = nextUrl
                redirectCount++
                connection.disconnect()
            } else {
                break
            }
        } while (redirectCount < 10)

        if (responseCode !in 200..299) {
            throw Exception("Server returned code $responseCode")
        }

        Log.i(TAG, "Starting download: $urlStr -> ${finalFile.name}")
        
        val totalBytes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            connection.contentLengthLong
        } else {
            connection.contentLength.toLong()
        }
        
        Log.i(TAG, "Server reported size: $totalBytes bytes")
        
        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(tempFile)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = 0L
        var lastUpdate = 0L

        inputStream.use { input ->
            outputStream.use { output ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 1000) {
                        val progress = if (totalBytes > 0) (totalRead.toFloat() / totalBytes * 100).toInt() else -1
                        setProgress(workDataOf(KEY_PROGRESS to progress))
                        setForeground(createForegroundInfo(progress))
                        lastUpdate = now
                    }
                }
            }
        }

        if (finalFile.exists()) {
            finalFile.delete()
        }
        
        if (tempFile.renameTo(finalFile)) {
            Log.i(TAG, "Download complete: ${finalFile.absolutePath}")
        } else {
            throw Exception("Failed to finalize download file - rename failed")
        }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val title = "Downloading Model"
        val content = if (progress >= 0) "$progress%" else "In progress..."
        
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(CHANNEL_ID, "Downloads", android.app.NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setProgress(100, if (progress >= 0) progress else 0, progress < 0)
            .build()

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
