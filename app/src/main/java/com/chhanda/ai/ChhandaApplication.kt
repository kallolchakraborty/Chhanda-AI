package com.chhanda.ai

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ChhandaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Ultimate Stability: Global Crash Handler to prevent silent kills
        // Logs the stacktrace to Logcat even if the app crashes before Hilt is ready.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRITICAL_BOOT", "FATAL CRASH on thread ${thread.name}: ${throwable.message}")
            Log.e("CRITICAL_BOOT", Log.getStackTraceString(throwable))
            
            // Allow the OS to eventually kill the app, but we've captured the log
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        Log.i("BOOT", "Chhanda Engine v18 initializing...")
    }
}
