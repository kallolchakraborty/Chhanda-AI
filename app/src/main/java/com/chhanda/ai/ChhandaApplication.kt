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

        super.onCreate()
        crashHandler.initialize()
    }
    
    @javax.inject.Inject
    lateinit var crashHandler: com.chhanda.ai.util.CrashHandler
}
