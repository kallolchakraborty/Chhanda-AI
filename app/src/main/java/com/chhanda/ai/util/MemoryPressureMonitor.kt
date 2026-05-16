package com.chhanda.ai.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Senior Architect Implementation: MemoryPressureMonitor
 * Monitors system-level memory pressure and provides reactive signals to the inference engine.
 * Enables adaptive behavior (e.g., reducing image resolution or clearing caches) before an OOM occurs.
 */
@Singleton
class MemoryPressureMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : ComponentCallbacks2 {

    private val _memoryLevel = MutableStateFlow(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    val memoryLevel: StateFlow<Int> = _memoryLevel.asStateFlow()

    private val _isLowMemory = MutableStateFlow(false)
    val isLowMemory: StateFlow<Boolean> = _isLowMemory.asStateFlow()

    init {
        context.registerComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        _memoryLevel.value = level
        Log.w("MemoryMonitor", "onTrimMemory: Level $level")
        
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                _isLowMemory.value = true
            }
            else -> {
                _isLowMemory.value = false
            }
        }
    }

    override fun onLowMemory() {
        _isLowMemory.value = true
        Log.e("MemoryMonitor", "onLowMemory: System-wide critical memory pressure!")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
}
