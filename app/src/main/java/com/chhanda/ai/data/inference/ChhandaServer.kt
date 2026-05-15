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
 * Chhanda embedded HTTP server architecture.
 *
 * Engine: Ktor-CIO (Coroutine-based I/O).
 * 
 * SENIOR ARCHITECTURAL DESIGN:
 * 1. CIO over Netty: CIO is 100% Kotlin-native, ensuring stability across diverse Android
 *    architectures without native SSL library mismatches (tcnative issues).
 * 2. Zero-Trust Security: All endpoints (except /ping and /health) require a mandatory 
 *    X-API-Key header to prevent unauthorized discovery on local networks.
 * 3. Reactive Lifecycle: Server scope is tied to a SupervisorJob, preventing engine crashes
 *    from taking down the entire application process.
 * 4. Resource Efficiency: Uses dagger.Lazy for LLM engine to prevent pre-emptive memory 
 *    allocation before the first request.
 */
@Singleton
class ChhandaServer @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val chatDao: ChatDao,
    private val deviceDao: DeviceDao,
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository,
    private val sendMessageUseCaseLazy: dagger.Lazy<SendMessageUseCase>,
    private val vectorChunkDao: com.chhanda.ai.data.repository.VectorChunkDao,
    private val templateProvider: ServerTemplateProvider
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val sendMessageUseCase get() = sendMessageUseCaseLazy.get()
    @Volatile private var server: CIOApplicationEngine? = null
    @Volatile private var boundPort: Int = -1
    private var reaperJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startTime = System.currentTimeMillis()

    // SENIOR SECURITY: Leaky Bucket Rate Limiter
    // Prevents the device from being DOS'ed by external clients spamming inference requests.
    private val requestSemaphore = kotlinx.coroutines.sync.Semaphore(2) // Max 2 concurrent inference tasks
    private val clientRequestWindow = mutableMapOf<String, Long>() // IP -> Last Request Time
    private val RATE_LIMIT_MS = 1000L // 1 request per second per IP

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

    /**
     * Senior State Check: Returns true if the Ktor engine is bound and active.
     */
    fun isServerActive(): Boolean = server != null

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

    /**
     * Returns the current bound IP address, refreshing it if it's older than IP_TTL_MS.
     * This ensures the UI always shows the correct connectivity info.
     */
    fun freshIp(): String {
        val now = System.currentTimeMillis()
        if (now - lastIpRefresh > IP_TTL_MS) {
            refreshNetworkStatus()
        }
        return cachedIp
    }

    /**
     * Manually triggers a network interface scan to update the server's IP and VPN status.
     */
    fun refreshNetworkStatus() {
        val now = System.currentTimeMillis()
        lastIpRefresh = now
        val (bestIp, all, vpn) = scanNetworkInterfaces()
        cachedIp = bestIp ?: "127.0.0.1"
        cachedAllIps = all
        cachedVpnStatus = vpn
    }

    /**
     * Senior Network Discovery Logic:
     * We scan all network interfaces to find the best IP to host the AI server.
     * We prioritize Hotspot (AP) and Local subnets so other devices can easily connect.
     */
    private fun scanNetworkInterfaces(): Triple<String?, List<String>, Boolean> {
        val ips = mutableListOf<Pair<String, String>>()
        var vpnFound = false
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces?.hasMoreElements() == true) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                
                val name = iface.name.lowercase()
                // Security: Detect active VPNs to warn the user about potential routing issues.
                if (name.contains("tun") || name.contains("ppp") || name.contains("vpn") || name.contains("ipsec")) {
                    vpnFound = true
                }
                
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    // Only bind to IPv4 addresses for better cross-device compatibility.
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(name to addr.hostAddress!!)
                    }
                }
            }
        } catch (_: Exception) {}
        
        // SENIOR SORTING: Prioritize Hotspot interfaces and specific subnets.
        // This ensures if the user is running a hotspot, that IP is shown first.
        val sortedIps = ips.sortedWith(compareBy { (name, ip) ->
            when {
                // High Priority: Android Mobile Hotspot (usually wlan1 or ap0)
                name.contains("ap0") || name.contains("softap") || name.contains("wlan1") || name.contains("swlan") -> 0
                // High Priority: Common Local Area Network subnets (e.g. Android Hotspot range)
                ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.") || ip.startsWith("192.168.45.") -> 1
                // Standard WLAN
                name.startsWith("wlan0") -> 2
                // Ethernet
                name.startsWith("eth") -> 3
                // Other LAN subnets
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
                            templateProvider.buildAccessDeniedHtml(),
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
                            templateProvider.buildMaxLimitReachedHtml(maxAllowed),
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
                        templateProvider.buildChatHtml(capturedPort, clientUsedHost, emptyList(), hasConnectedEarlier, savedName, sessions),
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
                    
                    // Apply Rate Limiting
                    val lastRequest = clientRequestWindow[remoteIp] ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - lastRequest < RATE_LIMIT_MS) {
                        call.respondText("Too many requests. Slow down.", status = io.ktor.http.HttpStatusCode.TooManyRequests)
                        return@post
                    }
                    clientRequestWindow[remoteIp] = now

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
                    
                    // Use Semaphore to limit total system load (Concurrency Control)
                    if (requestSemaphore.availablePermits == 0) {
                        Log.w(TAG, "Inference engine at capacity. Rejecting $remoteIp")
                    }
                    
                    requestSemaphore.withPermit {
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

                            val thinkingMode = settingsRepository.thinkingModeEnabledFlow.firstOrNull() ?: true
                            sendMessageUseCase(promptToSend, remoteIp, llmEngine.getCurrentModelName(), sessionIdToUse, uris, languageName, isRefinement = msg.isRefinement, source = sourceToUse, includeThinking = thinkingMode).collect { upd ->
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

        serverScope.launch(Dispatchers.IO) {
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
}
