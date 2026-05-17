package com.chhanda.ai.presentation.viewmodel

import android.app.DownloadManager
import android.content.Context
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.SettingsRepository
import com.chhanda.ai.data.repository.SecurityRepository
import com.chhanda.ai.data.repository.HardwareMonitor
import com.chhanda.ai.data.repository.NetworkManager
import com.chhanda.ai.data.repository.ModelProvisioner
import com.chhanda.ai.data.inference.ServerOrchestrator
import com.chhanda.ai.data.inference.ChhandaServer
import com.chhanda.ai.domain.model.*
import com.chhanda.ai.domain.service.IngestionManager
import com.chhanda.ai.data.sync.CloudSyncManager
import com.chhanda.ai.util.AppLogManager
import com.chhanda.ai.util.ThermalStatusTracker
import com.chhanda.ai.presentation.ui.DownloadModelInfo
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val llmEngine = mockk<LLMEngine>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val deviceDao = mockk<com.chhanda.ai.data.repository.DeviceDao>(relaxed = true)
    private val metricsManager = mockk<RAGMetricsManager>(relaxed = true)
    private val thermalStatusTracker = mockk<ThermalStatusTracker>(relaxed = true)
    private val hardwareMonitor = mockk<HardwareMonitor>(relaxed = true)
    private val networkManager = mockk<NetworkManager>(relaxed = true)
    private val serverOrchestrator = mockk<ServerOrchestrator>(relaxed = true)
    private val modelProvisioner = mockk<ModelProvisioner>(relaxed = true)
    private val securityRepository = mockk<SecurityRepository>(relaxed = true)
    private val appLogManager = mockk<AppLogManager>(relaxed = true)
    private val ingestionManager = mockk<IngestionManager>(relaxed = true)
    private val chhandaServer = mockk<ChhandaServer>(relaxed = true)
    private val cloudSyncManager = mockk<CloudSyncManager>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)

    private lateinit var viewModel: SystemViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        
        val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
        mockkStatic(androidx.work.WorkManager::class)
        every { androidx.work.WorkManager.getInstance(any()) } returns workManager
        
        val dummyFilesDir = java.io.File("/tmp")
        every { context.filesDir } returns dummyFilesDir
        every { context.getExternalFilesDir(any()) } returns dummyFilesDir
        
        // Mock SettingsRepository flows
        every { settingsRepository.darkModeFlow } returns flowOf(true)
        every { settingsRepository.serverPortFlow } returns flowOf("8888")
        every { settingsRepository.contextLengthFlow } returns flowOf("2048")
        every { settingsRepository.maxDevicesFlow } returns flowOf(5)
        every { settingsRepository.publicUrlFlow } returns flowOf("")
        every { settingsRepository.darkModeFlow } returns flowOf(true)
        every { settingsRepository.appLanguageFlow } returns flowOf("English")
        every { settingsRepository.vectorDbCapacityFlow } returns flowOf(1)
        every { settingsRepository.autoDeleteDaysFlow } returns flowOf(7)
        every { settingsRepository.autoDeleteEnabledFlow } returns flowOf(true)
        every { settingsRepository.turboQuantEnabledFlow } returns flowOf(true)
        every { settingsRepository.selectedVoiceFlow } returns flowOf("Kallol (Indian Male)")
        every { settingsRepository.ragEnabledFlow } returns flowOf(true)
        every { settingsRepository.thinkingModeEnabledFlow } returns flowOf(true)
        every { settingsRepository.privacyShieldEnabledFlow } returns flowOf(true)
        every { settingsRepository.activeModelFlow } returns flowOf("gemma-4")
        every { settingsRepository.appSecurityEnabledFlow } returns flowOf(false)

        // Mock SecurityRepository flows
        every { securityRepository.hfToken } returns MutableStateFlow<String>("test_token")
        every { securityRepository.apiKey } returns MutableStateFlow<String>("test_key")

        // Mock HardwareMonitor flows
        every { hardwareMonitor.latencyMetrics } returns MutableStateFlow<LatencyMetrics>(LatencyMetrics(0, 0, 0))
        every { hardwareMonitor.throughputMetrics } returns MutableStateFlow<ThroughputMetrics>(ThroughputMetrics(0.0, 0.0))
        every { hardwareMonitor.memoryMetrics } returns MutableStateFlow<MemoryMetrics>(MemoryMetrics(0L, 0.0, 0.0f))
        every { hardwareMonitor.qualityMetrics } returns MutableStateFlow<QualityMetrics>(QualityMetrics(0.0f, 0.0f))
        every { hardwareMonitor.costMetrics } returns MutableStateFlow<CostMetrics>(CostMetrics("0", "0", "0"))
        every { hardwareMonitor.processorInfo } returns MutableStateFlow<String>("CPU")
        every { hardwareMonitor.tokensPerSec } returns MutableStateFlow<String>("0.0")
        every { hardwareMonitor.batteryTemp } returns MutableStateFlow<Double>(0.0)
        every { hardwareMonitor.storageMetrics } returns MutableStateFlow<HardwareMonitor.StorageMetrics>(HardwareMonitor.StorageMetrics(0L, 0L, 0L, 0L))
        every { hardwareMonitor.tpsHistory } returns MutableStateFlow<List<Double>>(emptyList())
        every { hardwareMonitor.ramHistory } returns MutableStateFlow<List<Double>>(emptyList())
        every { hardwareMonitor.sessionTokens } returns MutableStateFlow<Long>(0L)
        every { hardwareMonitor.sessionCostSaved } returns MutableStateFlow<Double>(0.0)
        every { hardwareMonitor.setAppVisibility(any()) } just Runs
        every { hardwareMonitor.setPerformanceMetrics(any()) } just Runs

        // Mock ModelProvisioner flows
        every { modelProvisioner.ownedModels } returns MutableStateFlow(emptyList())
        every { modelProvisioner.sharedModels } returns MutableStateFlow(emptyList())
        every { modelProvisioner.downloadableModels } returns MutableStateFlow(emptyList())
        every { modelProvisioner.isScanning } returns MutableStateFlow(false)
        every { modelProvisioner.downloadProgress } returns MutableStateFlow<Map<String, Float>>(emptyMap())
        every { modelProvisioner.downloadStatus } returns MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
        every { modelProvisioner.downloadPauseFlow } returns MutableStateFlow<Map<String, Boolean>>(emptyMap())

        // Mock NetworkManager flows
        every { networkManager.isVpnActive } returns MutableStateFlow(false)
        every { networkManager.allIps } returns MutableStateFlow(emptyList())
        every { networkManager.isConnected } returns MutableStateFlow(true)

        // Mock ServerOrchestrator flows
        every { serverOrchestrator.isServerRunning } returns MutableStateFlow(false)
        every { serverOrchestrator.boundPort } returns MutableStateFlow(0)
        every { serverOrchestrator.serverError } returns MutableStateFlow(null)
        every { serverOrchestrator.tunnelUrl } returns MutableStateFlow("")
        every { serverOrchestrator.isModelLoading } returns MutableStateFlow(false)
        every { serverOrchestrator.isTunnelActive } returns MutableStateFlow(false)
        every { serverOrchestrator.isLocalLinkOk } returns MutableStateFlow(true)

        // Mock LLMEngine flows
        every { llmEngine.loadingProgress } returns MutableStateFlow(0f)
        every { llmEngine.isModelLoaded } returns MutableStateFlow(false)

        every { chatDao.getAllMessages() } returns flowOf(emptyList())
        every { deviceDao.getAllDevices() } returns flowOf(emptyList())
        every { appLogManager.logs } returns MutableStateFlow<List<LogEntry>>(emptyList())

        // Mock IngestionManager flows
        every { ingestionManager.isIngesting } returns MutableStateFlow<Boolean>(false)
        every { ingestionManager.ingestionProgress } returns MutableStateFlow<Float>(0f)
        every { ingestionManager.ingestionMessage } returns MutableStateFlow<String>("")
        every { ingestionManager.ingestionError } returns MutableStateFlow<String?>(null)
        every { ingestionManager.uploadedFiles } returns MutableStateFlow<List<com.chhanda.ai.data.repository.UploadedFileEntity>>(emptyList())
        every { ingestionManager.pendingBackgroundPrompt } returns MutableStateFlow<com.chhanda.ai.domain.service.IngestionTask?>(null)

        every { chhandaServer.port } returns 8888
        
        val llmEngineLazy = mockk<dagger.Lazy<LLMEngine>>()
        every { llmEngineLazy.get() } returns llmEngine
        
        val metricsManagerLazy = mockk<dagger.Lazy<RAGMetricsManager>>()
        every { metricsManagerLazy.get() } returns metricsManager

        viewModel = SystemViewModel(
            context = context,
            settingsRepository = settingsRepository,
            llmEngineLazy = llmEngineLazy,
            chatDao = chatDao,
            deviceDao = deviceDao,
            metricsManagerLazy = metricsManagerLazy,
            thermalStatusTracker = thermalStatusTracker,
            hardwareMonitor = hardwareMonitor,
            networkManager = networkManager,
            serverOrchestrator = serverOrchestrator,
            modelProvisioner = modelProvisioner,
            securityRepository = securityRepository,
            appLogManager = appLogManager,
            ingestionManager = ingestionManager,
            chhandaServer = chhandaServer,
            cloudSyncManager = cloudSyncManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(androidx.work.WorkManager::class)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `downloadModel should enqueue request and track progress`() = runTest {
        val model = DownloadModelInfo("Gemma-4-E2B-IT", "Test", "2.8 GB")
        
        viewModel.downloadModel(model)
        testScheduler.advanceUntilIdle()
        
        // Verify delegation to modelProvisioner
        verify { modelProvisioner.startDownload(model) }
    }
}
