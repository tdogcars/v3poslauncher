package com.flo.v3poslauncher.home

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Live Wi-Fi status for the home screen's top-left indicator: connected?, validated internet?,
 * and the SSID when the platform lets us read it (needs ACCESS_FINE_LOCATION, which the Device
 * Owner grants to itself, plus location services on some builds — otherwise the SSID is null
 * and the UI shows a generic "Wi-Fi" label).
 */
class NetworkStatus(private val context: Context, private val onChange: (State) -> Unit) {

    data class State(val connected: Boolean, val internet: Boolean, val ssid: String?)

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    private inner class Cb : ConnectivityManager.NetworkCallback {
        constructor() : super()
        @TargetApi(31) constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            publish(caps)
        }
        override fun onLost(network: Network) {
            publish(null)
        }
    }

    fun start() {
        stop()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb: ConnectivityManager.NetworkCallback =
            if (Build.VERSION.SDK_INT >= 31) Cb(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) else Cb()
        callback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
        // Publish the current state immediately rather than waiting for the first callback.
        publish(runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull())
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun publish(caps: NetworkCapabilities?) {
        val wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val internet = wifi && caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val ssid = if (wifi) readSsid(caps) else null
        val state = State(wifi, internet, ssid)
        main.post { onChange(state) }
    }

    @SuppressLint("MissingPermission")
    private fun readSsid(caps: NetworkCapabilities?): String? {
        var raw: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            raw = (caps?.transportInfo as? WifiInfo)?.ssid
        }
        if (raw.isNullOrEmpty() || raw.contains("unknown", ignoreCase = true)) {
            raw = runCatching {
                @Suppress("DEPRECATION")
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
            }.getOrNull()
        }
        val cleaned = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.trim()
        return cleaned?.takeIf { it.isNotEmpty() && !it.contains("unknown", ignoreCase = true) && it != "0x" }
    }
}
