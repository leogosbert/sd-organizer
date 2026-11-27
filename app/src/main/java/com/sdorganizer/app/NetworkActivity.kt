package com.sdorganizer.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.activity.result.contract.ActivityResultContracts

/** Live view of mobile signal, network type (2G–5G), Wi-Fi strength and settings that limit speed. */
class NetworkActivity : ListScreen() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }
    private val phonePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Network check"
        addButton("Mobile network settings (set 5G/LTE auto)") {
            openFirst(
                Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            )
        }
        addButton("Wi-Fi settings", outlined = true) { openFirst(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            phonePerm.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun typeName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "No mobile data"
        else -> "Other"
    }

    private fun bars(level: Int) = "▂▄▆█".take(level.coerceIn(0, 4)).padEnd(4, '·')

    private fun refresh() {
        val out = ArrayList<CheckItem>()
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val hasPhonePerm = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        // ---- Mobile
        out.add(CheckItem("📶 Mobile network — ${tm.networkOperatorName.ifEmpty { "no SIM" }}", isHeader = true, checkable = false))
        val type = if (hasPhonePerm) runCatching { typeName(tm.dataNetworkType) }.getOrDefault("Unknown") else "Allow phone access to see this"
        var on5g = type == "5G"
        if (Build.VERSION.SDK_INT >= 29) {
            val ss = tm.signalStrength
            if (ss != null) {
                if (ss.cellSignalStrengths.any { it is CellSignalStrengthNr }) on5g = true
                val main = ss.cellSignalStrengths.maxByOrNull { it.level }
                val dbm = main?.dbm ?: 0
                val quality = when (ss.level) {
                    4 -> "Excellent"; 3 -> "Good"; 2 -> "Fair"; 1 -> "Weak"; else -> "Very weak / none"
                }
                out.add(CheckItem("Signal: ${bars(ss.level)}  $quality", if (dbm != 0 && dbm != Int.MAX_VALUE) "$dbm dBm (closer to -50 is better, below -110 is very weak)" else "", checkable = false))
            }
        }
        out.add(CheckItem("Network: ${if (on5g && type != "5G") "5G (with 4G)" else type}", "", checkable = false))

        val supports5g: String = if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                if ((tm.supportedRadioAccessFamily and TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0L) "Yes" else "No — this phone has no 5G modem"
            }.getOrDefault("Unknown")
        } else "Unknown (Android 12+ needed to check)"
        out.add(CheckItem("Phone supports 5G: $supports5g", if (supports5g.startsWith("Yes") && !on5g) "Not on 5G now: no 5G tower nearby, or network mode is not set to 5G" else "", checkable = false))

        // ---- Wi-Fi
        out.add(CheckItem("📡 Wi-Fi", isHeader = true, checkable = false))
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (onWifi) {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val rssi = info.rssi
            @Suppress("DEPRECATION")
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            val band = when {
                info.frequency >= 5900 -> "6 GHz (fastest, shortest range)"
                info.frequency >= 4900 -> "5 GHz (fast, short range)"
                else -> "2.4 GHz (slower, reaches farthest)"
            }
            out.add(CheckItem("Wi-Fi signal: ${bars(level)}  $rssi dBm", "Above -60 is strong · below -80 is weak", checkable = false))
            out.add(CheckItem("Band: $band", "Link speed ${info.linkSpeed} Mbps", checkable = false))
            if (rssi < -75) {
                out.add(CheckItem("⚠ Weak Wi-Fi", "Move closer to the router, use 2.4 GHz, or add a Wi-Fi extender", checkable = false))
            }
        } else {
            out.add(CheckItem("Not connected to Wi-Fi", "Tap “Wi-Fi settings” below", checkable = false))
        }

        // ---- Speed limits
        out.add(CheckItem("🚀 Things that limit speed", isHeader = true, checkable = false))
        caps?.let {
            out.add(CheckItem("Estimated speed now: ↓ ${it.linkDownstreamBandwidthKbps / 1000} Mbps  ↑ ${it.linkUpstreamBandwidthKbps / 1000} Mbps", "Android's own estimate", checkable = false))
        }
        val dataSaver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        out.add(
            CheckItem(
                if (dataSaver) "⚠ Data Saver is ON" else "✓ Data Saver off",
                if (dataSaver) "Apps are limited in the background. Tap to change." else "",
                checkable = false,
                onClick = { openFirst(Intent("android.settings.DATA_SAVER_SETTINGS"), Intent(Settings.ACTION_DATA_USAGE_SETTINGS)) }
            )
        )
        out.add(
            CheckItem(
                if (pm.isPowerSaveMode) "⚠ Battery saver is ON" else "✓ Battery saver off",
                if (pm.isPowerSaveMode) "It slows the network and the phone. Tap to change." else "",
                checkable = false,
                onClick = { openFirst(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            )
        )

        header.text = "Updates every 2 seconds. Walk around to find the best signal spot."
        val firstVisible = list.firstVisiblePosition
        showItems(out)
        list.setSelection(firstVisible)
    }
}
