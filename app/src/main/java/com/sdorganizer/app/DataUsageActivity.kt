package com.sdorganizer.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/** Shows which apps use mobile data in the background (when you are not using them). */
class DataUsageActivity : ListScreen() {

    private class Usage(val uid: Int, var total: Long = 0, var background: Long = 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Background data"
        addButton("Turn on Data Saver (limits all background data)") {
            openFirst(
                Intent("android.settings.DATA_SAVER_SETTINGS"),
                Intent(Settings.ACTION_DATA_USAGE_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Perms.hasUsageAccess(this)) {
            header.text = "To see data use per app, allow usage access for SD Organizer on the next screen."
            showItems(listOf(CheckItem("Allow usage access", "Tap here", checkable = false, onClick = { Perms.openUsageAccess(this) })))
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            header.text = "This report needs Android 10 or newer."
            return
        }
        load()
    }

    private fun load() {
        busy("Reading data use for the last 30 days…")
        bg({
            val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val end = System.currentTimeMillis()
            val start = end - 30 * DAY_MS
            val map = HashMap<Int, Usage>()
            @Suppress("DEPRECATION")
            val stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                val u = map.getOrPut(b.uid) { Usage(b.uid) }
                val bytes = b.rxBytes + b.txBytes
                u.total += bytes
                if (b.state != NetworkStats.Bucket.STATE_FOREGROUND) u.background += bytes
            }
            stats.close()
            map.values.filter { it.total > 0 }.sortedByDescending { it.background }
        }) { list ->
            val pm = packageManager
            val total = list.sumOf { it.total }
            val bgTotal = list.sumOf { it.background }
            header.text = "Last 30 days on mobile data: ${StorageInfo.fmt(total)} used, " +
                    "${StorageInfo.fmt(bgTotal)} of it in the background.\n" +
                    "Tap an app → Mobile data → turn off “Allow background data usage”."
            showItems(list.take(60).map { u ->
                val pkgs = pm.getPackagesForUid(u.uid)
                val pkg = pkgs?.firstOrNull()
                val name = when {
                    u.uid == NetworkStats.Bucket.UID_REMOVED -> "Apps you uninstalled"
                    u.uid == NetworkStats.Bucket.UID_TETHERING -> "Hotspot / tethering"
                    pkg == null -> "System (uid ${u.uid})"
                    else -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                }
                CheckItem(
                    "$name · ${StorageInfo.fmt(u.background)} in background",
                    "Total ${StorageInfo.fmt(u.total)} in 30 days" + if (pkg != null) " · tap to limit" else "",
                    checkable = false,
                    onClick = if (pkg != null) ({ openAppDetails(pkg) }) else null
                )
            })
        }
    }
}
