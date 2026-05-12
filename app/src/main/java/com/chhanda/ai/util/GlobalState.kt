package com.chhanda.ai.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object GlobalState {
    private val _isHotspotActive = MutableStateFlow(false)
    val isHotspotActive = _isHotspotActive.asStateFlow()

    fun setHotspotActive(active: Boolean) {
        _isHotspotActive.value = active
    }
}
