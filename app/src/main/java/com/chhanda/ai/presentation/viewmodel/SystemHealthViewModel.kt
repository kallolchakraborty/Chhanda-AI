package com.chhanda.ai.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chhanda.ai.util.ThermalStatusTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

/**
 * Senior Architectural Improvement: Decoupled Health & Telemetry ViewModel.
 *
 * This ViewModel isolates high-frequency system monitoring (RAM, CPU, Thermal) 
 * from the main SystemViewModel. This prevents the primary business logic 
 * from being cluttered by telemetry polling and reduces unnecessary 
 * UI recompositions.
 */
@HiltViewModel
class SystemHealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thermalStatusTracker: ThermalStatusTracker
) : ViewModel() {

    private val _ramUsage = MutableStateFlow("0 MB / 0 MB")
    val ramUsage: StateFlow<String> = _ramUsage.asStateFlow()

    private val _appStorageUsage = MutableStateFlow("Calculating...")
    val appStorageUsage: StateFlow<String> = _appStorageUsage.asStateFlow()

    val thermalStatus = thermalStatusTracker.thermalStatus
    private val _deviceTemperature = MutableStateFlow(0.0)
    val deviceTemperature: StateFlow<Double> = _deviceTemperature.asStateFlow()

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            while (true) {
                updateRamUsage()
                updateStorageUsage()
                updateTemperature()
                delay(2000) // Poll every 2 seconds
            }
        }
    }
    
    private fun updateTemperature() {
        val intent = try {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) { null }
        val rawTemp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        _deviceTemperature.value = rawTemp / 10.0
    }

    private fun updateRamUsage() {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            if (memoryInfo.totalMem <= 0) {
                // Fallback to Runtime memory for app-level tracking if system call fails
                val runtime = Runtime.getRuntime()
                val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val total = runtime.maxMemory() / (1024 * 1024)
                _ramUsage.value = "App: $used MB / $total MB"
                return
            }

            val usedGb = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024.0 * 1024.0)
            val totalGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            
            _ramUsage.value = String.format("%.1f GB / %.1f GB", usedGb, totalGb)
            
            // Log for debugging if it's still failing for the user
            android.util.Log.d("SystemHealth", "RAM Update: ${_ramUsage.value}")
        } catch (e: Exception) {
            android.util.Log.e("SystemHealth", "Failed to update RAM: ${e.message}")
            _ramUsage.value = "Err: RAM"
        }
    }

    private fun updateStorageUsage() {
        val filesDir = context.filesDir
        val totalSpace = filesDir.totalSpace / (1024 * 1024)
        val freeSpace = filesDir.freeSpace / (1024 * 1024)
        val usedSpace = totalSpace - freeSpace
        _appStorageUsage.value = "$usedSpace MB used of $totalSpace MB"
    }
}
