package com.chhanda.ai.data.inference

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.DeviceDao
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.domain.usecase.SendMessageUseCase
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.http.content.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import java.net.NetworkInterface
import java.net.Socket
import java.net.ServerSocket
import androidx.work.*

@Serializable
data class WebAttachment(val name: String, val type: String, val data: String)

@Serializable
data class WebMessage(
    val text: String, 
    val role: String = "user", 
    val attachments: List<WebAttachment> = emptyList(), 
    val language: String = "en", 
    val sessionId: String? = null, 
    val isRefinement: Boolean = false,
    val persona: String? = null
)
@Serializable
data class RegisterRequest(val name: String)

@Serializable
data class OpenAiFunction(val name: String, val description: String? = null, val parameters: JsonObject? = null)

@Serializable
data class OpenAiTool(val type: String, val function: OpenAiFunction)

@Serializable
data class OpenAiToolCallFunction(val name: String, val arguments: String)

@Serializable
data class OpenAiToolCall(val id: String, val type: String, val function: OpenAiToolCallFunction)

@Serializable
data class OpenAiMessage(
    val role: String, 
    val content: String? = null, 
    val tool_calls: List<OpenAiToolCall>? = null,
    val tool_call_id: String? = null
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    val temperature: Float? = null,
    val stream: Boolean = false
)

@Serializable
data class OpenAiCompletionRequest(
    val model: String,
    val prompt: String,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val stream: Boolean = false
)

@Serializable
data class ToolCallOutput(val name: String, val arguments: JsonElement)

@Singleton
class ChhandaServer @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val chatDao: ChatDao,
    private val deviceDao: DeviceDao,
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository,
    private val sendMessageUseCaseLazy: dagger.Lazy<SendMessageUseCase>,
    private val contextManagerLazy: dagger.Lazy<com.chhanda.ai.domain.model.ContextManager>,
    private val vectorChunkDao: com.chhanda.ai.data.repository.VectorChunkDao,
    private val templateProvider: ServerTemplateProvider,
    private val thermalStatusTracker: com.chhanda.ai.util.ThermalStatusTracker,
    private val securityRepository: com.chhanda.ai.data.repository.SecurityRepository,
    private val sslCertManager: com.chhanda.ai.util.SslCertManager,
    private val responseProcessorLazy: dagger.Lazy<com.chhanda.ai.domain.service.ResponseProcessor>
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val sendMessageUseCase get() = sendMessageUseCaseLazy.get()
    private val contextManager get() = contextManagerLazy.get()
    private val responseProcessor get() = responseProcessorLazy.get()
    @Volatile private var server: CIOApplicationEngine? = null
    @Volatile private var boundPort: Int = -1
    private var reaperJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startTime = System.currentTimeMillis()

    companion object { private const val TAG = "ChhandaServer" }

    private val requestSemaphore = kotlinx.coroutines.sync.Semaphore(16)
    private val clientRequestWindow = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val RATE_LIMIT_MS = 1000L

    @Volatile private var cachedIp: String = "127.0.0.1"
    @Volatile private var cachedAllIps: List<String> = emptyList()
    @Volatile private var cachedVpnStatus: Boolean = false
    @Volatile private var cachedPublicUrl: String = ""
    @Volatile private var tunnelActive: Boolean = false
    @Volatile private var configuredMaxDevices: Int = 10
    @Volatile private var lastIpRefresh = 0L

    private val _boundPortFlow = MutableStateFlow(-1)
    val boundPortFlow: StateFlow<Int> = _boundPortFlow.asStateFlow()

    private val _serverErrorFlow = MutableStateFlow<String?>(null)
    val serverErrorFlow: StateFlow<String?> = _serverErrorFlow.asStateFlow()

    @Volatile var activeQrToken: String? = null
    val authorizedQrIps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun generateNewQrToken(): String {
        val token = java.util.UUID.randomUUID().toString().substring(0, 8)
        activeQrToken = token
        return token
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { true }
        } catch (_: Exception) { false }
    }

    fun isServerActive(): Boolean = server != null

    private fun logAudit(tag: String, message: String, level: String) {
        android.util.Log.i("ChhandaAudit", "[$tag] ($level) $message")
    }

    private var tunnelSession: com.jcraft.jsch.Session? = null
    private val IP_TTL_MS = 10_000L

    val boundIp: String get() = cachedIp
    val allIps: List<String> get() = cachedAllIps
    val port: Int get() = boundPort
    val isVpnActive: Boolean get() = cachedVpnStatus
    val publicUrl: String get() = cachedPublicUrl
    val isTunnelActive: Boolean get() = tunnelActive

    fun freshIp(): String {
        val now = System.currentTimeMillis()
        if (now - lastIpRefresh > IP_TTL_MS) {
            refreshNetworkStatus()
        }
        return cachedIp
    }

    fun refreshNetworkStatus() {
        val now = System.currentTimeMillis()
        lastIpRefresh = now
        val (bestIp, all, vpn) = scanNetworkInterfaces()
        cachedIp = bestIp ?: "127.0.0.1"
        cachedAllIps = all
        cachedVpnStatus = vpn
    }

    private fun scanNetworkInterfaces(): Triple<String?, List<String>, Boolean> {
        val ips = mutableListOf<Pair<String, String>>()
        var vpnFound = false
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces?.hasMoreElements() == true) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.contains("tun") || name.contains("ppp") || name.contains("vpn") || name.contains("ipsec")) vpnFound = true
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) ips.add(name to addr.hostAddress!!)
                }
            }
        } catch (_: Exception) {}
        val sortedIps = ips.sortedWith(compareBy { (name, ip) ->
            when {
                name.contains("ap0") || name.contains("softap") || name.contains("wlan1") || name.contains("swlan") -> 0
                ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.") || ip.startsWith("192.168.45.") -> 1
                name.startsWith("wlan0") -> 2
                name.startsWith("eth") -> 3
                ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.") -> 4
                else -> 5
            }
        }).map { it.second }.distinct()
        return Triple(sortedIps.firstOrNull(), sortedIps, vpnFound)
    }

    fun start(requestedPort: Int, maxDevices: Int) {
        stop()
        cleanupTempFiles()
        startTime = System.currentTimeMillis()
        configuredMaxDevices = maxDevices
        lastIpRefresh = 0L
        val ip = freshIp()
        _serverErrorFlow.value = null
        for (port in requestedPort..requestedPort + 10) {
            try {
                // val sslPort = port + 1 // HTTPS disabled due to CIO engine limitations on Android
                
                // PRO FIX: Verify port is free before attempting bind
                if (!isPortFree(port)) {
                    Log.w(TAG, "Port $port is occupied, trying next...")
                    continue
                }


                
                val environment = io.ktor.server.engine.applicationEngineEnvironment {
                    log = org.slf4j.LoggerFactory.getLogger("ktor.application")
                    
                    connector {
                        this.host = "0.0.0.0"
                        this.port = port
                    }
                    
                    module {
                        configureEngine(this, port)
                    }
                }

                val engine = embeddedServer(CIO, environment)
                engine.start(wait = false)
                server = engine
                boundPort = port
                _boundPortFlow.value = port
                startReaper()
                Log.i(TAG, "Server started: HTTP on $port")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Port binding failed at $port: ${e.message}")
            }
        }
        _serverErrorFlow.value = "CRITICAL: No ports available for binding."
    }

    private fun startReaper() {
        reaperJob?.cancel()
        reaperJob = serverScope.launch {
            while(isActive) {
                delay(10000)
                try {
                    val cutoff = System.currentTimeMillis() - 30000
                    val active = deviceDao.getActiveConnections()
                    active.forEach { device ->
                        if (device.lastActive < cutoff) deviceDao.updateDeviceStatus(device.deviceName, false, device.lastActive)
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun stop() {
        val wasRunning = server != null
        reaperJob?.cancel()
        reaperJob = null
        stopTunnel()
        server?.stop(100, 300)
        server = null
        boundPort = -1
        _boundPortFlow.value = -1
        
        // Unload the engine to free memory and clear dashboard status
        if (wasRunning) {
            serverScope.launch {
                try { llmEngine.close() } catch (e: Exception) { Log.w(TAG, "Engine close failed: ${e.message}") }
            }
        }
    }

    fun startTunnel() {
        if (boundPort == -1) return
        stopTunnel()
        Thread {
            try {
                val jsch = com.jcraft.jsch.JSch()
                val session = jsch.getSession("nokey", "localhost.run", 22)
                val config = java.util.Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)
                session.connect(20000)
                session.setPortForwardingR(80, "127.0.0.1", boundPort)
                val channel = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
                val inputStream = channel.inputStream
                channel.connect()
                val reader = inputStream.bufferedReader()
                for (i in 0..100) {
                    val line = reader.readLine() ?: break
                    if (line.contains("lhr.life") || line.contains("localhost.run")) {
                        val regex = """https://[^\s]+""".toRegex()
                        val match = regex.find(line)
                        if (match != null) {
                            cachedPublicUrl = match.value
                            tunnelActive = true
                            break
                        }
                    }
                }
                tunnelSession = session
                while(session.isConnected) { Thread.sleep(5000) }
            } catch (e: Exception) {
                tunnelActive = false
                cachedPublicUrl = ""
            }
        }.apply { name = "TunnelThread"; isDaemon = true }.start()
    }

    fun stopTunnel() {
        try { tunnelSession?.disconnect() } catch (_: Exception) {}
        tunnelSession = null
        tunnelActive = false
        cachedPublicUrl = ""
    }

    private fun configureEngine(app: io.ktor.server.application.Application, capturedPort: Int) {
        app.apply {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }) }
            install(CORS) {
                anyHost() // Use anyHost() and rely on X-API-KEY for security
                allowHeader("X-API-KEY")
                allowHeader("Authorization")
                allowHeader("Content-Type")
                allowMethod(io.ktor.http.HttpMethod.Options)
                allowMethod(io.ktor.http.HttpMethod.Get)
                allowMethod(io.ktor.http.HttpMethod.Post)
            }
            routing {
                get("/status") {
                    val loaded = llmEngine.isModelLoaded.value
                    val uptime = (System.currentTimeMillis() - startTime) / 1000
                    val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true
                    call.respondText("""{"ok":true,"modelLoaded":$loaded,"uptime":$uptime,"thinkingMode":$thinkingMode}""", io.ktor.http.ContentType.Application.Json)
                }
                get("/ping") { call.respondText("pong") }

                val isLocalRequest: (String) -> Boolean = { host ->
                    host == "127.0.0.1" || host == "0:0:0:0:0:0:0:1" || host == "::1" || host.equals("localhost", ignoreCase = true) ||
                    host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
                }

                val validateAuth: suspend (io.ktor.server.application.ApplicationCall) -> Boolean = validateAuth@{ call ->
                    val remoteHost = call.request.local.remoteHost
                    
                    // Allow local loopback always, but restrict subnet if tunnel is inactive
                    if (!tunnelActive && !isLocalRequest(remoteHost)) {
                        call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Access Restricted: Non-local requests blocked without active tunnel."))
                        return@validateAuth false
                    }

                    val authHeader = call.request.headers["Authorization"]
                    val bearerKey = if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                        authHeader.substring(7).trim()
                    } else null

                    val providedKey = bearerKey ?: call.request.headers["X-API-KEY"] ?: call.request.queryParameters["key"]
                    val actualKey = securityRepository.apiKey.value
                    
                    Log.d(TAG, "API Gateway auth attempt: provided='$providedKey', actual='$actualKey'")
                    
                    val isLoopback: (String) -> Boolean = { host ->
                        host == "127.0.0.1" || host == "0:0:0:0:0:0:0:1" || host == "::1" || host.equals("localhost", ignoreCase = true)
                    }

                    if (actualKey.isBlank() || actualKey == "Initializing..." || actualKey == "000000000" || providedKey != actualKey) {
                        Log.w(TAG, "API Gateway auth failed. Provided: '$providedKey', Actual: '$actualKey'")
                        call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized: Invalid or Uninitialized API Key"))
                        false
                    } else {
                        // Secure remote verification: remote hosts (not loopback) must have scanned the QR code
                        // However, exempt standard programmatic API endpoints (/v1/*) where clients supply the API key directly.
                        val isApiEndpoint = call.request.path().startsWith("/v1/")
                        if (!isApiEndpoint && !isLoopback(remoteHost) && !authorizedQrIps.contains(remoteHost)) {
                            call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Forbidden: Device not authorized via QR code scan."))
                            false
                        } else true
                    }
                }

                post("/register") {
                    if (!validateAuth(call)) return@post
                    try {
                        val req = call.receive<RegisterRequest>()
                        val ip = call.request.local.remoteHost
                        
                        val activeDevices = deviceDao.getActiveConnections().filter { it.connectionType == "SHARED" }
                        val alreadyConnected = activeDevices.any { it.deviceName == req.name || it.ipAddress == ip }
                        if (activeDevices.size >= configuredMaxDevices && !alreadyConnected) {
                            call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Max device limit reached"))
                            return@post
                        }

                        val existing = deviceDao.getAllDevices().firstOrNull()?.find { it.deviceName == req.name }
                        if (existing != null) {
                            deviceDao.updateDevice(existing.copy(
                                ipAddress = ip,
                                isCurrentlyConnected = true,
                                lastActive = System.currentTimeMillis()
                            ))
                        } else {
                            deviceDao.insertDevice(com.chhanda.ai.data.repository.DeviceEntity(
                                deviceName = req.name,
                                ipAddress = ip,
                                connectionTime = System.currentTimeMillis(),
                                isCurrentlyConnected = true,
                                connectionType = "SHARED",
                                lastActive = System.currentTimeMillis()
                            ))
                        }
                        call.respondText("""{"ok":true}""", io.ktor.http.ContentType.Application.Json)
                    } catch(e: Exception) {
                        call.respondText("""{"ok":false}""", io.ktor.http.ContentType.Application.Json)
                    }
                }

                post("/v1/chat/completions") {
                    if (!validateAuth(call)) return@post
                    val remoteIp = call.request.local.remoteHost
                    val now = System.currentTimeMillis()
                    val lastRequest = clientRequestWindow[remoteIp] ?: 0L
                    if (now - lastRequest < RATE_LIMIT_MS) {
                        call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                        return@post
                    }
                    clientRequestWindow[remoteIp] = now
                    
                    // Wake on Demand: Ensure model is loaded
                    if (!llmEngine.isModelLoaded.value && !llmEngine.isModelLoading.value) {
                        val lastModel = settingsRepository.activeModelFlow.firstOrNull()
                        if (lastModel != null) {
                            val modelDir = java.io.File(this@ChhandaServer.context.getExternalFilesDir(null), "models")
                            val modelFile = java.io.File(modelDir, lastModel)
                            if (modelFile.exists()) {
                                Log.i(TAG, "Wake on Demand: Auto-loading model ${modelFile.absolutePath}")
                                try { llmEngine.initModel(modelFile.absolutePath) } catch (e: Exception) { Log.e(TAG, "Wake on Demand failed", e) }
                            } else {
                                Log.w(TAG, "Wake on Demand: Selected model file does not exist: ${modelFile.absolutePath}")
                            }
                        }
                    }

                    try {
                        val req = call.receive<OpenAiChatRequest>()
                        val lastUserMsg = req.messages.lastOrNull { it.role == "user" }?.content ?: ""
                        val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true
                        
                        // Extract previous conversation context (all messages except the last one)
                        val externalHistory = req.messages.dropLast(1).map { msg ->
                            Pair(msg.role, msg.content ?: "")
                        }

                        // System-prompt instruction injecting available tools to act as a robust coding agent
                        val toolsInstructions = if (req.tools != null && req.tools.isNotEmpty()) {
                            buildString {
                                append("\n\nAVAILABLE TOOLS:\n")
                                append("You can use the following tools to assist the user by generating a tool call:\n")
                                req.tools.forEach { tool ->
                                    append("- Function: ${tool.function.name}\n")
                                    append("  Description: ${tool.function.description ?: ""}\n")
                                    if (tool.function.parameters != null) {
                                        append("  Parameters: ${tool.function.parameters}\n")
                                    }
                                    append("\n")
                                }
                                append("To call a tool, you MUST output a single <tool_call> tag containing a JSON object with 'name' and 'arguments' properties. Do not output anything else. Example:\n")
                                append("<tool_call>{\"name\": \"create_new_file\", \"arguments\": {\"path\": \"example.py\", \"content\": \"print('hello')\"}}</tool_call>\n")
                            }
                        } else ""

                        if (req.stream) {
                            call.respondTextWriter(io.ktor.http.ContentType.Text.EventStream) {
                                requestSemaphore.withPermit {
                                    try {
                                        val currentModel = llmEngine.getCurrentModelName()
                                        val msgId = "chatcmpl-${System.currentTimeMillis()}"
                                        var buffer = ""
                                        var insideToolCall = false
                                        sendMessageUseCase(
                                            userText = lastUserMsg + toolsInstructions,
                                            deviceId = call.request.local.remoteHost,
                                            modelName = currentModel,
                                            sessionId = "openai_session",
                                            source = "api",
                                            includeThinking = false,
                                            externalHistory = externalHistory
                                        ).collect { upd ->
                                            if (upd is TokenUpdate.Partial) {
                                                buffer += upd.text
                                                if (buffer.contains("<tool_call>")) {
                                                    insideToolCall = true
                                                    val parts = buffer.split("<tool_call>", limit = 2)
                                                    val before = parts[0]
                                                    if (before.isNotEmpty()) {
                                                        val chunk = """{"id":"$msgId","object":"chat.completion.chunk","created":${System.currentTimeMillis()/1000},"model":"$currentModel","choices":[{"index":0,"delta":{"content":"${before.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}"},"finish_reason":null}]}"""
                                                        write("data: $chunk\n\n"); flush()
                                                    }
                                                    buffer = "<tool_call>" + (parts.getOrNull(1) ?: "")
                                                }
                                                
                                                if (insideToolCall) {
                                                    if (buffer.contains("</tool_call>")) {
                                                        val match = """<tool_call>([\s\S]*?)</tool_call>""".toRegex().find(buffer)
                                                        val jsonStr = match?.groupValues?.get(1)?.trim() ?: ""
                                                        val jsonObj = try {
                                                            kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
                                                        } catch (e: Exception) {
                                                            null
                                                        }
                                                        val toolCallName = jsonObj?.get("name")?.jsonPrimitive?.content ?: ""
                                                        val toolCallArgs = jsonObj?.get("arguments")?.toString() ?: "{}"
                                                        val toolCallId = "call_" + java.util.UUID.randomUUID().toString().take(8)
                                                        
                                                        if (toolCallName.isNotEmpty()) {
                                                            val chunk = """{"id":"$msgId","object":"chat.completion.chunk","created":${System.currentTimeMillis()/1000},"model":"$currentModel","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"$toolCallId","type":"function","function":{"name":"$toolCallName","arguments":"${toolCallArgs.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}"}}]},"finish_reason":"tool_calls"}]}"""
                                                            write("data: $chunk\n\n"); flush()
                                                        }
                                                        insideToolCall = false
                                                        buffer = buffer.substringAfter("</tool_call>")
                                                    }
                                                } else {
                                                    val chunk = """{"id":"$msgId","object":"chat.completion.chunk","created":${System.currentTimeMillis()/1000},"model":"$currentModel","choices":[{"index":0,"delta":{"content":"${upd.text.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}"},"finish_reason":null}]}"""
                                                    write("data: $chunk\n\n"); flush()
                                                    buffer = ""
                                                }
                                            } else if (upd is TokenUpdate.Final) {
                                                write("data: [DONE]\n\n"); flush()
                                            }
                                        }
                                    } catch (e: Exception) { write("data: [DONE]\n\n"); flush() }
                                }
                            }
                        } else {
                            var fullResponse = ""
                            requestSemaphore.withPermit {
                                try {
                                    sendMessageUseCase(
                                        userText = lastUserMsg + toolsInstructions,
                                        deviceId = call.request.local.remoteHost,
                                        modelName = llmEngine.getCurrentModelName(),
                                        sessionId = "openai_session",
                                        source = "api",
                                        includeThinking = false,
                                        externalHistory = externalHistory
                                    ).collect { upd ->
                                        if (upd is TokenUpdate.Partial) fullResponse += upd.text
                                    }
                                    
                                    val toolCallRegex = """<tool_call>([\s\S]*?)</tool_call>""".toRegex()
                                    val match = toolCallRegex.find(fullResponse)
                                    val jsonStr = match?.groupValues?.get(1)?.trim()
                                    val jsonObj = if (jsonStr != null) {
                                        try {
                                            kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
                                        } catch (e: Exception) {
                                            null
                                        }
                                    } else null
                                    
                                    val toolCallName = jsonObj?.get("name")?.jsonPrimitive?.content ?: ""
                                    val toolCallArgs = jsonObj?.get("arguments")?.toString() ?: "{}"
                                    val toolCallId = "call_" + java.util.UUID.randomUUID().toString().take(8)
                                    
                                    if (toolCallName.isNotEmpty()) {
                                        val escapedArgs = toolCallArgs
                                            .replace("\\", "\\\\")
                                            .replace("\"", "\\\"")
                                            .replace("\n", "\\n")
                                            .replace("\r", "\\r")
                                            .replace("\t", "\\t")
                                        val responseJson = """
                                            {
                                              "id": "chatcmpl-${System.currentTimeMillis()}",
                                              "choices": [
                                                {
                                                  "message": {
                                                    "role": "assistant",
                                                    "content": null,
                                                    "tool_calls": [
                                                      {
                                                        "id": "$toolCallId",
                                                        "type": "function",
                                                        "function": {
                                                          "name": "$toolCallName",
                                                          "arguments": "$escapedArgs"
                                                        }
                                                      }
                                                    ]
                                                  },
                                                  "finish_reason": "tool_calls"
                                                }
                                              ]
                                            }
                                        """.trimIndent()
                                        call.respondText(responseJson, io.ktor.http.ContentType.Application.Json)
                                    } else {
                                        val cleanedContent = responseProcessor.cleanFinalResponse(fullResponse).text
                                        val jsonEscapedContent = cleanedContent
                                            .replace("\\", "\\\\")
                                            .replace("\"", "\\\"")
                                            .replace("\n", "\\n")
                                            .replace("\r", "\\r")
                                            .replace("\t", "\\t")
                                        val responseJson = """
                                            {
                                              "id": "chatcmpl-${System.currentTimeMillis()}",
                                              "choices": [
                                                {
                                                  "message": {
                                                    "role": "assistant",
                                                    "content": "$jsonEscapedContent"
                                                  },
                                                  "finish_reason": "stop"
                                                }
                                              ]
                                            }
                                        """.trimIndent()
                                        call.respondText(responseJson, io.ktor.http.ContentType.Application.Json)
                                    }
                                } catch (e: Exception) {
                                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error")))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error")))
                    }
                }

                post("/chat") {
                    if (!validateAuth(call)) return@post
                    val remoteIp = call.request.local.remoteHost

                    // Receive message body first to extract sessionId for precise, session-isolated rate-limiting
                    val msg = call.receive<WebMessage>()
                    val sessionKey = remoteIp + "_" + (msg.sessionId ?: "default")

                    val now = System.currentTimeMillis()
                    val lastRequest = clientRequestWindow[sessionKey] ?: 0L
                    if (now - lastRequest < RATE_LIMIT_MS) {
                        call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                        return@post
                    }
                    clientRequestWindow[sessionKey] = now

                    // Wake on Demand: Ensure model is loaded
                    if (!llmEngine.isModelLoaded.value && !llmEngine.isModelLoading.value) {
                        val lastModel = settingsRepository.activeModelFlow.firstOrNull()
                        if (lastModel != null) {
                            val modelDir = java.io.File(this@ChhandaServer.context.getExternalFilesDir(null), "models")
                            val modelFile = java.io.File(modelDir, lastModel)
                            if (modelFile.exists()) {
                                Log.i(TAG, "Wake on Demand: Auto-loading model ${modelFile.absolutePath}")
                                try { llmEngine.initModel(modelFile.absolutePath) } catch (e: Exception) { Log.e(TAG, "Wake on Demand failed", e) }
                            } else {
                                Log.w(TAG, "Wake on Demand: Selected model file does not exist: ${modelFile.absolutePath}")
                            }
                        }
                    }

                    val attachmentFiles = mutableListOf<java.io.File>()
                    val attachmentUris = msg.attachments.mapNotNull { webAtt ->
                        try {
                            val cleanBase64 = webAtt.data.substringAfter("base64,")
                            val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                            val file = java.io.File(this@ChhandaServer.context.cacheDir, "web_upload_${System.currentTimeMillis()}_${webAtt.name}")
                            file.writeBytes(bytes)
                            attachmentFiles.add(file)
                            android.net.Uri.fromFile(file)
                        } catch(e: Exception) { null }
                    }

                    call.respondTextWriter(io.ktor.http.ContentType.Text.EventStream) {
                        requestSemaphore.withPermit {
                            try {
                                val devName = try { deviceDao.getDeviceByIp(remoteIp)?.deviceName } catch(e: Exception) { null } ?: remoteIp
                                val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true
                                sendMessageUseCase(
                                    userText = msg.text, 
                                    deviceId = devName, 
                                    modelName = llmEngine.getCurrentModelName(), 
                                    sessionId = msg.sessionId ?: "api_session", 
                                    attachments = attachmentUris, 
                                    persona = msg.persona,
                                    includeThinking = thinkingMode,
                                    source = "qr"
                                ).collect { upd ->
                                    if (upd is TokenUpdate.Partial) {
                                        write("data: ${upd.text.replace("\n", "\\n")}\n\n"); flush()
                                    } else if (upd is TokenUpdate.Final) {
                                        write("data: [DONE]\n\n"); flush()
                                    }
                                }
                            } catch (e: Exception) { 
                                write("data: ERR:${e.message}\n\n"); flush() 
                            } finally {

                                attachmentFiles.forEach { it.delete() }
                            }
                        }
                    }
                }

                get("/") {
                    val remoteHost = call.request.local.remoteHost
                    val scanToken = call.request.queryParameters["scan_token"]

                    val isLoopback: (String) -> Boolean = { host ->
                        host == "127.0.0.1" || host == "0:0:0:0:0:0:0:1" || host == "::1" || host.equals("localhost", ignoreCase = true)
                    }

                    if (!isLoopback(remoteHost)) {
                        if (scanToken != null && activeQrToken != null && scanToken == activeQrToken) {
                            // Perfect! Authorize this remote host IP address
                            authorizedQrIps.add(remoteHost)
                            // Consume the token immediately to prevent any other device from using it
                            activeQrToken = null
                        } else if (!authorizedQrIps.contains(remoteHost)) {
                            // Not authorized via QR scan! Reject access!
                            call.respondText(templateProvider.buildAccessDeniedHtml(), io.ktor.http.ContentType.Text.Html)
                            return@get
                        }
                    }

                    val activeDevices = deviceDao.getActiveConnections().filter { it.connectionType == "SHARED" }
                    val alreadyConnected = activeDevices.any { it.ipAddress == remoteHost }
                    if (activeDevices.size >= configuredMaxDevices && !alreadyConnected) {
                        call.respondText(templateProvider.buildMaxLimitReachedHtml(configuredMaxDevices), io.ktor.http.ContentType.Text.Html)
                        return@get
                    }
                    val sessions = try { chatDao.getSessionIdsForDevice(remoteHost).firstOrNull() ?: emptyList() } catch(e: Exception) { emptyList() }
                    call.respondText(templateProvider.buildChatHtml(capturedPort, call.request.host(), emptyList(), false, "", sessions), io.ktor.http.ContentType.Text.Html)
                }

                get("/session/{id}") {
                    if (!validateAuth(call)) return@get
                    val sessionId = call.parameters["id"] ?: ""
                    val messages = try {
                        chatDao.getMessagesForSession(sessionId).firstOrNull() ?: emptyList()
                    } catch(e: Exception) {
                        emptyList()
                    }
                    val jsonArray = messages.map { msg ->
                        """{
                            "role": "${msg.role}",
                            "text": "${msg.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}",
                            "timestamp": ${msg.timestamp}
                        }"""
                    }.joinToString(",", "[", "]")
                    call.respondText(jsonArray, io.ktor.http.ContentType.Application.Json)
                }

                post("/disconnect") {
                    if (!validateAuth(call)) return@post
                    try {
                        val ip = call.request.local.remoteHost
                        authorizedQrIps.remove(ip)
                        val active = deviceDao.getActiveConnections().filter { it.ipAddress == ip && it.isCurrentlyConnected }
                        active.forEach { device ->
                            val now = System.currentTimeMillis()
                            val duration = now - device.connectionTime
                            deviceDao.updateDevice(device.copy(
                                isCurrentlyConnected = false,
                                disconnectionTime = now,
                                durationMs = duration
                            ))
                        }
                        call.respondText("""{"ok":true}""", io.ktor.http.ContentType.Application.Json)
                    } catch(e: Exception) {
                        call.respondText("""{"ok":false}""", io.ktor.http.ContentType.Application.Json)
                    }
                }
            }
        }
    }

    private fun heartbeatDevice(ip: String) {
        serverScope.launch { 
            try { 
                deviceDao.updateDeviceStatus(deviceDao.getDeviceByIp(ip)?.deviceName ?: return@launch, true, System.currentTimeMillis()) 
            } catch (_: Exception) {} 
        }
    }

    private fun cleanupTempFiles() {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles { _, name -> name.startsWith("web_upload_") }?.forEach { 
                if (it.delete()) Log.d(TAG, "Cleaned orphaned temp file: ${it.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Temp cleanup failed: ${e.message}")
        }
    }
}
