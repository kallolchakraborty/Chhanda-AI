package com.chhanda.ai.domain.service

import android.content.Context
import android.util.Log
import com.chhanda.ai.util.DocumentGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles agentic tool calls embedded in LLM responses (e.g., file generation).
 */
@Singleton
class AgenticActionHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun handleActions(text: String): ActionResult {
        var filePath: String? = null
        if (text.contains("[GENERATE_FILE")) {
            try {
                val regex = """\[GENERATE_FILE\s+type="(\w+)"\s+name="([^"]+)"\]([\s\S]*?)\[/GENERATE_FILE\]""".toRegex()
                val match = regex.find(text)
                if (match != null) {
                    val type = match.groupValues[1].lowercase()
                    val name = match.groupValues[2]
                    val content = match.groupValues[3].trim()

                    val file = when(type) {
                        "excel" -> DocumentGenerator.generateExcel(context, name, content)
                        "word" -> DocumentGenerator.generateWord(context, name, content)
                        "pdf" -> DocumentGenerator.generatePdf(context, name, content)
                        else -> null
                    }
                    filePath = file?.absolutePath
                    Log.i("AgenticActionHandler", "Generated $type file: $filePath")
                }
            } catch (e: Exception) {
                Log.e("AgenticActionHandler", "File generation failed: ${e.message}")
            }
        }
        return ActionResult(filePath)
    }

    data class ActionResult(val generatedFilePath: String?)
}
