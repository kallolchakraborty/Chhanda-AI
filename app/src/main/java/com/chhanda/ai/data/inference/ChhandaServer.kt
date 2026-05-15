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
    private val thermalStatusTracker: com.chhanda.ai.util.ThermalStatusTracker
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val sendMessageUseCase get() = sendMessageUseCaseLazy.get()
    private val contextManager get() = contextManagerLazy.get()
    @Volatile private var server: CIOApplicationEngine? = null
    @Volatile private var boundPort: Int = -1
    private var reaperJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startTime = System.currentTimeMillis()

    companion object { private const val TAG = "ChhandaServer" }

    private val requestSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val clientRequestWindow = mutableMapOf<String, Long>()
    private val RATE_LIMIT_MS = 1000L

    @Volatile private var cachedIp: String = "127.0.0.1"
    @Volatile private var cachedAllIps: List<String> = emptyList()
    @Volatile private var cachedVpnStatus: Boolean = false
    @Volatile private var cachedPublicUrl: String = ""
    @Volatile private var tunnelActive: Boolean = false
    @Volatile private var lastIpRefresh = 0L

    private val _boundPortFlow = MutableStateFlow(-1)
    val boundPortFlow: StateFlow<Int> = _boundPortFlow.asStateFlow()

    private val _serverErrorFlow = MutableStateFlow<String?>(null)
    val serverErrorFlow: StateFlow<String?> = _serverErrorFlow.asStateFlow()

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

    fun start(requestedPort: Int, @Suppress("UNUSED_PARAMETER") maxDevices: Int) {
        stop()
        startTime = System.currentTimeMillis()
        lastIpRefresh = 0L
        val ip = freshIp()
        _serverErrorFlow.value = null
        for (port in requestedPort..requestedPort + 10) {
            try {
                val engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    configureEngine(this, port)
                }
                engine.start(wait = false)
                server = engine
                boundPort = port
                _boundPortFlow.value = port
                startReaper()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Port $port binding failed: ${e.message}")
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
        reaperJob?.cancel()
        reaperJob = null
        stopTunnel()
        server?.stop(100, 300)
        server = null
        boundPort = -1
        _boundPortFlow.value = -1
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
                        val regex = "https:
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
            install(CORS) { anyHost(); allowHeader("*"); allowMethod(io.ktor.http.HttpMethod.Options); allowMethod(io.ktor.http.HttpMethod.Get); allowMethod(io.ktor.http.HttpMethod.Post) }
            routing {
                get("/status") {
                    val loaded = llmEngine.isModelLoaded()
                    val uptime = (System.currentTimeMillis() - startTime) / 1000
                    val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true
                    call.respondText("""{"ok":true,"modelLoaded":$loaded,"uptime":$uptime,"thinkingMode":$thinkingMode}""", io.ktor.http.ContentType.Application.Json)
                }
                get("/ping") { call.respondText("pong") }

                val validateAuth: suspend (io.ktor.server.application.ApplicationCall) -> Boolean = { call ->
                    val providedKey = call.request.queryParameters["key"] ?: call.request.headers["X-API-KEY"]
                    val actualKey = settingsRepository.apiKeyFlow.firstOrNull() ?: "000000000"
                    if (providedKey != actualKey) {
                        call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized: Invalid API Key"))
                        false
                    } else true
                }

                post("/register") {
                    if (!validateAuth(call)) return@post
                    try {
                        val req = call.receive<RegisterRequest>()
                        val ip = call.request.local.remoteHost
                        deviceDao.updateDeviceStatus(req.name, true, System.currentTimeMillis()) ?: run {
                            deviceDao.insertDevice(com.chhanda.ai.data.repository.DeviceEntity(
                                deviceName = req.name,
                                ipAddress = ip,
                                connectionTime = System.currentTimeMillis(),
                                isCurrentlyConnected = true,
                                connectionType = "SHARED"
                            ))
                        }
                        call.respondText("""{"ok":true}""", io.ktor.http.ContentType.Application.Json)
                    } catch(e: Exception) {
                        call.respondText("""{"ok":false}""", io.ktor.http.ContentType.Application.Json)
                    }
                }

                post("/v1/chat/completions") {
                    if (!validateAuth(call)) return@post
                    requestSemaphore.withPermit {
                        try {
                            val req = call.receive<OpenAiChatRequest>()
                            val lastUserMsg = req.messages.lastOrNull { it.role == "user" }?.content ?: ""
                            val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true

                            if (req.stream) {
                                call.respondTextWriter(io.ktor.http.ContentType.Text.EventStream) {
                                    requestSemaphore.withPermit {
                                        try {
                                            val currentModel = llmEngine.getCurrentModelName()
                                            val msgId = "chatcmpl-${System.currentTimeMillis()}"
                                            sendMessageUseCase(
                                                userText = lastUserMsg,
                                                deviceId = call.request.local.remoteHost,
                                                modelName = currentModel,
                                                sessionId = "openai_session",
                                                source = "api",
                                                includeThinking = thinkingMode
                                            ).collect { upd ->
                                                if (upd is TokenUpdate.Partial) {
                                                    val chunk = """{"id":"$msgId","object":"chat.completion.chunk","created":${System.currentTimeMillis()/1000},"model":"$currentModel","choices":[{"index":0,"delta":{"content":"${upd.text.replace("\"", "\\\"").replace("\n", "\\n")}"},"finish_reason":null}]}"""
                                                    write("data: $chunk\n\n"); flush()
                                                } else if (upd is TokenUpdate.Final) {
                                                    write("data: [DONE]\n\n"); flush()
                                                }
                                            }
                                        } catch (e: Exception) { write("data: [DONE]\n\n"); flush() }
                                    }
                                }
                            } else {
                                var fullResponse = ""
                                sendMessageUseCase(
                                    userText = lastUserMsg,
                                    deviceId = call.request.local.remoteHost,
                                    modelName = llmEngine.getCurrentModelName(),
                                    sessionId = "openai_session",
                                    source = "api",
                                    includeThinking = thinkingMode
                                ).collect { upd ->
                                    if (upd is TokenUpdate.Partial) fullResponse += upd.text
                                }
                                call.respond(mapOf(
                                    "id" to "chatcmpl-${System.currentTimeMillis()}",
                                    "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to fullResponse), "finish_reason" to "stop"))
                                ))
                            }
                        } catch (e: Exception) {
                            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error")))
                        }
                    }
                }

                post("/chat") {
                    if (!validateAuth(call)) return@post
                    val remoteIp = call.request.local.remoteHost

                    val now = System.currentTimeMillis()
                    val lastRequest = clientRequestWindow[remoteIp] ?: 0L
                    if (now - lastRequest < RATE_LIMIT_MS) {
                        call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                        return@post
                    }
                    clientRequestWindow[remoteIp] = now

                    val msg = call.receive<WebMessage>()
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
                                val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true
                                sendMessageUseCase(
                                    userText = msg.text, 
                                    deviceId = remoteIp, 
                                    modelName = llmEngine.getCurrentModelName(), 
                                    sessionId = msg.sessionId ?: "api_session", 
                                    attachments = attachmentUris, 
                                    persona = msg.persona,
                                    includeThinking = thinkingMode
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
                    val apiKey = settingsRepository.apiKeyFlow.firstOrNull() ?: "8961221281"
                    val sessions = try { chatDao.getSessionIdsForDevice(call.request.local.remoteHost).firstOrNull() ?: emptyList() } catch(e: Exception) { emptyList() }
                    call.respondText(templateProvider.buildChatHtml(capturedPort, call.request.host(), emptyList(), false, "", sessions), io.ktor.http.ContentType.Text.Html)
                }
            }
        }
    }

    private fun showNotification(deviceName: String) {
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "chhanda_server"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(android.app.NotificationChannel(channelId, "Chhanda Server", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("New Connection").setContentText("$deviceName connected").build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun heartbeatDevice(ip: String) {
        serverScope.launch { try { deviceDao.updateDeviceStatus(deviceDao.getDeviceByIp(ip)?.deviceName ?: return@launch, true, System.currentTimeMillis()) } catch (_: Exception) {} }
    }
}
