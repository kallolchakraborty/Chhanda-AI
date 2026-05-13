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
        
        Log.i("BOOT", "Chhanda Engine v18 initializing...")
        
        Log.i("BOOT", "Chhanda Engine v18 initializing...")
    }
}
