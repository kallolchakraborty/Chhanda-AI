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
            val processName = getProcessName(this)?.replace(":", "_") ?: "main"
            java.io.File(filesDir, "boot_reached_${processName}.txt").writeText("${System.currentTimeMillis()}")
        } catch(e: Throwable) {}

        super.onCreate()
        crashHandler.initialize()
    }
    
    @javax.inject.Inject
    lateinit var crashHandler: com.chhanda.ai.util.CrashHandler

    private fun getProcessName(context: android.content.Context): String? {
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
