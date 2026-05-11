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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import java.net.NetworkInterface
import java.net.Socket
import java.net.ServerSocket

@Serializable
data class WebMessage(val text: String, val role: String = "user")

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
    private val sendMessageUseCaseLazy: dagger.Lazy<SendMessageUseCase>
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val sendMessageUseCase get() = sendMessageUseCaseLazy.get()
    @Volatile private var server: CIOApplicationEngine? = null
    @Volatile private var boundPort: Int = -1
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

    private fun refreshNetworkStatus() {
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
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.contains("tun") || name.contains("ppp") || name.contains("vpn")) vpnFound = true
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(name to addr.hostAddress!!)
                    }
                }
            }
        } catch (_: Exception) {}
        val sortedIps = ips.sortedWith(compareBy { (name, _) ->
            when {
                name.startsWith("wlan") -> 0
                name.startsWith("ap") || name.startsWith("softap") -> 1
                name.startsWith("eth") -> 2
                name.startsWith("rndis") -> 3
                else -> 4
            }
        }).map { it.second }.distinct()
        return Triple(sortedIps.firstOrNull(), sortedIps, vpnFound)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start(requestedPort: Int, @Suppress("UNUSED_PARAMETER") maxDevices: Int) {
        stop()
        startTime = System.currentTimeMillis()
        lastIpRefresh = 0L
        val ip = freshIp()

        if (ip == "127.0.0.1") {
            Log.w(TAG, "No WiFi detected; server will be loopback-only.")
        }

        // Force Android to keep the network interface active (Critical for SIM-less)
        try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm?.requestNetwork(request, object : android.net.ConnectivityManager.NetworkCallback() {})
        } catch (_: Exception) {}

        _serverErrorFlow.value = null
        for (port in requestedPort..requestedPort + 10) {
            Log.i(TAG, "Trying CIO on 0.0.0.0:$port …")
            try {
                val engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    configureEngine(this, port)
                }
                engine.start(wait = false)

                // PRO PROBE: Verify via loopback OR current detected IP
                var ok = false
                val probeIps = listOf("127.0.0.1", ip)
                
                outer@for (pIp in probeIps) {
                    val probeUrl = java.net.URL("http://$pIp:$port/ping")
                    Log.d(TAG, "Probing server on $pIp:$port...")
                    for (i in 1..20) { // Up to 4 seconds total
                        Thread.sleep(200)
                        try {
                            val connection = probeUrl.openConnection() as java.net.HttpURLConnection
                            connection.connectTimeout = 400
                            connection.readTimeout = 400
                            val text = connection.inputStream.bufferedReader().readText()
                            if (text == "pong") {
                                ok = true
                                Log.i(TAG, "Probe successful on $pIp")
                                break@outer
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (ok) {
                    server = engine
                    boundPort = port
                    _boundPortFlow.value = port
                    Log.i(TAG, "✅ Server Verified: http://$ip:$port")
                    return
                } else {
                    // SENIOR FIX: If we can't probe it but no exception was thrown, 
                    // it might just be local network isolation. Start it anyway as fallback.
                    Log.w(TAG, "Port $port: probe timed out. Starting in unverified mode.")
                    server = engine
                    boundPort = port
                    _boundPortFlow.value = port
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Port $port startup error: ${e.message}")
                _serverErrorFlow.value = "Port $port: ${e.message}"
            }
        }
        _serverErrorFlow.value = "CRITICAL: No port bound in range $requestedPort..${requestedPort + 10}"
        Log.e(TAG, _serverErrorFlow.value!!)
    }

    fun stop() {
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

                // /ping — bare minimum, useful for connectivity check
                get("/ping") { call.respondText("pong") }

                // /status — < 1ms, uses cached IP
                get("/status") {
                    val loaded    = llmEngine.isModelLoaded()
                    val uptime    = (System.currentTimeMillis() - startTime) / 1000
                    val addr      = freshIp()
                    val liteRt    = llmEngine as? LiteRTLMEngine
                    val isLoading = liteRt?.isLoading ?: !loaded
                    val loadErr   = (liteRt?.lastLoadError ?: "").replace("\"", "'")
                    call.respondText(
                        """{"ok":true,"modelLoaded":$loaded,"isLoading":$isLoading,"loadError":"$loadErr","ip":"$addr","port":$capturedPort,"uptime":$uptime}""",
                        io.ktor.http.ContentType.Application.Json
                    )
                }

                // / — Chat UI — no-store prevents stale cached page
                get("/") {
                    val remoteIp = call.request.local.remoteHost
                    val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                    trackDevice(remoteIp, userAgent)
                    
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respondText(
                        buildChatHtml(capturedPort, freshIp()),
                        io.ktor.http.ContentType.Text.Html
                    )
                }

                post("/chat") {
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
                            sendMessageUseCase(msg.text, remoteIp, llmEngine.getCurrentModelName(), "api_session").collect { upd ->
                                when (upd) {
                                    is TokenUpdate.Partial -> { write("data: ${upd.text.replace("\n","\\n")}\n\n"); flush() }
                                    is TokenUpdate.Final   -> { write("data: [DONE]\n\n"); flush() }
                                    is TokenUpdate.Error   -> { write("data: ERR:${upd.message}\n\n"); flush() }
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

    private fun trackDevice(ip: String, userAgent: String = "Remote Client") {
        if (ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1") return

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val existing = deviceDao.getDeviceByIp(ip)
                if (existing == null) {
                    val fallbackName = "Device"
                    val parsedName = when {
                        userAgent.contains("iPhone") -> "iPhone"
                        userAgent.contains("iPad") -> "iPad"
                        userAgent.contains("Android") -> "Android"
                        userAgent.contains("Windows") -> "Windows PC"
                        userAgent.contains("Macintosh") -> "Mac"
                        else -> fallbackName
                    }
                    val lastOctet = ip.split(".").lastOrNull() ?: ip.takeLast(3)
                    val uniqueName = "$parsedName ($lastOctet)"
                    
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
            } catch (e: Exception) {
                Log.w(TAG, "Device tracking failure for $ip: ${e.message}")
            }
        }
    }

    // ── IP Util ────────────────────────────────────────────────────────────────


    // ── HTML ───────────────────────────────────────────────────────────────────

    private fun buildChatHtml(port: Int, ip: String) = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Chhanda AI</title>
    <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500;700&display=swap" rel="stylesheet">
    <style>
        :root {
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
            font-family: 'Roboto', -apple-system, BlinkMacSystemFont, sans-serif;
            background: var(--bg);
            color: var(--fg);
            display: flex;
            flex-direction: column;
        }
        
        #hdr {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 12px 16px;
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            flex-shrink: 0;
            z-index: 10;
        }
        
        #logo {
            width: 40px;
            height: 40px;
            border-radius: 12px;
            background: var(--primary);
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
            font-size: 18px;
            color: var(--on-primary);
        }
        
        #title {
            font-weight: 500;
            font-size: 20px;
            color: var(--fg);
        }
        
        #badge {
            margin-left: auto;
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 12px;
            font-weight: 500;
            padding: 6px 16px;
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
        
        .msg {
            max-width: 80%;
            padding: 12px 16px;
            border-radius: 20px;
            font-size: 15px;
            line-height: 1.5;
            word-break: break-word;
            animation: slideUp 0.2s cubic-bezier(0, 0, 0.2, 1);
        }
        
        @keyframes slideUp {
            from { opacity: 0; transform: translateY(8px); }
            to { opacity: 1; transform: translateY(0); }
        }
        
        .u {
            align-self: flex-end;
            background: var(--user-bubble);
            color: #fff;
            border-bottom-right-radius: 4px;
        }
        
        .a {
            align-self: flex-start;
            background: var(--ai-bubble);
            color: var(--fg);
            border-bottom-left-radius: 4px;
            border: 1px solid var(--border);
        }
        
        .s {
            align-self: center;
            background: transparent;
            border: 1px dashed var(--border);
            color: var(--muted);
            font-size: 13px;
            max-width: 90%;
            padding: 6px 12px;
            border-radius: 8px;
            text-align: center;
        }
        
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
        
        @media (max-width: 600px) {
            .msg { max-width: 85%; }
        }
    </style>
</head>
<body>
    <div id="hdr">
        <div id="logo">C</div>
        <span id="title">Chhanda AI</span>
        <div id="badge"><div id="dot"></div><span id="bt">CONNECTING</span></div>
    </div>
    <div id="msgs">
        <div class="msg s">Connection established with Node at ${ip}:${port}</div>
    </div>
    <div id="ftr">
        <div id="row">
            <input id="inp" placeholder="Waiting for AI…" autocomplete="off" disabled>
            <button id="btn" disabled>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
                </svg>
            </button>
        </div>
    </div>
    <div id="ovl">
        <div id="crd">
            <h2>⚠️ Cannot Reach Node</h2>
            <p>Node at <code>${ip}:${port}</code> is not responding.<br><br>
            <b>Troubleshooting:</b><br>
            • Ensure both devices share the SAME Wi-Fi.<br>
            • <b>iPhone:</b> Turn off "Private Relay" or "Limit IP Address Tracking" in Wi-Fi settings.<br>
            • Ensure Chhanda app is active on the phone.</p>
            <button id="rtry" onclick="location.reload()">Retry Connection</button>
        </div>
    </div>
    <script>
        const msgs=document.getElementById('msgs'),inp=document.getElementById('inp'),
              btn=document.getElementById('btn'),badge=document.getElementById('badge'),
              bt=document.getElementById('bt'),ovl=document.getElementById('ovl');
        let ready=false,fails=0,errShown=false;
        const MAX=12;

        async function pulse(){
            try{
                const c=new AbortController();
                setTimeout(()=>c.abort(),5000);
                const r=await fetch('/status?t='+Date.now(),{signal:c.signal, cache:'no-store'});
                if(!r.ok)throw new Error('HTTP '+r.status);
                const d=await r.json();
                fails=0; ovl.style.display='none'; ready=d.modelLoaded;
                if(ready){
                    badge.className='on';bt.textContent='ONLINE';
                    inp.disabled=false;btn.disabled=false;inp.placeholder='Message Chhanda…';
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
                if(fails>=MAX)ovl.style.display='flex';
            }
        }

        pulse(); setInterval(pulse,2000);

        async function send(){
            if(!ready)return;
            const txt=inp.value.trim();if(!txt)return;
            inp.value='';inp.disabled=true;btn.disabled=true;
            addMsg(txt,'u');const ai=addMsg('Thinking…','a');
            try{
                const res=await fetch('/chat',{method:'POST',
                    headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({text:txt,role:'user'})});
                if(res.status===503){ai.className='msg s';ai.textContent='Model loading — try again.';return;}
                if(!res.ok){ai.className='msg s';ai.textContent='Error '+res.status;return;}
                ai.textContent=''; // Clear thinking state
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
                        
                        const currentText = ai.getAttribute('data-raw') || '';
                        const newText = currentText + tok.replace(/\\n/g,'\n');
                        ai.setAttribute('data-raw', newText);
                        
                        ai.innerHTML = formatText(newText);
                        msgs.scrollTop=msgs.scrollHeight;
                    }
                }
            }catch(e){ai.className='msg s';ai.textContent='Error: '+e.message;}
            finally{if(ready){inp.disabled=false;btn.disabled=false;inp.focus();}msgs.scrollTop=msgs.scrollHeight;}
        }

        function formatText(t) {
            return t
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
                .replace(/`(.*?)`/g, '<code>$1</code>')
                .replace(/\n/g, '<br>');
        }

        function addMsg(t,c){
            const d=document.createElement('div');
            d.className='msg '+c;
            if (c === 's') {
                d.textContent = t;
            } else {
                d.setAttribute('data-raw', t);
                d.innerHTML = formatText(t);
            }
            msgs.appendChild(d);
            msgs.scrollTop=msgs.scrollHeight;
            return d;
        }

        btn.onclick=send;
        inp.addEventListener('keypress',e=>{if(e.key==='Enter'&&!btn.disabled)send();});
    </script>
</body>
</html>""".trimIndent()

}
