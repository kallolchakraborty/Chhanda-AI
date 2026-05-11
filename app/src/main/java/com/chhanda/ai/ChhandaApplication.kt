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
        super.onCreate()
        startTime = System.currentTimeMillis()
        
        // Ultimate Stability: Global Crash Handler to make the app "crash proof"
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRITICAL_BOOT", "FATAL CRASH on thread ${thread.name}: ${throwable.message}")
            Log.e("CRITICAL_BOOT", Log.getStackTraceString(throwable))
            
            val runningTime = System.currentTimeMillis() - startTime
            
            // If the app crashes after being stable for 5 seconds, attempt to auto-restart.
            // This hides the crash dialog and keeps the gateway alive.
            if (runningTime > 5000) {
                Log.i("CRITICAL_BOOT", "App was running for ${runningTime}ms. Attempting graceful restart...")
                try {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("CRITICAL_BOOT", "Failed to restart app", e)
                }
            } else {
                Log.w("CRITICAL_BOOT", "Crash occurred too close to startup (${runningTime}ms). Not restarting to prevent boot loop.")
            }
            
            // Kill the process to ensure a clean state and prevent ANR
            Process.killProcess(Process.myPid())
            System.exit(10)
        }
        
        Log.i("BOOT", "Chhanda Engine v18 initializing...")
    }
}
