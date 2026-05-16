package com.chhanda.ai.domain.model

sealed class HardwareStatus {
    object Normal : HardwareStatus()
    object Throttled : HardwareStatus()
    object Critical : HardwareStatus()
    
    val isThrottled: Boolean get() = this != Normal
}
