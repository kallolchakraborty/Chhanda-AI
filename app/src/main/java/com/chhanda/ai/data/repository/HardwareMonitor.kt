package com.chhanda.ai.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import com.chhanda.ai.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HardwareMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmEngine: LLMEngine,
    private val metricsManager: RAGMetricsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _processorInfo = MutableStateFlow("Detecting...")
    val processorInfo: StateFlow<String> = _processorInfo.asStateFlow()

    private val _tokensPerSec = MutableStateFlow("0.0")
    val tokensPerSec: StateFlow<String> = _tokensPerSec.asStateFlow()

    private val _batteryTemp = MutableStateFlow(0.0)
    val batteryTemp: StateFlow<Double> = _batteryTemp.asStateFlow()

    private val _ramUsage = MutableStateFlow(0.0)
    val ramUsage: StateFlow<Double> = _ramUsage.asStateFlow()

    private val _storageMetrics = MutableStateFlow(StorageMetrics(0, 0, 0, 0))
    val storageMetrics: StateFlow<StorageMetrics> = _storageMetrics.asStateFlow()

    private val _latencyMetrics = MutableStateFlow(LatencyMetrics(0, 0, 0))
    val latencyMetrics: StateFlow<LatencyMetrics> = _latencyMetrics.asStateFlow()

    private val _throughputMetrics = MutableStateFlow(ThroughputMetrics(0.0, 0.0))
    val throughputMetrics: StateFlow<ThroughputMetrics> = _throughputMetrics.asStateFlow()

    private val _memoryMetrics = MutableStateFlow(MemoryMetrics(0, 0.0, 0f))
    val memoryMetrics: StateFlow<MemoryMetrics> = _memoryMetrics.asStateFlow()

    private val _qualityMetrics = MutableStateFlow(QualityMetrics(0f, 0f))
    val qualityMetrics: StateFlow<QualityMetrics> = _qualityMetrics.asStateFlow()

    private val _costMetrics = MutableStateFlow(CostMetrics("", "", ""))
    val costMetrics: StateFlow<CostMetrics> = _costMetrics.asStateFlow()

    private val isAppVisible = MutableStateFlow(true)

    init {
        detectHardware()
        startMonitoring()
        observePerformance()
    }

    fun setAppVisibility(visible: Boolean) {
        isAppVisible.value = visible
    }

    private fun detectHardware() {
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuName = getCpuName()
        _processorInfo.value = "$cpuName\n$cores Cores | $cores Threads"
    }

    fun startMonitoring() {
        scope.launch {
            while (true) {
                if (isAppVisible.value) {
                    updateRamUsage()
                    updateBatteryStats()
                    updateStorageMetrics()
                    updateRagMetrics()
                }
                delay(2000)
            }
        }
    }

    fun setPerformanceMetrics(tps: Double) {
        _tokensPerSec.value = "%.1f".format(tps)
    }

    private fun updateRamUsage() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val usedMem = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024 * 1024)
        _ramUsage.value = usedMem
    }

    private fun updateBatteryStats() {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) { null }
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        _batteryTemp.value = rawTemp / 10.0
    }

    private fun updateStorageMetrics() {
        val statFs = StatFs(context.filesDir.path)
        val available = statFs.availableBlocksLong * statFs.blockSizeLong
        val total = statFs.blockCountLong * statFs.blockSizeLong
        
        val appDir = context.filesDir.parentFile
        val appSize = appDir?.walkBottomUp()?.map { it.length() }?.sum() ?: 0L
        
        val vectorFile = File(context.getDatabasePath("shared_rag_db").absolutePath)
        val vectorSize = if (vectorFile.exists()) vectorFile.length() else 0L
        
        _storageMetrics.value = StorageMetrics(appSize, available, total, vectorSize)
    }

    private fun updateRagMetrics() {
        _latencyMetrics.value = metricsManager.getLatencyMetrics()
        _throughputMetrics.value = ThroughputMetrics(0.0, 0.0) // Placeholder if not in manager
        _memoryMetrics.value = metricsManager.getMemoryMetrics()
        _qualityMetrics.value = metricsManager.getQualityMetrics()
        _costMetrics.value = metricsManager.getCostMetrics()
    }

    private fun observePerformance() {
        scope.launch {
            llmEngine.performanceMetrics.collect { tps ->
                _tokensPerSec.value = "%.1f".format(tps)
            }
        }
    }

    private fun getCpuName(): String {
        return try {
            val scanner = java.util.Scanner(File("/proc/cpuinfo"))
            var cpuName = "Unknown"
            while (scanner.hasNextLine()) {
                val line = scanner.nextLine()
                if (line.startsWith("Hardware") || line.startsWith("model name")) {
                    cpuName = line.split(":")[1].trim()
                    break
                }
            }
            scanner.close()
            cpuName
        } catch (e: Exception) {
            android.os.Build.HARDWARE ?: "Unknown"
        }
    }

    data class StorageMetrics(
        val appUsedBytes: Long,
        val deviceAvailableBytes: Long,
        val deviceTotalBytes: Long,
        val vectorDbBytes: Long
    )
}
