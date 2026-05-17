package com.chhanda.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chhanda.ai.data.inference.LiteRTLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.util.MemoryPressureMonitor
import com.chhanda.ai.util.ThermalStatusTracker
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineInferenceTest {

    @Test
    fun testLocalModelInference() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val thermalTracker = ThermalStatusTracker(appContext)
        val memoryMonitor = MemoryPressureMonitor(appContext)
        
        val settingsRepo = com.chhanda.ai.data.repository.SettingsRepository(appContext)
        
        val engine = LiteRTLMEngine(appContext, thermalTracker, memoryMonitor, settingsRepo)
        
        val modelFile = File(appContext.getExternalFilesDir(null), "models/Gemma-4-E2B-IT.litertlm")
        assertTrue("Model file should exist at ${modelFile.absolutePath}", modelFile.exists())
        
        android.util.Log.i("EngineInferenceTest", "Initializing model: ${modelFile.absolutePath}")
        engine.initModel(modelFile.absolutePath)
        
        android.util.Log.i("EngineInferenceTest", "Model initialized. Starting inference for 'Hi'...")
        
        var fullResponse = ""
        engine.generateResponse(
            prompt = "Hi",
            history = emptyList(),
            systemInstruction = null,
            attachments = emptyList()
        ).collect { update ->
            when (update) {
                is TokenUpdate.Partial -> {
                    fullResponse += update.text
                    android.util.Log.i("EngineInferenceTest", "Token: ${update.text}")
                }
                is TokenUpdate.Final -> {
                    android.util.Log.i("EngineInferenceTest", "Final full text: ${update.fullText}")
                }
                is TokenUpdate.Status -> {
                    android.util.Log.i("EngineInferenceTest", "Status: ${update.message}")
                }
                is TokenUpdate.Error -> {
                    android.util.Log.e("EngineInferenceTest", "Error: ${update.message}")
                }
            }
        }
        
        android.util.Log.i("EngineInferenceTest", "Completed. Response was: '$fullResponse'")
        engine.close()
    }
}
