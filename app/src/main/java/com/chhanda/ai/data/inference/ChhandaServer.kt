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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
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
data class WebMessage(val text: String, val role: String = "user", val attachments: List<WebAttachment> = emptyList(), val language: String = "en", val sessionId: String? = null, val isRefinement: Boolean = false)

@Serializable
data class RegisterRequest(val name: String)

/**
 * Chhanda embedded HTTP server.
 *
 * Engine: Ktor-CIO (pure Kotlin coroutines, zero native/JNI dependencies).
 * WHY NOT NETTY: Netty tries to load netty_tcnative native libs on start.
 * On Android these don't exist; the fallback detection itself throws exceptions
 * that get caught as "bind failed", exhausting all ports silently.
 *
 * Binding strategy: CIO's start(wait=false) returns before the coroutine that
 * actually binds the socket runs. We detect success via a TCP self-probe loop
 * (up to 12 × 150ms = 1.8s) rather than catching exceptions from start().
 */
@Singleton
class ChhandaServer @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val chatDao: ChatDao,
    private val deviceDao: DeviceDao,
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository,
    private val sendMessageUseCaseLazy: dagger.Lazy<SendMessageUseCase>,
    private val vectorChunkDao: com.chhanda.ai.data.repository.VectorChunkDao
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val sendMessageUseCase get() = sendMessageUseCaseLazy.get()
    @Volatile private var server: CIOApplicationEngine? = null
    @Volatile private var boundPort: Int = -1
    private var reaperJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startTime = System.currentTimeMillis()

    // Cached IP — getWifiIpAddressRaw() can take 1-3s on Android; never call it on /status
    @Volatile private var cachedIp: String = "127.0.0.1"
    @Volatile private var cachedAllIps: List<String> = emptyList()
    @Volatile private var cachedVpnStatus: Boolean = false
    @Volatile private var cachedPublicUrl: String = ""
    @Volatile private var tunnelActive: Boolean = false
    @Volatile private var lastIpRefresh = 0L
    
    // Reactive State
    private val _boundPortFlow = MutableStateFlow(-1)
    val boundPortFlow: StateFlow<Int> = _boundPortFlow.asStateFlow()
    
    private val _serverErrorFlow = MutableStateFlow<String?>(null)
    val serverErrorFlow: StateFlow<String?> = _serverErrorFlow.asStateFlow()

    private fun logAudit(tag: String, message: String, level: String) {
        android.util.Log.i("ChhandaAudit", "[$tag] ($level) $message")
    }

    private var tunnelSession: com.jcraft.jsch.Session? = null
    private val IP_TTL_MS = 10_000L

    companion object { private const val TAG = "ChhandaServer" }

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
                // VPN detection
                if (name.contains("tun") || name.contains("ppp") || name.contains("vpn") || name.contains("ipsec")) {
                    vpnFound = true
                }
                
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(name to addr.hostAddress!!)
                    }
                }
            }
        } catch (_: Exception) {}
        
        // SENIOR SORTING: Prioritize Hotspot interfaces and subnets
        val sortedIps = ips.sortedWith(compareBy { (name, ip) ->
            when {
                // Highest priority: Known Android Hotspot interface names
                name.contains("ap0") || name.contains("softap") || name.contains("wlan1") || name.contains("swlan") -> 0
                // Second priority: Known Android Hotspot standard subnets
                ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.") || ip.startsWith("192.168.45.") -> 1
                // Third priority: Standard WLAN
                name.startsWith("wlan0") -> 2
                // Fourth priority: Ethernet
                name.startsWith("eth") -> 3
                // Fifth priority: Other LAN IPs
                ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.") -> 4
                else -> 5
            }
        }).map { it.second }.distinct()
        
        Log.d(TAG, "Senior Interface Discovery: $sortedIps (VPN: $vpnFound)")
        return Triple(sortedIps.firstOrNull(), sortedIps, vpnFound)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start(requestedPort: Int, @Suppress("UNUSED_PARAMETER") maxDevices: Int) {
        stop()
        startTime = System.currentTimeMillis()
        lastIpRefresh = 0L
        val ip = freshIp()

        // SENIOR DIAGNOSTIC: Log all interfaces for troubleshooting
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            Log.i(TAG, "--- NETWORK INTERFACE MAP ---")
            while (ifaces?.hasMoreElements() == true) {
                val iface = ifaces.nextElement()
                val addrs = iface.inetAddresses.asSequence().filter { it is java.net.Inet4Address }.map { it.hostAddress }.toList()
                Log.d(TAG, "IFACE: ${iface.name} | UP: ${iface.isUp} | ADDRS: $addrs")
            }
            Log.i(TAG, "-----------------------------")
        } catch (_: Exception) {}

        if (ip == "127.0.0.1") {
            Log.w(TAG, "No WiFi/Hotspot detected; server will be loopback-only.")
        }

        _serverErrorFlow.value = null
        for (port in requestedPort..requestedPort + 10) {
            Log.i(TAG, "Binding Chhanda Node to 0.0.0.0:$port (Best IP: $ip)")
            try {
                val engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    configureEngine(this, port)
                }
                engine.start(wait = false)

                // SENIOR PROBE: Verify cross-interface reachability
                var ok = false
                val probeIps = mutableListOf("127.0.0.1")
                if (ip != "127.0.0.1") probeIps.add(0, ip)
                
                // Also probe common hotspot gateways just in case
                listOf("192.168.43.1", "192.168.44.1", "192.168.45.1").forEach {
                    if (it !in probeIps) probeIps.add(it)
                }

                outer@for (pIp in probeIps) {
                    val probeUrl = java.net.URL("http://$pIp:$port/ping")
                    for (i in 1..10) { 
                        Thread.sleep(200)
                        try {
                            val connection = probeUrl.openConnection() as java.net.HttpURLConnection
                            connection.connectTimeout = 300
                            connection.readTimeout = 300
                            val text = connection.inputStream.bufferedReader().readText()
                            if (text == "pong") {
                                ok = true
                                Log.i(TAG, "✅ Reachability confirmed on $pIp:$port")
                                break@outer
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (ok) {
                    server = engine
                    boundPort = port
                    _boundPortFlow.value = port
                    startReaper()
                    return
                } else {
                    Log.w(TAG, "Port $port: local probe failed, but proceeding anyway (client-side connection may still work).")
                    server = engine
                    boundPort = port
                    _boundPortFlow.value = port
                    startReaper()
                    return
                }
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
                delay(10000) // Check every 10 seconds
                try {
                    val cutoff = System.currentTimeMillis() - 30000 // 30s timeout (3 heartbeats)
                    val active = deviceDao.getActiveConnections()
                    active.forEach { device ->
                        if (device.lastActive < cutoff) {
                            deviceDao.updateDeviceStatus(device.deviceName, false, device.lastActive)
                            Log.d(TAG, "Connection Timeout: ${device.deviceName} has been marked offline.")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reaper failed: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        reaperJob?.cancel()
        reaperJob = null
        stopTunnel()
        server?.let { try { it.stop(100, 300) } catch (_: Exception) {} }
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
                // Localhost.run is a zero-config tunnel service over SSH
                val session = jsch.getSession("nokey", "localhost.run", 22)
                val config = java.util.Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)
                session.connect(20000)
                
                // Request remote port forwarding (80 is the standard HTTP port for the tunnel)
                session.setPortForwardingR(80, "127.0.0.1", boundPort)
                
                // To get the URL, we need to read the terminal greeting from localhost.run
                val channel = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
                val inputStream = channel.inputStream
                channel.connect()
                
                val reader = inputStream.bufferedReader()
                for (i in 0..100) { // Read first 100 lines looking for the URL
                    val line = reader.readLine() ?: break
                    if (line.contains("lhr.life") || line.contains("localhost.run")) {
                        val regex = "https://[a-zA-Z0-9.-]+\\.(lhr\\.life|localhost\\.run)".toRegex()
                        val match = regex.find(line)
                        if (match != null) {
                            cachedPublicUrl = match.value
                            tunnelActive = true
                            Log.i(TAG, "🚀 Tunnel Active: $cachedPublicUrl")
                            break
                        }
                    }
                }
                tunnelSession = session
                
                // Keep the thread alive while the session is active to prevent GC of the channel
                while(session.isConnected) { Thread.sleep(5000) }
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel Error: ${e.message}")
                tunnelActive = false
                cachedPublicUrl = ""
            }
        }.apply { name = "TunnelThread"; isDaemon = true }.start()
    }

    fun stopTunnel() {
        try {
            tunnelSession?.disconnect()
        } catch (_: Exception) {}
        tunnelSession = null
        tunnelActive = false
        cachedPublicUrl = ""
    }

    // ── Engine Builder ─────────────────────────────────────────────────────────

    private fun configureEngine(app: io.ktor.server.application.Application, capturedPort: Int) {
        app.apply {
            install(ContentNegotiation) { json() }
            install(CORS) {
                anyHost()
                allowHeader(io.ktor.http.HttpHeaders.ContentType)
                allowHeader(io.ktor.http.HttpHeaders.Authorization)
                allowHeader("*")
                allowMethod(io.ktor.http.HttpMethod.Options)
                allowMethod(io.ktor.http.HttpMethod.Get)
                allowMethod(io.ktor.http.HttpMethod.Post)
            }
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.headers.append("X-Powered-By", "Chhanda-AI-Gateway")
            }
            routing {

                // /status — < 1ms, high-priority diagnostic
                post("/register") {
                    try {
                        val remoteIp = call.request.local.remoteHost
                        val data = call.receive<Map<String, String>>()
                        val name = data["name"] ?: "Unknown Browser"
                        
                        // Store in DB for audit trail
                        try {
                            val existing = deviceDao.getDeviceByIp(remoteIp)
                            if (existing != null) {
                                deviceDao.updateDevice(existing.copy(
                                    deviceName = name, 
                                    connectionTime = System.currentTimeMillis(),
                                    lastActive = System.currentTimeMillis(),
                                    isCurrentlyConnected = true
                                ))
                            } else {
                                deviceDao.insertDevice(com.chhanda.ai.data.repository.DeviceEntity(
                                    deviceName = name,
                                    ipAddress = remoteIp,
                                    connectionTime = System.currentTimeMillis(),
                                    lastActive = System.currentTimeMillis(),
                                    isCurrentlyConnected = true,
                                    connectionType = "BROWSER",
                                    userAgent = call.request.headers["User-Agent"] ?: "Browser"
                                ))
                            }
                            android.util.Log.i("ChhandaAudit", "Registered user: $name ($remoteIp)")
                        } catch (e: Exception) {
                            android.util.Log.e("ChhandaAudit", "Failed to register user: ${e.message}")
                        }
                        
                        call.respond(mapOf("status" to "success"))
                    } catch (e: Exception) {
                        call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                    }
                }

                get("/status") {
                    val loaded    = llmEngine.isModelLoaded()
                    val uptime    = (System.currentTimeMillis() - startTime) / 1000
                    val addr      = freshIp()
                    val liteRt    = llmEngine as? LiteRTLMEngine
                    val isLoading = liteRt?.isLoading ?: !loaded
                    val loadErr   = (liteRt?.lastLoadError ?: "").replace("\"", "'")
                    
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText(
                        """{"ok":true,"modelLoaded":$loaded,"isLoading":$isLoading,"loadError":"$loadErr","ip":"$addr","port":$capturedPort,"uptime":$uptime}""",
                        io.ktor.http.ContentType.Application.Json
                    )
                }

                // /ping — Bare minimum for connectivity verification
                get("/ping") {
                    val remoteIp = call.request.local.remoteHost
                    heartbeatDevice(remoteIp)
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.respondText("pong")
                }


                get("/chat/history/{sessionId}") {
                    val sessionId = call.parameters["sessionId"] ?: return@get call.respond(io.ktor.http.HttpStatusCode.BadRequest)
                    val messages = chatDao.getMessagesForSession(sessionId).firstOrNull() ?: emptyList()
                    val json = messages.joinToString(",", "[", "]") { msg ->
                        """{"text":"${msg.text.replace("\"", "\\\"").replace("\n", "\\n")}","role":"${msg.role}","timestamp":${msg.timestamp}}"""
                    }
                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                }

                get("/download/{fileName}") {
                    val fileName = call.parameters["fileName"] ?: return@get call.respond(io.ktor.http.HttpStatusCode.BadRequest)
                    val file = java.io.File(this@ChhandaServer.context.filesDir, "generated/${fileName}")
                    if (file.exists()) {
                        call.response.header(
                            io.ktor.http.HttpHeaders.ContentDisposition,
                            io.ktor.http.ContentDisposition.Attachment.withParameter(
                                io.ktor.http.ContentDisposition.Parameters.FileName, fileName
                            ).toString()
                        )
                        call.respondFile(file)
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.NotFound)
                    }
                }

                // Captive Portal Detection Routes (Android, iOS, Windows, Chrome)
                val portalRoutes = listOf(
                    "/generate_204", "/gen_204", "/check_network_status", 
                    "/hotspot-detect.html", "/library/test/success.html",
                    "/success.txt", "/ncsi.txt", "/connecttest.txt",
                    "/redirect", "/wpad.dat"
                )
                portalRoutes.forEach { route ->
                    get(route) {
                        Log.d(TAG, "Captive portal probe on $route")
                        if (route.contains("204")) {
                            call.respond(io.ktor.http.HttpStatusCode.NoContent)
                        } else if (route.contains("success") || route.contains("connecttest")) {
                            call.respondText("success")
                        } else {
                            val apiKey = settingsRepository.apiKeyFlow.firstOrNull() ?: ""
                            call.respondRedirect("/?key=$apiKey")
                        }
                    }
                }

                // / — Chat UI — no-store prevents stale cached page
                get("/") {
                    val apiKey = settingsRepository.apiKeyFlow.firstOrNull()
                    val providedKey = call.request.queryParameters["key"]
                    
                    if (apiKey != null && providedKey != apiKey) {
                        call.response.headers.append("Cache-Control", "no-store")
                        call.respondText(
                            buildAccessDeniedHtml(),
                            io.ktor.http.ContentType.Text.Html,
                            io.ktor.http.HttpStatusCode.Unauthorized
                        )
                        return@get
                    }

                    // SENIOR MOVE: Use the host requested by the client, NOT the detected IP
                    // This is fail-proof because if the client reached this GET, they know the route.
                    val clientUsedHost = call.request.host()
                    val remoteIp = call.request.local.remoteHost
                    val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                    
                    val maxAllowed = settingsRepository.maxDevicesFlow.firstOrNull() ?: 5
                    val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000
                    val activeConnections = deviceDao.getActiveConnections().filter { it.lastActive > fiveMinutesAgo }
                    val existingDevice = deviceDao.getDeviceByIp(remoteIp)
                    
                    if (existingDevice == null && activeConnections.size >= maxAllowed) {
                        call.response.headers.append("Cache-Control", "no-store")
                        call.respondText(
                            buildMaxLimitReachedHtml(maxAllowed),
                            io.ktor.http.ContentType.Text.Html,
                            io.ktor.http.HttpStatusCode.Forbidden
                        )
                        return@get
                    }
                    
                    val hasConnectedEarlier = existingDevice != null
                    val savedName = existingDevice?.deviceName ?: ""
                    
                    trackDevice(remoteIp, userAgent)
                    
                    val sessions = try { chatDao.getSessionIdsForDevice(remoteIp).firstOrNull() ?: emptyList() } catch(e: Exception) { emptyList() }
                    
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respondText(
                        buildChatHtml(capturedPort, clientUsedHost, emptyList(), hasConnectedEarlier, savedName, sessions),
                        io.ktor.http.ContentType.Text.Html
                    )
                }

                // CATCH-ALL REDIRECT: Redirect any unknown path to the home page (Captive Portal style)
                get("{...}") {
                    val apiKey = settingsRepository.apiKeyFlow.firstOrNull() ?: ""
                    call.respondRedirect("/?key=$apiKey")
                }

                post("/upload") {
                    val apiKey = settingsRepository.apiKeyFlow.firstOrNull()
                    val providedKey = call.request.queryParameters["key"] ?: call.request.headers["X-API-Key"]
                    
                    if (apiKey != null && providedKey != apiKey) {
                        call.respondText("Unauthorized", status = io.ktor.http.HttpStatusCode.Unauthorized)
                        return@post
                    }

                    try {
                        val multipart = call.receiveMultipart()
                        var uploadedFileName = ""
                        var uploadedFileUri = ""
                        
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                val name = part.originalFileName ?: "upload_${System.currentTimeMillis()}"
                                val uploadDir = java.io.File(this@ChhandaServer.context.cacheDir, "api_uploads")
                                if (!uploadDir.exists()) uploadDir.mkdirs()
                                val file = java.io.File(uploadDir, name)
                                
                                part.streamProvider().use { input ->
                                    file.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                uploadedFileName = name
                                uploadedFileUri = android.net.Uri.fromFile(file).toString()
                                
                                // Trigger RAG ingestion
                                val ext = name.substringAfterLast(".", "").lowercase()
                                val docType = when (ext) {
                                    "pdf" -> com.chhanda.ai.domain.usecase.DocType.PDF
                                    "docx", "doc" -> com.chhanda.ai.domain.usecase.DocType.WORD
                                    "xlsx", "xls" -> com.chhanda.ai.domain.usecase.DocType.EXCEL
                                    else -> com.chhanda.ai.domain.usecase.DocType.TXT
                                }

                                val workRequest = OneTimeWorkRequestBuilder<com.chhanda.ai.service.IngestionWorker>()
                                    .setInputData(workDataOf(
                                        com.chhanda.ai.service.IngestionWorker.KEY_URI to uploadedFileUri,
                                        com.chhanda.ai.service.IngestionWorker.KEY_TYPE to docType.name,
                                        com.chhanda.ai.service.IngestionWorker.KEY_NAME to uploadedFileName
                                    ))
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .build()
                                    
                                WorkManager.getInstance(this@ChhandaServer.context).enqueue(workRequest)
                            }
                            part.dispose()
                        }
                        
                        call.respond(mapOf("status" to "Success", "file" to uploadedFileName, "message" to "File uploaded and indexed for AI understanding."))
                    } catch (e: Exception) {
                        Log.e(TAG, "Upload failed: ${e.message}")
                        call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                    }
                }

                post("/ingest") {
                    val apiKey = settingsRepository.apiKeyFlow.firstOrNull()
                    val providedKey = call.request.queryParameters["key"] ?: call.request.headers["X-API-Key"]
                    
                    if (apiKey != null && providedKey != apiKey) {
                        call.respondText("Unauthorized", status = io.ktor.http.HttpStatusCode.Unauthorized)
                        return@post
                    }

                    try {
                        val data = call.receive<Map<String, String>>()
                        val url = data["url"] ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing URL"))
                        val label = data["label"] ?: "Web Resource"
                        
                        val workRequest = OneTimeWorkRequestBuilder<com.chhanda.ai.service.IngestionWorker>()
                            .setInputData(workDataOf(
                                com.chhanda.ai.service.IngestionWorker.KEY_URL to url,
                                com.chhanda.ai.service.IngestionWorker.KEY_NAME to label
                            ))
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .build()
                            
                        WorkManager.getInstance(this@ChhandaServer.context).enqueue(workRequest)
                        
                        call.respond(mapOf("status" to "In Progress", "message" to "Ingestion task queued for $label"))
                    } catch (e: Exception) {
                        call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                    }
                }

                post("/chat") {
                    val apiKey = settingsRepository.apiKeyFlow.firstOrNull()
                    val providedKey = call.request.queryParameters["key"] ?: call.request.headers["X-API-Key"]
                    
                    if (apiKey != null && providedKey != apiKey) {
                        call.respondText("Unauthorized", status = io.ktor.http.HttpStatusCode.Unauthorized)
                        return@post
                    }

                    val remoteIp = call.request.local.remoteHost
                    if (!llmEngine.isModelLoaded()) {
                        call.respondText("Model loading", status = io.ktor.http.HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    val msg = try { call.receive<WebMessage>() }
                              catch (e: Exception) { 
                                  Log.e(TAG, "Bad chat request: ${e.message}")
                                  call.respondText("Bad request", status = io.ktor.http.HttpStatusCode.BadRequest)
                                  return@post 
                              }
                    Log.d(TAG, "Processing chat: ${msg.text.take(50)}...")
                    
                    call.response.headers.append("Cache-Control", "no-cache")
                    call.response.headers.append("Connection", "keep-alive")
                    call.respondTextWriter(io.ktor.http.ContentType.Text.EventStream) {
                        try {
                            val uris = msg.attachments.mapNotNull { attachment ->
                                val base64Data = attachment.data.substringAfter("base64,")
                                com.chhanda.ai.util.FileUtils.saveBase64ToFile(this@ChhandaServer.context, base64Data, attachment.name)
                            }
                            val languageName = when(msg.language) {
                                "bn" -> "Bengali"
                                "hi" -> "Hindi"
                                "fr" -> "French"
                                "de" -> "German"
                                else -> "English"
                            }
                            val sessionIdToUse = msg.sessionId ?: "api_session"
                            val sourceToUse = if (msg.sessionId != null) "qr" else "api"
                            
                            try {
                                val remoteIp = call.request.local.remoteHost
                                val existing = deviceDao.getDeviceByIp(remoteIp)
                                if (existing != null) {
                                    deviceDao.updateDevice(existing.copy(
                                        connectionTime = System.currentTimeMillis(), 
                                        lastActive = System.currentTimeMillis(),
                                        isCurrentlyConnected = true,
                                        connectionType = if (sourceToUse == "api") "API" else existing.connectionType
                                    ))
                                } else {
                                    deviceDao.insertDevice(com.chhanda.ai.data.repository.DeviceEntity(
                                        deviceName = if (sourceToUse == "api") "API Client" else "Unknown Browser",
                                        ipAddress = remoteIp,
                                        connectionTime = System.currentTimeMillis(),
                                        isCurrentlyConnected = true,
                                        connectionType = if (sourceToUse == "api") "API" else "BROWSER",
                                        userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                                    ))
                                }
                            } catch (e: Exception) {}
                            
                            val promptToSend = if (msg.isRefinement) {
                                """
                                ### TRANSCRIPT REFINEMENT TASK
                                Please polish the following raw spoken transcript into professional, well-structured text. 
                                - Fix grammar and punctuation.
                                - Remove filler words (like "um", "uh", "you know").
                                - Improve sentence flow and clarity.
                                - Keep the original tone and all key information.
                                - Respond ONLY with the polished text.
                                
                                RAW TRANSCRIPT:
                                "${msg.text}"
                                """.trimIndent()
                            } else {
                                msg.text
                            }

                            var clearedInitialThinking = false

                            sendMessageUseCase(promptToSend, remoteIp, llmEngine.getCurrentModelName(), sessionIdToUse, uris, languageName, isRefinement = msg.isRefinement, source = sourceToUse).collect { upd ->
                                when (upd) {
                                    is TokenUpdate.Partial -> {
                                        if (!clearedInitialThinking) {
                                            write("data: CLR:1\n\n")
                                            clearedInitialThinking = true
                                        }
                                        write("data: ${upd.text.replace("\n", "\\n")}\n\n")
                                        flush()
                                    }
                                    is TokenUpdate.Final -> {
                                        // Auto-ingest attachments if they are documents
                                        msg.attachments.forEach { attachment ->
                                            val ext = attachment.name.substringAfterLast(".", "").lowercase()
                                            if (listOf("pdf", "docx", "doc", "xlsx", "xls", "txt").contains(ext)) {
                                                val uri = com.chhanda.ai.util.FileUtils.saveBase64ToFile(this@ChhandaServer.context, attachment.data.substringAfter("base64,"), attachment.name)
                                                if (uri != null) {
                                                    val docType = when (ext) {
                                                        "pdf" -> com.chhanda.ai.domain.usecase.DocType.PDF
                                                        "docx", "doc" -> com.chhanda.ai.domain.usecase.DocType.WORD
                                                        "xlsx", "xls" -> com.chhanda.ai.domain.usecase.DocType.EXCEL
                                                        else -> com.chhanda.ai.domain.usecase.DocType.TXT
                                                    }
                                                    val workRequest = OneTimeWorkRequestBuilder<com.chhanda.ai.service.IngestionWorker>()
                                                        .setInputData(workDataOf(
                                                            com.chhanda.ai.service.IngestionWorker.KEY_URI to uri.toString(),
                                                            com.chhanda.ai.service.IngestionWorker.KEY_TYPE to docType.name,
                                                            com.chhanda.ai.service.IngestionWorker.KEY_NAME to attachment.name
                                                        ))
                                                        .build()
                                                    WorkManager.getInstance(this@ChhandaServer.context).enqueue(workRequest)
                                                }
                                            }
                                        }

                                        write("data: RT:${upd.responseTimeMs}\n\n")
                                        write("data: [DONE]\n\n")
                                        flush()
                                    }
                                    is TokenUpdate.Error -> { 
                                        write("data: ERR:${upd.message}\n\n")
                                        flush() 
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            try { write("data: ERR:${e.message}\n\n"); flush() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    private fun trackDevice(ip: String, userAgent: String = "Remote Client", customName: String? = null) {
        if (ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1") return

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val existing = deviceDao.getDeviceByIp(ip)
                val lastOctet = ip.split(".").lastOrNull() ?: ip.takeLast(3)
                
                val uniqueName = if (customName != null) {
                    "$customName ($lastOctet)"
                } else if (existing != null) {
                    existing.deviceName
                } else {
                    val fallbackName = "Device"
                    val parsedName = when {
                        userAgent.contains("iPhone") -> "iPhone"
                        userAgent.contains("iPad") -> "iPad"
                        userAgent.contains("Android") -> "Android"
                        userAgent.contains("Windows") -> "Windows PC"
                        userAgent.contains("Macintosh") -> "Mac"
                        else -> fallbackName
                    }
                    "$parsedName ($lastOctet)"
                }
                
                if (existing == null) {
                    val newDevice = com.chhanda.ai.data.repository.DeviceEntity(
                        deviceName = uniqueName,
                        ipAddress = ip,
                        connectionTime = System.currentTimeMillis(),
                        isCurrentlyConnected = true,
                        connectionType = "SHARED",
                        userAgent = userAgent,
                        lastActive = System.currentTimeMillis()
                    )
                    deviceDao.insertDevice(newDevice)
                } else {
                    deviceDao.updateDeviceStatus(existing.deviceName, true, System.currentTimeMillis())
                }
                
                // Show notification on the device
                showNotification(uniqueName)
                
            } catch (e: Exception) {
                Log.w(TAG, "Device tracking failure for $ip: ${e.message}")
            }
        }
    }

    private fun showNotification(deviceName: String) {
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "chhanda_server"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Chhanda Server", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New Connection")
            .setContentText("$deviceName connected to Chhanda")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun heartbeatDevice(ip: String) {
        if (ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1") return
        serverScope.launch {
            try {
                val existing = deviceDao.getDeviceByIp(ip)
                if (existing != null) {
                    deviceDao.updateDeviceStatus(existing.deviceName, true, System.currentTimeMillis())
                }
            } catch (_: Exception) {}
        }
    }

    // ── IP Util ────────────────────────────────────────────────────────────────


    // ── HTML ───────────────────────────────────────────────────────────────────

    private fun buildChatHtml(port: Int, ip: String, suggestions: List<String> = emptyList(), hasConnectedEarlier: Boolean, savedName: String, sessions: List<String>) = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Chhanda</title>
    <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
    <style>
        :root {
            --font: system-ui, -apple-system, sans-serif;
            /* Material You (M3) Dark Theme Colors */
            --bg: #141218;
            --surface: #1D1B20;
            --surface-container: #211F26;
            --border: #49454F;
            --fg: #E6E1E5;
            --muted: #CAC4D0;
            --primary: #D0BCFF;
            --user-bubble: #4F378B;
            --ai-bubble: #36343B;
            --on-primary: #381E72;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        
        /* Fix for fitting in web browser and handling mobile URL bars */
        html, body {
            height: 100%;
            height: -webkit-fill-available;
            overflow: hidden;
        }
        
        body {
            font-family: var(--font);
            background: var(--bg);
            color: var(--fg);
            display: flex;
            flex-direction: column;
        }
        
        #hdr {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px 12px;
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            flex-shrink: 0;
            z-index: 10;
        }
        
        #logo {
            width: 32px;
            height: 32px;
            border-radius: 8px;
            background: #ffffff;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 1px 3px rgba(0,0,0,0.2);
        }
        
        #title {
            font-weight: 500;
            font-size: 16px;
            color: var(--fg);
        }
        
        #title::after {
            content: "Chhanda";
        }
        
        #badge {
            margin-left: auto;
            display: flex;
            align-items: center;
            gap: 4px;
            font-size: 11px;
            font-weight: 500;
            padding: 4px 8px;
            border-radius: 100px;
            border: 1px solid var(--border);
            color: var(--muted);
            background: var(--surface-container);
            transition: all 0.3s ease;
        }
        
        #dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: var(--muted);
            transition: all 0.3s;
        }
        
        .on { color: #B3F5B8 !important; border-color: rgba(179, 245, 184, 0.2) !important; }
        .on #dot { background: #B3F5B8 !important; box-shadow: 0 0 8px #B3F5B8; }
        .warm { color: #FFDEB5 !important; border-color: rgba(255, 222, 181, 0.2) !important; }
        .warm #dot { background: #FFDEB5 !important; box-shadow: 0 0 8px #FFDEB5; }
        .err { color: #FFB4AB !important; border-color: rgba(255, 180, 171, 0.2) !important; }
        .err #dot { background: #FFB4AB !important; box-shadow: 0 0 8px #FFB4AB; }
        
        #msgs {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 12px;
            scroll-behavior: smooth;
        }
        
        .msg-container {
            display: flex;
            flex-direction: column;
            max-width: 80%;
            animation: slideUp 0.2s cubic-bezier(0, 0, 0.2, 1);
        }
        
        .msg-container.u {
            align-self: flex-end;
        }
        
        .msg-container.a {
            align-self: flex-start;
        }
        
        .msg-container.s {
            align-self: center;
            max-width: 90%;
        }
        
        .msg {
            padding: 12px 16px;
            border-radius: 20px;
            font-size: 15px;
            line-height: 1.5;
            word-break: break-word;
        }
        
        @keyframes slideUp {
            from { opacity: 0; transform: translateY(8px); }
            to { opacity: 1; transform: translateY(0); }
        }
        
        .u .msg {
            background: var(--user-bubble);
            color: #fff;
            border-bottom-right-radius: 4px;
        }
        
        .a .msg {
            background: var(--ai-bubble);
            color: var(--fg);
            border-bottom-left-radius: 4px;
            border: 1px solid var(--border);
        }
        
        .s .msg {
            background: transparent;
            border: 1px dashed var(--border);
            color: var(--muted);
            font-size: 13px;
            padding: 6px 12px;
            border-radius: 8px;
            text-align: center;
        }
        
        .actions {
            display: flex;
            gap: 8px;
            margin-top: 4px;
            margin-left: 4px;
            align-items: center;
            width: 100%;
        }
        
        .action-btn {
            background: transparent;
            border: none;
            color: var(--muted);
            cursor: pointer;
            padding: 4px;
            display: flex;
            align-items: center;
            justify-content: center;
            border-radius: 4px;
            transition: all 0.2s;
        }
        
        .action-btn:hover {
            color: var(--primary);
            background: rgba(255,255,255,0.05);
        }
        
        .speed-label {
            font-size: 11px;
            color: var(--muted);
            align-self: center;
            margin-right: auto;
        }
        
        .tts-player {
            display: none;
            background: rgba(255, 255, 255, 0.05);
            border-radius: 12px;
            padding: 8px 12px;
            margin-top: 8px;
            width: 100%;
            align-items: center;
            gap: 10px;
            border: 1px solid var(--border);
            animation: slideUp 0.2s ease;
        }
        .tts-progress-container {
            flex: 1;
            height: 4px;
            background: rgba(255,255,255,0.1);
            border-radius: 2px;
            overflow: hidden;
            position: relative;
        }
        .tts-progress-bar {
            height: 100%;
            background: var(--primary);
            width: 0%;
            transition: width 0.2s linear;
        }
        .tts-btn {
            background: transparent;
            border: none;
            color: var(--primary);
            cursor: pointer;
            padding: 4px;
            display: flex;
            align-items: center;
            justify-content: center;
            border-radius: 50%;
            transition: all 0.2s;
        }
        .tts-btn:hover { background: rgba(255,255,255,0.1); transform: scale(1.1); }
        
        #ftr {
            padding: 12px 16px;
            background: var(--surface);
            border-top: 1px solid var(--border);
            flex-shrink: 0;
        }
        
        #row {
            display: flex;
            align-items: center;
            gap: 8px;
            background: var(--surface-container);
            border-radius: 28px;
            padding: 4px 4px 4px 16px;
            transition: all 0.3s ease;
            max-width: 720px;
            margin: 0 auto;
        }
        
        #inp {
            flex: 1;
            background: transparent;
            border: none;
            outline: none;
            color: var(--fg);
            font-size: 16px;
            padding: 10px 0;
        }
        
        #inp::placeholder { color: var(--muted); }
        
        #btn {
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: var(--primary);
            border: none;
            color: var(--on-primary);
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;
            transition: transform 0.2s;
        }
        
        #btn:hover { transform: scale(1.05); }
        #btn:disabled { opacity: 0.3; cursor: not-allowed; transform: none; }
        
        #ovl {
            display: none;
            position: fixed;
            inset: 0;
            background: rgba(20, 18, 24, 0.8);
            z-index: 100;
            align-items: center;
            justify-content: center;
            backdrop-filter: blur(4px);
        }
        
        #crd {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 28px;
            padding: 24px;
            max-width: 360px;
            width: 90%;
            text-align: center;
        }
        
        #crd h2 { font-size: 20px; margin-bottom: 8px; color: var(--fg); }
        #crd p { color: var(--muted); font-size: 14px; line-height: 1.6; margin-bottom: 20px; }
        #crd code { background: var(--surface-container); padding: 2px 6px; border-radius: 4px; font-size: 12px; }
        
        #rtry {
            width: 100%;
            background: var(--primary);
            color: var(--on-primary);
            border: none;
            padding: 12px;
            border-radius: 100px;
            font-weight: 500;
            font-size: 14px;
            cursor: pointer;
        }
        
        /* Markdown formatting styles */
        .msg b { font-weight: 700; color: #fff; }
        .msg code { background: rgba(0, 0, 0, 0.3); padding: 2px 6px; border-radius: 4px; font-family: monospace; font-size: 13px; }
        
        details.think {
            background: rgba(0,0,0,0.15);
            padding: 10px;
            border-radius: 12px;
            font-size: 13px;
            color: var(--muted);
            margin-bottom: 8px;
            border: 1px solid rgba(255,255,255,0.05);
        }
        details.think summary {
            cursor: pointer;
            font-weight: 500;
            color: var(--primary);
            outline: none;
        }
        
        .think-loader {
            color: var(--muted);
            font-size: 13px;
            font-style: italic;
            padding: 8px;
            background: rgba(0,0,0,0.1);
            border-radius: 8px;
            margin-bottom: 8px;
            display: inline-block;
            animation: pulse-op 1.5s infinite;
        }
        
        .suggest-btn {
            background: var(--surface);
            border: 1px solid var(--border);
            color: var(--primary);
            padding: 6px 12px;
            border-radius: 16px;
            font-size: 12px;
            cursor: pointer;
            white-space: nowrap;
            transition: all 0.2s;
        }
        .suggest-btn:hover {
            background: var(--border);
            color: var(--fg);
        }
        #row {
            display: flex;
            flex-direction: column;
            gap: 12px;
            width: 100%;
        }
        
        #input-line {
            display: flex;
            align-items: center;
            gap: 12px;
            width: 100%;
        }
        
        #preview-area {
            display: none;
            flex-wrap: wrap;
            gap: 8px;
            padding: 8px 0;
            border-bottom: 1px solid var(--border);
        }
        
        .attach-chip {
            background: var(--surface-container);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 6px 12px;
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 12px;
            color: var(--primary);
            animation: slideUp 0.2s ease-out;
        }

        @keyframes slideUp {
            from { opacity: 0; transform: translateY(10px); }
            to { opacity: 1; transform: translateY(0); }
        }
        
        /* Name Modal Styles */
        #name-modal {
            display: none;
            position: fixed;
            inset: 0;
            background: rgba(20, 18, 24, 0.9);
            z-index: 200;
            align-items: center;
            justify-content: center;
            backdrop-filter: blur(8px);
        }
        #name-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 28px;
            padding: 24px;
            max-width: 360px;
            width: 90%;
            text-align: center;
        }
        #name-card h2 { font-size: 20px; margin-bottom: 8px; color: var(--fg); }
        #name-card p { color: var(--muted); font-size: 14px; line-height: 1.6; margin-bottom: 20px; }
        #name-inp {
            width: 100%;
            background: var(--surface-container);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 12px;
            color: var(--fg);
            font-size: 16px;
            margin-bottom: 16px;
            outline: none;
            text-align: center;
        }
        #name-btn {
            width: 100%;
            background: var(--primary);
            color: var(--on-primary);
            border: none;
            padding: 12px;
            border-radius: 100px;
            font-weight: 500;
            font-size: 14px;
            cursor: pointer;
        }
        
        @media (max-width: 600px) {
            .msg-container { max-width: 85%; }
            #title { display: none; }
            #badge span { display: none; }
            #hdr { padding: 4px 8px; }
        }
    </style>
</head>
<body>
    <div id="name-modal">
        <div id="name-card">
            <h2>Welcome!</h2>
            <p>Please enter your name to start chatting.</p>
            <input id="name-inp" type="text" placeholder="Your Name" autocomplete="off">
            <button id="name-btn">Continue</button>
        </div>
    </div>
    <div id="hdr">
        <div id="logo">
            <svg width="32" height="32" viewBox="0 0 108 108">
                <path d="M 70,30 A 28,28 0 1,0 70,78" stroke="#2563EB" stroke-width="8" stroke-linecap="round" fill="none"/>
                <path d="M 46,44 h 5 v 20 h -5 z" fill="#10B981"/>
                <path d="M 56,38 h 5 v 32 h -5 z" fill="#EF4444"/>
                <path d="M 66,48 h 5 v 12 h -5 z" fill="#2563EB"/>
            </svg>
        </div>
        <span id="title"></span>
        <div id="badge"><div id="dot"></div><span id="bt">CONNECTING</span></div>
        <select id="session-sel" style="background:var(--surface-container); border:1px solid var(--border); color:var(--muted); cursor:pointer; padding:2px 4px; font-size:10px; border-radius:4px;">
        </select>
        <select id="lang-sel" style="background:var(--surface-container); border:1px solid var(--border); color:var(--muted); cursor:pointer; padding:2px 4px; font-size:10px; border-radius:4px;">
            <option value="en">English</option>
            <option value="fr">Français</option>
            <option value="de">Deutsch</option>
            <option value="hi">Hindi</option>
            <option value="bn">Bengali</option>
        </select>
        <button id="close-btn" style="background:transparent; border:none; color:var(--muted); cursor:pointer; padding:4px; display:flex; align-items:center; justify-content:center; margin-left:auto;">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
        </button>
    </div>
    <div id="msgs">
        <div class="msg-container s"><div class="msg s">Connection established with Node at ${ip}:${port}</div></div>
    </div>
        <div id="ftr">
            <div id="row">
                <div id="preview-area"></div>
                <div id="input-line">
                    <button id="clip-btn" style="background:var(--surface-container); border:1px solid var(--border); color:var(--primary); width:44px; height:44px; border-radius:12px; display:flex; align-items:center; justify-content:center; cursor:pointer; flex-shrink:0;">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>
                        </svg>
                    </button>
                    <input id="file-inp" type="file" style="display:none" multiple accept="image/*,audio/*,text/*,application/pdf,.csv,.json,.xlsx,.txt">
                    <div style="flex:1; position:relative; display:flex; align-items:center;">
                        <input id="inp" placeholder="Waiting for AI…" autocomplete="off" disabled style="width:100%; padding-right:45px;">
                        <button id="polish-btn" style="position:absolute; right:8px; background:none; border:none; color:var(--primary); cursor:pointer; display:none; padding:8px;">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>
                        </button>
                    </div>
                    <button id="btn" disabled style="width:44px; height:44px; border-radius:12px; flex-shrink:0; display:flex; align-items:center; justify-content:center;">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                            <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
                        </svg>
                    </button>
                </div>
            </div>
        </div>
    <div id="ovl">
        <div id="crd">
            <h2 id="ovl-title">⚠️ Cannot Reach Node</h2>
            <p id="ovl-text">Node at <code>${ip}:${port}</code> is not responding.<br><br>
            <b>Troubleshooting:</b><br>
            • Ensure both devices share the SAME Wi-Fi.<br>
            • <b>iPhone:</b> Turn off "Private Relay" or "Limit IP Address Tracking" in Wi-Fi settings.<br>
            • Ensure Chhanda app is active on the phone.</p>
            <button id="rtry" onclick="location.reload()">Retry Connection</button>
        </div>
    </div>
    <script>
        const initialSessions = ${sessions.joinToString(",", "[", "]") { "\"$it\"" }};
        const hasConnectedEarlier = $hasConnectedEarlier;
        const savedName = "$savedName";
        
        const urlParams = new URLSearchParams(window.location.search);
        const apiKeyParam = urlParams.get('key');
        
        let currentSessionId = localStorage.getItem('currentSessionId');
        if (!currentSessionId) {
            currentSessionId = 'session_' + Math.random().toString(36).substring(2, 15);
            localStorage.setItem('currentSessionId', currentSessionId);
        }
        
        const msgs=document.getElementById('msgs'),inp=document.getElementById('inp'),
              btn=document.getElementById('btn'),badge=document.getElementById('badge'),
              bt=document.getElementById('bt'),ovl=document.getElementById('ovl'),
              clipBtn=document.getElementById('clip-btn'),
              fileInp=document.getElementById('file-inp'),
              polishBtn=document.getElementById('polish-btn'),
              nameModal=document.getElementById('name-modal'),
              nameInp=document.getElementById('name-inp'),
              nameBtn=document.getElementById('name-btn');
        let ready=false,fails=0,errShown=false;
        const MAX=12;
        
        let currentFiles = [];
        const previewArea = document.getElementById('preview-area');
        
        clipBtn.addEventListener('click', () => fileInp.click());
        fileInp.addEventListener('change', async () => {
            const files = Array.from(fileInp.files);
            for(let f of files) {
                const reader = new FileReader();
                reader.onload = (e) => {
                    const id = Math.random().toString(36).substr(2, 9);
                    currentFiles.push({ id, name: f.name, type: f.type, data: e.target.result });
                    
                    const chip = document.createElement('div');
                    chip.className = 'attach-chip';
                    chip.id = 'chip-' + id;
                    chip.innerHTML = `<span>${'$'}{f.name}</span><span style="cursor:pointer; opacity:0.5;" onclick="removeFile('${'$'}{id}')">✕</span>`;
                    previewArea.appendChild(chip);
                    previewArea.style.display = 'flex';
                };
                reader.readAsDataURL(f);
            }
            fileInp.value = '';
        });

        function removeFile(id) {
            currentFiles = currentFiles.filter(f => f.id !== id);
            document.getElementById('chip-' + id).remove();
            if(currentFiles.length === 0) previewArea.style.display = 'none';
        }

        const closeBtn = document.getElementById('close-btn');
        if (closeBtn) {
            closeBtn.addEventListener('click', () => {
                if (confirm('Are you sure you want to close the chat?')) {
                    window.close();
                    // Fallback for browsers that block window.close()
                    alert('If the tab did not close, please close it manually.');
                }
            });
        }

        async function pulse(){
            try{
                const c=new AbortController();
                setTimeout(()=>c.abort(),5000);
                const r=await fetch('/status?key='+apiKeyParam+'&t='+Date.now(),{signal:c.signal, cache:'no-store'});
                if(!r.ok)throw new Error('HTTP '+r.status);
                const d=await r.json();
                fails=0; ovl.style.display='none'; ready=d.modelLoaded;
                if(ready){
                    badge.className='on';bt.textContent='ONLINE';
                    inp.disabled=false;btn.disabled=false;inp.placeholder='Message Chhanda…';
                    if(inp.value.trim()) polishBtn.style.display='block';
                }else if(d.loadError&&d.loadError.length){
                    badge.className='err';bt.textContent='LOAD ERROR';
                    inp.disabled=true;btn.disabled=true;
                    if(!errShown){errShown=true;addMsg('❌ '+d.loadError,'s');}
                }else{
                    badge.className='warm';bt.textContent='AI LOADING';
                    inp.disabled=true;btn.disabled=true;inp.placeholder='Model loading…';
                }
            }catch(e){
                fails++;
                badge.className='';bt.textContent='CONNECTING ('+fails+'/'+MAX+')';
                if(fails>=MAX){
                    ovl.style.display='flex';
                    document.getElementById('ovl-title').textContent = "🔴 Server is Offline";
                    document.getElementById('ovl-text').innerHTML = "The server is set to off. No further communication is possible for now.";
                    inp.disabled = true;
                    btn.disabled = true;
                }
            }
        }

        pulse(); 
        
        // TTS Player Logic
        let currentUtterance = null;
        let ttsPlayerActive = null;

        function stopTTS() {
            if (window.speechSynthesis) {
                window.speechSynthesis.cancel();
            }
            if (ttsPlayerActive) {
                ttsPlayerActive.style.display = 'none';
                const progressBar = ttsPlayerActive.querySelector('.tts-progress-bar');
                if (progressBar) progressBar.style.width = '0%';
                ttsPlayerActive = null;
            }
            currentUtterance = null;
        }

        function speakText(text, playerDiv) {
            // If already playing this player, just return
            if (ttsPlayerActive === playerDiv && window.speechSynthesis.speaking) return;

            stopTTS();
            
            // Filtering: Omit code blocks and handle markers
            let cleanText = text.replace(/```[\s\S]*?```/g, " [CODE_BLOCK_PLACEHOLDER] ");
            cleanText = cleanText.replace(/\[CODE_BLOCK_PLACEHOLDER\]/g, " Please check the code block for details. ");
            
            // Clean markdown bold/italic/headers
            cleanText = cleanText.replace(/\*\*/g, "").replace(/\*/g, "").replace(/#/g, "");
            
            const utterance = new SpeechSynthesisUtterance(cleanText);
            currentUtterance = utterance;
            ttsPlayerActive = playerDiv;
            ttsPlayerActive.style.display = 'flex';
            
            const progressBar = ttsPlayerActive.querySelector('.tts-progress-bar');
            const playPauseIcon = ttsPlayerActive.querySelector('.play-pause-icon');
            
            utterance.onboundary = (event) => {
                if (event.name === 'word') {
                    const progress = (event.charIndex / cleanText.length) * 100;
                    progressBar.style.width = progress + '%';
                }
            };
            
            utterance.onend = () => {
                progressBar.style.width = '100%';
                setTimeout(() => { if (ttsPlayerActive === playerDiv) stopTTS(); }, 1000);
            };

            utterance.onerror = (e) => {
                console.error("TTS Error:", e);
                stopTTS();
            };

            window.speechSynthesis.speak(utterance);
            playPauseIcon.innerHTML = '<path d="M6 4h4v16H6zM14 4h4v16h-4z"/>'; // Pause icon
        }

        function togglePlayPause() {
            if (!currentUtterance || !ttsPlayerActive) return;
            const playPauseIcon = ttsPlayerActive.querySelector('.play-pause-icon');
            
            if (window.speechSynthesis.speaking) {
                if (window.speechSynthesis.paused) {
                    window.speechSynthesis.resume();
                    playPauseIcon.innerHTML = '<path d="M6 4h4v16H6zM14 4h4v16h-4z"/>';
                } else {
                    window.speechSynthesis.pause();
                    playPauseIcon.innerHTML = '<path d="M5 3l14 9-14 9V3z"/>';
                }
            }
        }

        setInterval(pulse,2000);

        inp.addEventListener('input', () => {
            polishBtn.style.display = inp.value.trim() ? 'block' : 'none';
        });

        async function send(isRefinement = false){
            if(!ready)return;
            const txt=inp.value.trim();if(!txt)return;
            inp.value='';inp.disabled=true;btn.disabled=true;
            polishBtn.style.display='none';
            addMsg(txt,'u');const ai=addMsg('Thinking…','a');ai.innerHTML = `<div class="think-loader">⚡ Thinking...</div>`;
            let tokenCount = 0;
            let startTime = null;
            try {
                const res=await fetch('/chat?key='+apiKeyParam,{method:'POST',
                    headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({
                        text:txt,
                        role:'user', 
                        attachments: currentFiles.map(f => ({ name: f.name, type: f.type, data: f.data })), 
                        language: document.getElementById('lang-sel').value, 
                        sessionId: currentSessionId,
                        isRefinement: isRefinement
                    })});
                
                currentFiles = [];
                previewArea.innerHTML = '';
                previewArea.style.display = 'none';
                if(res.status===503){ai.className='msg s';ai.textContent='Model loading — try again.';return;}
                if(!res.ok){ai.className='msg s';ai.textContent='Error '+res.status;return;}
                const reader=res.body.getReader(),dec=new TextDecoder();
                let buf='';
                for(;;){
                    const{done,value}=await reader.read();if(done)break;
                    buf+=dec.decode(value,{stream:true});
                    const parts=buf.split('\n\n');buf=parts.pop();
                    for(const p of parts){
                        if(!p.startsWith('data: '))continue;
                        const tok=p.slice(6);
                        if(tok.trim()==='[DONE]')break;
                        if(tok.startsWith('ERR:')){ai.className='msg s';ai.textContent=tok.slice(4);break;}
                        
                        if (tok === 'CLR:1') {
                            ai.textContent = '';
                            continue;
                        }
                        
                        if (tok.startsWith('MSG:')) {
                             const html = tok.slice(4);
                             ai.innerHTML += html;
                             continue;
                        }
                        
                        if (tok.startsWith('RT:')) {
                             const responseTime = tok.slice(3);
                             ai.setAttribute('data-rt', responseTime);
                             continue;
                        }
                        
                        tokenCount++;
                        if (!startTime) startTime = Date.now();
                        
                        const currentText = ai.getAttribute('data-raw') || '';
                        const newText = currentText + tok.replace(/\\n/g,'\n');
                        ai.setAttribute('data-raw', newText);
                        
                        ai.innerHTML = formatText(newText);
                        msgs.scrollTop=msgs.scrollHeight;
                    }
                }
                
                // Add actions after streaming completes
                const raw = ai.getAttribute('data-raw');
                if (raw) {
                    let speed = 0;
                    if (startTime) {
                        const duration = (Date.now() - startTime) / 1000;
                        speed = duration > 0 ? (tokenCount / duration).toFixed(1) : 0;
                    }
                    addActionsToMessage(ai, raw, speed);
                }
                
            }catch(e){ai.className='msg s';ai.textContent='Error: '+e.message;}
            finally{if(ready){inp.disabled=false;btn.disabled=false;inp.focus();}msgs.scrollTop=msgs.scrollHeight;}
        }

        btn.addEventListener('click', () => send(false));
        polishBtn.addEventListener('click', () => send(true));
        inp.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') send(false);
        });

        function formatText(t) {
            let processed = t.trimStart();
            
            // Suppress literal prefixes at the start
            const prefixes = ["Thinking...", "Thinking:", "Thought:", "Thought..."];
            let changed = true;
            while(changed) {
                changed = false;
                for(const p of prefixes) {
                    if(processed.toLowerCase().startsWith(p.toLowerCase())) {
                        processed = processed.substring(p.length).trimStart();
                        changed = true;
                    }
                }
            }

            // Handle CREATE_FILE tags
            const createRegex = /\[CREATE_FILE\s+path="([^"]+)"\]([\s\S]*?)\[\/CREATE_FILE\]/g;
            processed = processed.replace(createRegex, (match, path, content) => {
                const escapedContent = content.replace(/</g, '&lt;').replace(/>/g, '&gt;');
                return `<div style="background:var(--surface-container); border:1px solid var(--primary); border-radius:12px; margin:12px 0; overflow:hidden;">
                    <div style="background:rgba(208, 188, 255, 0.1); padding:8px 12px; display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border);">
                        <span style="font-size:12px; font-weight:600; color:var(--primary);">CREATE: ${'$'}{path}</span>
                        <div style="display:flex; gap:8px;">
                            <button onclick="copyToClipboard(this)" data-code="${'$'}{btoa(unescape(encodeURIComponent(content)))}" style="background:none; border:none; color:var(--primary); cursor:pointer;">Copy</button>
                        </div>
                    </div>
                    <pre style="padding:12px; margin:0; font-family:monospace; font-size:13px; overflow-x:auto; color:var(--fg);">${'$'}{escapedContent.trim()}</pre>
                </div>`;
            });

            // Handle Code Blocks
            const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
            processed = processed.replace(codeBlockRegex, (match, lang, code) => {
                const langName = lang.toUpperCase() || 'CODE';
                const escapedCode = code.replace(/</g, '&lt;').replace(/>/g, '&gt;');
                return `<div class="code-block" style="background:rgba(0,0,0,0.2); border:1px solid var(--border); border-radius:12px; margin:12px 0; overflow:hidden;">
                    <div style="background:rgba(255,255,255,0.05); padding:6px 12px; display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border);">
                        <span style="font-size:10px; font-weight:bold; color:var(--primary);">${'$'}{langName}</span>
                        <button onclick="copyToClipboard(this)" data-code="${'$'}{btoa(unescape(encodeURIComponent(code)))}" style="background:none; border:none; color:var(--primary); cursor:pointer; padding:4px;">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                        </button>
                    </div>
                    <pre style="padding:12px; margin:0; font-family:monospace; font-size:13px; overflow-x:auto; color:var(--fg); line-height:1.4;">${'$'}{escapedCode.trim()}</pre>
                </div>`;
            });
            
            // Handle [GENERATE_FILE] tags
            const fileRegex = /\[GENERATE_FILE\s+type="(\w+)"\s+name="([^"]+)"\]([\s\S]*?)\[\/GENERATE_FILE\]/g;
            processed = processed.replace(fileRegex, (match, type, name, content) => {
                return `<div style="background:var(--surface-container); border:1px solid var(--border); border-radius:12px; padding:12px; margin:12px 0; display:flex; align-items:center; gap:12px; color:var(--fg); text-align:left;">
                    <div style="background:var(--primary); color:var(--on-primary); width:40px; height:40px; border-radius:8px; display:flex; align-items:center; justify-content:center; flex-shrink:0;">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><polyline points="9 15 12 18 15 15"/>
                        </svg>
                    </div>
                    <div style="flex:1; min-width:0;">
                        <div style="font-weight:500; font-size:14px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${'$'}{name}</div>
                        <div style="font-size:11px; color:var(--muted);">${'$'}{type.toUpperCase()} Document</div>
                    </div>
                    <a href="/download/${'$'}{name}?key=${'$'}{apiKeyParam}" download="${'$'}{name}" style="background:var(--primary); color:var(--on-primary); padding:6px 16px; border-radius:100px; text-decoration:none; font-size:12px; font-weight:600; white-space:nowrap;">Download</a>
                </div>`;
            });

            return escapeHtml(processed);
        }
        
        function escapeHtml(text) {
            const hasCustomTags = text.includes('<div style="background:var(--surface-container)') || text.includes('<div class="code-block"');
            
            let processed = text;
            if (!hasCustomTags) {
                processed = text
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;');
            }

            return processed
                .replace(/^> (.*$)/gm, '<blockquote style="border-left: 4px solid var(--primary); padding-left: 12px; margin: 12px 0; color: var(--muted); font-style: italic;">$1</blockquote>')
                .replace(/^(\*\*\*|---|___)$/gm, '<hr style="border: 0; border-top: 1px solid var(--border); margin: 16px 0;">')
                .replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
                .replace(/`(.*?)`/g, '<code style="background:rgba(255,255,255,0.1); padding:2px 4px; border-radius:4px; font-family:monospace;">$1</code>')
                .replace(/^### (.*$)/gm, '<h3 style="margin:16px 0 8px 0; color:var(--fg); font-weight:600;">$1</h3>')
                .replace(/^## (.*$)/gm, '<h2 style="margin:20px 0 10px 0; color:var(--fg); font-weight:700;">$1</h2>')
                .replace(/^# (.*$)/gm, '<h1 style="margin:24px 0 12px 0; color:var(--fg); font-weight:800;">$1</h1>')
                .replace(/^- (.*$)/gm, '<li style="margin-left:16px; color:var(--muted); list-style-type: disc;">$1</li>')
                .replace(/\n/g, '<br>');
        }

        window.copyToClipboard = function(btn) {
            const code = decodeURIComponent(escape(atob(btn.getAttribute('data-code'))));
            navigator.clipboard.writeText(code);
            const original = btn.innerHTML;
            btn.innerHTML = '<span style="font-size:10px; color:var(--primary);">Copied!</span>';
            setTimeout(() => { btn.innerHTML = original; }, 1500);
        };

        function addMsg(t,c){
            const container = document.createElement('div');
            container.className = 'msg-container ' + c;
            
            const d=document.createElement('div');
            d.className='msg '+c;
            if (c === 's') {
                d.textContent = t;
            } else {
                d.setAttribute('data-raw', t);
                d.innerHTML = formatText(t);
            }
            
            container.appendChild(d);

            // Add TTS Player UI to the container (initially hidden)
            if (c === 'a') {
                const player = document.createElement('div');
                player.className = 'tts-player';
                player.innerHTML = `
                    <button class="tts-btn" onclick="togglePlayPause()">
                        <svg class="play-pause-icon" width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M5 3l14 9-14 9V3z"/></svg>
                    </button>
                    <div class="tts-progress-container">
                        <div class="tts-progress-bar"></div>
                    </div>
                    <button class="tts-btn" onclick="stopTTS()">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12"/></svg>
                    </button>
                `;
                container.appendChild(player);
                d.ttsPlayer = player;
            }

            msgs.appendChild(container);
            msgs.scrollTop=msgs.scrollHeight;
            
            if (c === 'a' && t !== 'Thinking…') {
                addActionsToMessage(d, t, 0);
            }
            
            return d;
        }
        
        function addActionsToMessage(msgDiv, text, speed) {
            const container = msgDiv.parentElement;
            if (!container) return;
            
            const actions = document.createElement('div');
            actions.className = 'actions';
            
            if (speed) {
                const rt = msgDiv.getAttribute('data-rt');
                const speedLabel = document.createElement('span');
                speedLabel.className = 'speed-label';
                let labelText = speed + ' tok/s';
                if (rt) labelText += ' | ' + (rt/1000).toFixed(2) + 's';
                speedLabel.textContent = labelText;
                actions.appendChild(speedLabel);
            }
            
            const copyBtn = document.createElement('button');
            copyBtn.className = 'action-btn';
            copyBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`;
            copyBtn.onclick = () => {
                navigator.clipboard.writeText(text);
                const originalContent = copyBtn.innerHTML;
                copyBtn.innerHTML = `<span style="font-size:10px;color:var(--primary);">Copied!</span>`;
                setTimeout(() => { copyBtn.innerHTML = originalContent; }, 1500);
            };
            
            const shareBtn = document.createElement('button');
            shareBtn.className = 'action-btn';
            shareBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.41" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>`;
            shareBtn.onclick = () => {
                if (navigator.share) {
                    navigator.share({ text: text });
                } else {
                    alert('Sharing not supported on this browser. Text copied instead!');
                    navigator.clipboard.writeText(text);
                }
            };
            
            const speakBtn = document.createElement('button');
            speakBtn.className = 'action-btn';
            speakBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4V5z"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>`;
            speakBtn.onclick = () => {
                if (msgDiv.ttsPlayer) {
                    speakText(text, msgDiv.ttsPlayer);
                }
            };

            actions.appendChild(copyBtn);
            actions.appendChild(shareBtn);
            actions.appendChild(speakBtn);
            container.appendChild(actions);
        }

        // Session Management
        const sessionSel = document.getElementById('session-sel');
        
        // Populate sessions
        initialSessions.forEach(sid => {
            const opt = document.createElement('option');
            opt.value = sid;
            opt.textContent = sid.substring(0, 8) + '...';
            if (sid === currentSessionId) opt.selected = true;
            sessionSel.appendChild(opt);
        });
        
        sessionSel.onchange = () => {
            const val = sessionSel.value;
            currentSessionId = val;
            localStorage.setItem('currentSessionId', currentSessionId);
            loadChatHistory(val);
        };

        if (initialSessions.includes(currentSessionId)) {
            loadChatHistory(currentSessionId);
        }
        
        function loadChatHistory(sid) {
            msgs.innerHTML = '<div class="think-loader">⚡ Loading history...</div>';
            fetch(`/chat/history/` + sid + (apiKeyParam ? `?key=` + apiKeyParam : ''))
                .then(r => r.json())
                .then(data => {
                    msgs.innerHTML = '';
                    if (data.length === 0) {
                        addMsg('No messages in this chat.', 's');
                    } else {
                        data.forEach(m => {
                            addMsg(m.text, m.role === 'user' ? 'u' : 'a');
                        });
                    }
                })
                .catch(e => {
                    msgs.innerHTML = '';
                    addMsg('Failed to load history.', 's');
                });
        }

        // Name Prompt Logic
        let userName = localStorage.getItem('userName') || savedName;
        if (!userName && !hasConnectedEarlier) {
            nameModal.style.display = 'flex';
            nameBtn.onclick = () => {
                const val = nameInp.value.trim();
                if (val) {
                    localStorage.setItem('userName', val);
                    nameModal.style.display = 'none';
                    registerName(val);
                    addMsg(`Welcome, ${'$'}{val}! 👋 How can I help you today?`, 's');
                }
            };
            nameInp.addEventListener('keypress', e => { if (e.key === 'Enter') nameBtn.click(); });
        } else {
            if (userName) {
                localStorage.setItem('userName', userName);
                registerName(userName);
                if (initialSessions.length === 0) {
                    addMsg(`Welcome back, ${'$'}{userName}! 👋 How can I help you today?`, 's');
                }
            } else {
                registerName("Device"); // fallback
            }
        }
        
        async function registerName(name) {
            try {
                await fetch('/register?key='+apiKeyParam, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({name: name})
                });
            } catch(e) { console.error(e); }
        }

        // Heartbeat to keep connection active
        setInterval(() => {
            fetch('/ping' + (apiKeyParam ? `?key=` + apiKeyParam : ''))
                .catch(e => console.log('Heartbeat failed'));
        }, 10000);

        btn.onclick=send;
        inp.addEventListener('keypress',e=>{if(e.key==='Enter'&&!btn.disabled)send();});
    </script>
</body>
</html>""".trimIndent()

    private fun buildMaxLimitReachedHtml(limit: Int) = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Limit Reached - Chhanda</title>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <style>
        body {
            font-family: 'Roboto', sans-serif;
            background: #141218;
            color: #E6E1E5;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            margin: 0;
            text-align: center;
        }
        .card {
            background: #1D1B20;
            border: 1px solid #F2B8B5; /* Soft error red */
            border-radius: 28px;
            padding: 40px;
            max-width: 400px;
            width: 90%;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
        }
        .icon {
            font-size: 64px;
            margin-bottom: 24px;
            display: block;
        }
        h1 { font-size: 24px; margin-bottom: 16px; color: #F2B8B5; }
        p { color: #CAC4D0; line-height: 1.6; margin-bottom: 24px; }
        .hint {
            background: #211F26;
            padding: 12px;
            border-radius: 12px;
            font-size: 13px;
            border: 1px dashed #F2B8B5;
            color: #F2B8B5;
        }
    </style>
</head>
<body>
    <div class="card">
        <span class="icon">🚫</span>
        <h1>Device Limit Reached</h1>
        <p>The maximum limit of <b>$limit devices</b> has been reached for this Chhanda gateway.</p>
        <div class="hint">
            Please increase the limit or remove active devices from the <b>Chhanda Host Application</b> settings.
        </div>
    </div>
</body>
</html>""".trimIndent()

    private fun buildAccessDeniedHtml() = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Access Denied - Chhanda</title>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <style>
        body {
            font-family: 'Roboto', sans-serif;
            background: #141218;
            color: #E6E1E5;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            margin: 0;
            text-align: center;
        }
        .card {
            background: #1D1B20;
            border: 1px solid #49454F;
            border-radius: 28px;
            padding: 40px;
            max-width: 400px;
            width: 90%;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
        }
        .icon {
            font-size: 64px;
            margin-bottom: 24px;
            display: block;
        }
        h1 { font-size: 24px; margin-bottom: 16px; color: #D0BCFF; }
        p { color: #CAC4D0; line-height: 1.6; margin-bottom: 24px; }
        .hint {
            background: #211F26;
            padding: 12px;
            border-radius: 12px;
            font-size: 13px;
            border: 1px dashed #49454F;
        }
    </style>
</head>
<body>
    <div class="card">
        <span class="icon">🔒</span>
        <h1>Secure Access Required</h1>
        <p>To use this AI gateway, please scan the <b>QR Code</b> displayed on the host device or use the full URL provided in the sharing settings.</p>
        <div class="hint">
            Unauthorized access is blocked to ensure network privacy.
        </div>
    </div>
</body>
</html>""".trimIndent()
}
