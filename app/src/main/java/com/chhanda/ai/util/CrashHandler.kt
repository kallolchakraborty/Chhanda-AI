package com.chhanda.ai.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashHandler @Inject constructor(
    private val logManager: AppLogManager
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun initialize() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        startAnrWatchdog()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val stackTrace = Log.getStackTraceString(throwable)
        logManager.addLog("CRASH", "Uncaught Exception in ${thread.name}: ${throwable.message}\n$stackTrace", "ERROR")
        
        // Ensure logs are saved immediately before process death
        // Note: This is best-effort.
        
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * Senior Implementation: ANR Watchdog
     * Monitors the Main Thread for hangs. If the UI thread is blocked for > 5 seconds,
     * it logs a warning and the current stack trace.
     */
    private fun startAnrWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        val watchdogThread = Thread {
            while (true) {
                var tick = false
                mainHandler.post { tick = true }
                
                try {
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    break
                }

                if (!tick) {
                    val stackTrace = Looper.getMainLooper().thread.stackTrace
                    val stackTraceStr = stackTrace.joinToString("\n") { "    at $it" }
                    logManager.addLog("ANR", "Main Thread Blocked Detected!\n$stackTraceStr", "WARNING")
                    Log.e("ANRWatchdog", "Application Not Responding detected!")
                }
            }
        }
        watchdogThread.name = "ANR-Watchdog"
        watchdogThread.priority = Thread.MAX_PRIORITY
        watchdogThread.start()
    }
}
