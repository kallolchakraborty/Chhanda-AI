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
        if (text.contains("[CREATE_FILE")) {
            try {
                val regex = """\[CREATE_FILE\s+path="([^"]+)"\]([\s\S]*?)\[/CREATE_FILE\]""".toRegex()
                val matches = regex.findAll(text)
                for (match in matches) {
                    val pathStr = match.groupValues[1]
                    val content = match.groupValues[2].trim()
                    
                    val file = if (pathStr.startsWith("/")) {
                        java.io.File(pathStr)
                    } else {
                        val baseDir = java.io.File(context.getExternalFilesDir(null), "generated_files")
                        if (!baseDir.exists()) baseDir.mkdirs()
                        java.io.File(baseDir, pathStr)
                    }
                    
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    filePath = file.absolutePath
                    Log.i("AgenticActionHandler", "Created code file at path: $filePath")
                }
            } catch (e: Exception) {
                Log.e("AgenticActionHandler", "Code file creation failed: ${e.message}")
            }
        }
        return ActionResult(filePath)
    }

    data class ActionResult(val generatedFilePath: String?)
}
