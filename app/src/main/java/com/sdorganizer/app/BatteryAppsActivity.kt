package com.sdorganizer.app

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/** Shows apps that keep running in the background, with a shortcut to put them to sleep. */
class BatteryAppsActivity : ListScreen() {

    private class AppUse(val pkg: String, val name: String, val visible: Long, val background: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Battery-hungry apps"
        addButton("Open sleeping apps settings") {
            openFirst(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Perms.hasUsageAccess(this)) {
            header.text = "To see which apps run the most, allow usage access for SD Organizer on the next screen."
            showItems(listOf(CheckItem("Allow usage access", "Tap here", checkable = false, onClick = { Perms.openUsageAccess(this) })))
            return
        }
        load()
    }

    private fun load() {
        busy("Checking the last 7 days…")
        bg({
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryAndAggregateUsageStats(now - 7 * DAY_MS, now)
            val pm = packageManager
            val out = ArrayList<AppUse>()
            for ((pkg, s) in stats) {
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || pkg == packageName) continue
                val bgTime = if (Build.VERSION.SDK_INT >= 29) s.totalTimeForegroundServiceUsed else 0L
                if (s.totalTimeInForeground <= 0L && bgTime <= 0L) continue
                out.add(AppUse(pkg, pm.getApplicationLabel(ai).toString(), s.totalTimeInForeground, bgTime))
            }
            out.sortedWith(compareByDescending<AppUse> { it.background }.thenByDescending { it.visible })
        }) { list ->
            header.text = "Apps that run in the background use battery and slow the phone.\n" +
                    "Tap an app → Battery → choose “Restricted” (or “Deep sleeping” on Samsung)."
            showItems(list.take(60).map { a ->
                CheckItem(
                    a.name,
                    (if (a.background > 0) "Running in background ${Scan.hours(a.background)} · " else "") +
                            "on screen ${Scan.hours(a.visible)} (7 days)",
                    checkable = false,
                    onClick = { openAppDetails(a.pkg) }
                )
            })
        }
    }
}
