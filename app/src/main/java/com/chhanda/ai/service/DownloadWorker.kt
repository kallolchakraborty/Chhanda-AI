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
        const val KEY_SPEED = "speed"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_STATUS = "status"
        const val NOTIFICATION_ID = 8008
        const val CHANNEL_ID = "download_channel"
    }

    override suspend fun doWork(): Result {
        val urlStr = inputData.getString(KEY_URL) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN) ?: ""

        val dir = File(applicationContext.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
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
            
            if (redirectCount == 0 && currentUrl.contains("huggingface.co") && useToken) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            
            // Resume support: check if temp file exists
            val existingSize = if (tempFile.exists()) tempFile.length() else 0L
            if (existingSize > 0) {
                connection.setRequestProperty("Range", "bytes=$existingSize-")
            }
            
            responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val loc = connection.getHeaderField("Location") ?: break
                val nextUrl = URL(URL(currentUrl), loc).toString()
                currentUrl = nextUrl
                redirectCount++
                connection.disconnect()
            } else {
                break
            }
        } while (redirectCount < 10)

        if (responseCode == 416) { // Range not satisfiable - likely already finished
            if (tempFile.exists()) {
                finalizeFile(tempFile, finalFile)
                return
            }
        }

        if (responseCode !in 200..299 && responseCode != 206) {
            throw Exception("Server returned code $responseCode for URL: $currentUrl")
        }

        val totalBytes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            connection.contentLengthLong
        } else {
            connection.contentLength.toLong()
        }
        
        val actualTotal = if (responseCode == 206) {
            val rangeHeader = connection.getHeaderField("Content-Range")
            if (rangeHeader != null) {
                rangeHeader.substringAfterLast("/").toLongOrNull() ?: totalBytes
            } else totalBytes
        } else totalBytes

        val inputStream = connection.inputStream
        val outputStream = FileOutputStream(tempFile, responseCode == 206)

        val buffer = ByteArray(16384)
        var bytesRead: Int
        var totalRead = if (responseCode == 206) tempFile.length() else 0L
        var lastUpdate = 0L
        var lastTotalRead = totalRead
        var speedBytesPerSec = 0L

        try {
            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1000) {
                            val progress = if (actualTotal > 0) (totalRead.toFloat() / actualTotal * 100).toInt().coerceIn(0, 99) else -1
                            
                            val timeDiff = (now - lastUpdate) / 1000.0
                            speedBytesPerSec = if (timeDiff > 0) ((totalRead - lastTotalRead) / timeDiff).toLong() else 0L
                            
                            setProgress(workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_SPEED to speedBytesPerSec,
                                KEY_BYTES_DOWNLOADED to totalRead,
                                KEY_BYTES_TOTAL to actualTotal
                            ))
                            
                            val speedStr = android.text.format.Formatter.formatFileSize(applicationContext, speedBytesPerSec) + "/s"
                            val downloadedStr = android.text.format.Formatter.formatFileSize(applicationContext, totalRead)
                            val totalStr = android.text.format.Formatter.formatFileSize(applicationContext, actualTotal)
                            
                            setForeground(createForegroundInfo(progress, "$downloadedStr / $totalStr ($speedStr)"))
                            
                            lastUpdate = now
                            lastTotalRead = totalRead
                        }
                    }
                }
            }
            finalizeFile(tempFile, finalFile)
        } catch (e: Exception) {
            throw e
        }
    }

    private suspend fun finalizeFile(tempFile: File, finalFile: File) {
        // Force 100% and Finalizing status
        try {
            setProgress(workDataOf(KEY_PROGRESS to 100))
            setForeground(createForegroundInfo(100, "Finalizing..."))
        } catch (_: Exception) {}

        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (tempFile.renameTo(finalFile)) {
            Log.i(TAG, "Download complete: ${finalFile.absolutePath}")
        } else {
            // Fallback: Copy if rename fails (rare but possible across filesystems)
            try {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            } catch (e: Exception) {
                throw Exception("Failed to finalize download file: ${e.message}")
            }
        }
    }

    private fun createForegroundInfo(progress: Int, status: String? = null): ForegroundInfo {
        val title = "Downloading Model"
        val content = status ?: (if (progress >= 0) "$progress%" else "In progress...")
        
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
