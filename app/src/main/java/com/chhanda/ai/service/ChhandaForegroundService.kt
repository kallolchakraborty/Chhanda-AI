package com.chhanda.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chhanda.ai.MainActivity
import com.chhanda.ai.data.inference.ChhandaServer
import com.chhanda.ai.data.inference.ServerOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Chhanda Foreground Service — Fail-proof v9.
 *
 * Key invariants:
 * 1. startForeground() is ALWAYS the first call to prevent ANR.
 * 2. Server start is SYNCHRONOUS — no coroutine delay that can be cancelled.
 * 3. START_STICKY null-intent is handled: if Android restarts us, we restart the server.
 * 4. NsdManager is guarded by nsdRegistered flag to prevent ALREADY_ACTIVE crash.
 * 5. No ConnectivityManager callbacks — 0.0.0.0 binding handles all interface transitions.
 */
@AndroidEntryPoint
class ChhandaForegroundService : Service() {

    @Inject
    lateinit var chhandaServer: ChhandaServer

    @Inject
    lateinit var serverOrchestrator: ServerOrchestrator

    private var currentPort: Int = 8080
    private var currentMaxDevices: Int = 5
    private var nsdRegistered = false
    private var serviceStarting = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var nsdManager: NsdManager? = null

    companion object {
        private const val TAG = "ChhandaService"
        private const val CHANNEL_ID = "chhanda_node_v9"
        private const val NOTIFICATION_ID = 9009
        private const val SERVICE_TYPE = "_http._tcp."
        private const val SERVICE_NAME = "Chhanda-AI-Node"

        const val EXTRA_PORT   = "EXTRA_PORT"
        const val EXTRA_MAX    = "EXTRA_MAX_DEVICES"
        const val EXTRA_SSID   = "EXTRA_SSID"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"
        const val ACTION_STOP_NODE = "ACTION_STOP_NODE"

        fun start(context: Context, port: Int, maxDevices: Int, ssid: String? = null) {
            val intent = Intent(context, ChhandaForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_MAX, maxDevices)
                if (ssid != null) putExtra(EXTRA_SSID, ssid)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ChhandaForegroundService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    // ── NSD listener with state guard ────────────────────────────────────────
    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            nsdRegistered = true
            Log.i(TAG, "mDNS registered: ${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            nsdRegistered = false
            Log.w(TAG, "mDNS failed: $code")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            nsdRegistered = false
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
            nsdRegistered = false
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Chhanda:WakeLock")
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "Chhanda:WifiLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action (intent null=${intent == null})")

        // ── Critical: handle START_STICKY re-delivery (intent == null) ────────
        // Android calls onStartCommand with null intent after killing and restarting
        // a START_STICKY service. We must restart the server here.
        val effectiveAction = action ?: ACTION_START   // treat null as "please restart"

        when (effectiveAction) {
            ACTION_START -> {
                if (serviceStarting) {
                    Log.w(TAG, "Server startup already in progress, ignoring request")
                    return START_STICKY
                }
                serviceStarting = true
                // Restore port from extras if available; keep currentPort if this is a re-delivery
                if (intent != null) {
                    currentPort = intent.getIntExtra(EXTRA_PORT, 8888)
                    currentMaxDevices = intent.getIntExtra(EXTRA_MAX, 5)
                }

                // 1. Post foreground notification FIRST (prevents ANR window)
                val ssid = intent?.getStringExtra(EXTRA_SSID)
                val isHotspot = ssid != null
                val title = if (isHotspot) "Chhanda Hotspot: $ssid" else "Chhanda AI Node"
                val icon = com.chhanda.ai.R.mipmap.ic_launcher
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID, 
                        buildNotification(title, "Listening on :$currentPort", icon),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification(title, "Listening on :$currentPort", icon))
                }

                // 2. Acquire locks
                if (wakeLock?.isHeld == false) wakeLock?.acquire()
                if (wifiLock?.isHeld == false) wifiLock?.acquire()

                // 3. Start server on a background thread.
                Thread({
                    try {
                        chhandaServer.start(currentPort, currentMaxDevices)
                        Log.i(TAG, "Server start complete on port $currentPort")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Server thread crash: ${e.message}", e)
                    } finally {
                        serviceStarting = false
                    }
                }, "chhanda-server-start").start()

                // 4. Register mDNS (best-effort)
                registerMdns(currentPort)
            }

            ACTION_UPDATE -> {
                val ssid = intent?.getStringExtra(EXTRA_SSID)
                val isHotspot = ssid != null
                val title = if (isHotspot) "Chhanda Hotspot: $ssid" else "Chhanda AI Node"
                val content = "Listening on :$currentPort"
                val icon = com.chhanda.ai.R.drawable.ic_chhanda_status
                
                val notification = buildNotification(title, content, icon)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }

            ACTION_STOP_NODE -> {
                Log.i(TAG, "Stop node requested via action")
                shutdown()
            }
 
             ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                shutdown()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Private helpers ───────────────────────────────────────────────────────
    private fun shutdown() {
        unregisterMdns()
        try { chhandaServer.stop() } catch (e: Exception) { Log.w(TAG, "Server stop: ${e.message}") }
        try {
            if (::serverOrchestrator.isInitialized) {
                serverOrchestrator.stopServerFromService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update orchestrator: ${e.message}")
        }
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun registerMdns(port: Int) {
        if (nsdRegistered) return
        try {
            val info = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS register error: ${e.message}")
        }
    }

    private fun unregisterMdns() {
        if (!nsdRegistered) return
        try {
            nsdManager?.unregisterService(nsdListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unregister error: ${e.message}")
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    private fun buildNotification(title: String, content: String, iconRes: Int = com.chhanda.ai.R.mipmap.ic_launcher): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, ChhandaForegroundService::class.java).apply { action = ACTION_STOP_NODE }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(iconRes)
            .setColor(android.graphics.Color.parseColor("#3B82F6"))
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTicker(title)
            .addAction(com.chhanda.ai.R.drawable.ic_chhanda_status, "STOP SERVER", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Chhanda AI Node", NotificationManager.IMPORTANCE_LOW)
                .also { (getSystemService(NotificationManager::class.java)).createNotificationChannel(it) }
        }
    }
}
