package com.chhanda.ai.data.inference

import android.content.Context
import com.chhanda.ai.service.ChhandaForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chhandaServer: ChhandaServer
) {
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    val tunnelUrl = MutableStateFlow("") // Simplified for now
    val isTunnelActive = MutableStateFlow(false)

    fun startServer(port: Int, maxDevices: Int) {
        ChhandaForegroundService.start(context, port, maxDevices)
        _isServerRunning.value = true
        _boundPort.value = port
    }

    fun stopServer() {
        ChhandaForegroundService.stop(context)
        _isServerRunning.value = false
        _boundPort.value = 0
    }

    fun updateStatus(running: Boolean, port: Int, error: String? = null) {
        _isServerRunning.value = running
        _boundPort.value = port
        _serverError.value = error
    }
}
