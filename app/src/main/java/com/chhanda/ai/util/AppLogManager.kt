package com.chhanda.ai.util

import android.content.Context
import com.chhanda.ai.domain.model.LogEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    private var logSaveJob: Job? = null
    private val logFile = File(context.filesDir, "app_logs.json")

    init {
        _logs.value = loadLogsFromFile()
    }

    fun addLog(tag: String, message: String, level: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            tag = tag,
            message = message,
            status = level
        )
        _logs.update { current ->
            (listOf(entry) + current).take(100)
        }
        
        logSaveJob?.cancel()
        logSaveJob = scope.launch {
            delay(2000)
            saveLogsToFile(_logs.value)
        }
    }

    fun deleteLogs(logIds: List<String>) {
        _logs.update { it.filter { log -> log.id !in logIds } }
        addLog("SYSTEM", "Deleted ${logIds.size} logs", "INFO")
    }

    fun clearAllLogs() {
        _logs.value = emptyList()
        saveLogsToFile(emptyList())
    }

    private fun loadLogsFromFile(): List<LogEntry> {
        return try {
            if (logFile.exists()) {
                val json = logFile.readText()
                Json.decodeFromString<List<LogEntry>>(json)
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveLogsToFile(logs: List<LogEntry>) {
        try {
            val json = Json.encodeToString(logs)
            logFile.writeText(json)
        } catch (e: Exception) {
            // Log fallback
        }
    }
}
