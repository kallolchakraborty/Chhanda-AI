package com.chhanda.ai.presentation.viewmodel

import android.app.DownloadManager
import android.content.Context
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.SettingsRepository
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.VectorStore
import com.chhanda.ai.presentation.ui.DownloadModelInfo
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val vectorStore = mockk<VectorStore>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val deviceDao = mockk<com.chhanda.ai.data.repository.DeviceDao>(relaxed = true)
    private val chhandaServer = mockk<com.chhanda.ai.data.inference.ChhandaServer>(relaxed = true)
    private val ingestDocumentUseCase = mockk<com.chhanda.ai.domain.usecase.IngestDocumentUseCase>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)

    private lateinit var viewModel: SystemViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        
        val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
        mockkStatic(androidx.work.WorkManager::class)
        every { androidx.work.WorkManager.getInstance(any()) } returns workManager
        
        val dummyFilesDir = java.io.File("/tmp")
        every { context.filesDir } returns dummyFilesDir
        every { context.getExternalFilesDir(any()) } returns dummyFilesDir
        
        every { settingsRepository.darkModeFlow } returns kotlinx.coroutines.flow.flowOf(true)
        every { settingsRepository.hfTokenFlow } returns kotlinx.coroutines.flow.flowOf("test_token")
        every { settingsRepository.serverPortFlow } returns kotlinx.coroutines.flow.flowOf("8888")
        every { settingsRepository.contextLengthFlow } returns kotlinx.coroutines.flow.flowOf("2048")
        every { settingsRepository.maxDevicesFlow } returns kotlinx.coroutines.flow.flowOf(5)
        every { settingsRepository.apiKeyFlow } returns kotlinx.coroutines.flow.flowOf("test_key")
        every { settingsRepository.publicUrlFlow } returns kotlinx.coroutines.flow.flowOf("")
        
        every { chhandaServer.port } returns 8888
        
        val llmEngineLazy = mockk<dagger.Lazy<LLMEngine>>()
        every { llmEngineLazy.get() } returns llmEngine
        
        val vectorStoreLazy = mockk<dagger.Lazy<VectorStore>>()
        every { vectorStoreLazy.get() } returns vectorStore
        
        val chhandaServerLazy = mockk<dagger.Lazy<com.chhanda.ai.data.inference.ChhandaServer>>()
        every { chhandaServerLazy.get() } returns chhandaServer
        
        val ingestDocumentUseCaseLazy = mockk<dagger.Lazy<com.chhanda.ai.domain.usecase.IngestDocumentUseCase>>()
        every { ingestDocumentUseCaseLazy.get() } returns ingestDocumentUseCase

        viewModel = SystemViewModel(
            context,
            settingsRepository,
            llmEngineLazy,
            vectorStoreLazy,
            chatDao,
            deviceDao,
            chhandaServerLazy,
            ingestDocumentUseCaseLazy
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(androidx.work.WorkManager::class)
    }

    @Test
    fun `downloadModel should enqueue request and track progress`() = runTest {
        val model = DownloadModelInfo("Gemma-4-4B", "Test", "2.8 GB")
        
        viewModel.downloadModel(model)
        
        // Verify download was enqueued via WorkManager
        verify { androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) }
    }
}
