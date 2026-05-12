package com.chhanda.ai.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class HotspotManager(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    interface HotspotCallback {
        fun onStarted(ssid: String, pass: String)
        fun onFailed(error: String)
        fun onStopped()
    }

    @SuppressLint("MissingPermission")
    fun startHotspot(callback: HotspotCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onFailed("Hotspot API requires Android 8.0+")
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val config = reservation?.softApConfiguration
                            if (config != null) {
                                callback.onStarted(config.ssid ?: "Chhanda-AI", config.passphrase ?: "")
                            } else {
                                callback.onFailed("Failed to get hotspot config (R+)")
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val config = reservation?.wifiConfiguration
                            if (config != null) {
                                callback.onStarted(config.SSID ?: "Chhanda-AI", config.preSharedKey ?: "")
                            } else {
                                callback.onFailed("Failed to get hotspot config (<R)")
                            }
                        }
                    } catch (e: Exception) {
                        callback.onFailed("Error extracting config: ${e.message}")
                    }
                }

                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                    callback.onStopped()
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    hotspotReservation = null
                    val error = when (reason) {
                        ERROR_NO_CHANNEL -> "No channel available"
                        ERROR_GENERIC -> "Generic error"
                        ERROR_INCOMPATIBLE_MODE -> "Incompatible mode"
                        ERROR_TETHERING_DISALLOWED -> "Tethering disallowed"
                        else -> "Unknown error: $reason"
                    }
                    callback.onFailed(error)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            callback.onFailed(e.localizedMessage ?: "Unknown error")
        }
    }

    fun stopHotspot() {
        try {
            hotspotReservation?.close()
        } catch (e: Exception) {
            Log.w("HotspotManager", "Error closing hotspot: ${e.message}")
        }
        hotspotReservation = null
    }

    fun isHotspotActive(): Boolean = hotspotReservation != null
}
