package com.chhanda.ai

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ChhandaApplication : Application() {
    
    private var startTime: Long = 0

    override fun onCreate() {
        // STEP 1: Marker for adb debugging
        try {
            java.io.File(filesDir, "boot_marker.txt").writeText("BOOT_REACHED_${System.currentTimeMillis()}")
        } catch(e: Exception) {}

        // STEP 2: Early Crash Handler (Persists logs to disk)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                java.io.File(filesDir, "crash_log.txt").writeText("FATAL_CRASH:\n${android.util.Log.getStackTraceString(throwable)}")
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // STEP 3: Minimal super call. Hilt will initialize the component here.
        // We do NOT call any third party libraries (PDFBox, etc) here anymore.
        super.onCreate()
        
        android.util.Log.i("CHHANDA_BOOT", "Application Process Started Successfully.")
    }
}
