package com.chhanda.ai.util

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

import com.chhanda.ai.domain.model.HardwareStatus

@Singleton
class ThermalStatusTracker @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    private val _thermalStatus = MutableStateFlow<HardwareStatus>(HardwareStatus.Normal)
    val thermalStatus: StateFlow<HardwareStatus> = _thermalStatus.asStateFlow()

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        _thermalStatus.value = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE -> HardwareStatus.Normal
            PowerManager.THERMAL_STATUS_SEVERE -> HardwareStatus.Throttled
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> HardwareStatus.Critical
            else -> HardwareStatus.Normal
        }
    }

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener(context.mainExecutor, listener)
        }
    }
}
