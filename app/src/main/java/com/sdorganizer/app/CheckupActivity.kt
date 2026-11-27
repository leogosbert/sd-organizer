package com.sdorganizer.app

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/** One screen that scores the phone and links to every tool. Runs only when opened. */
class CheckupActivity : ListScreen() {

    private class Check(val ok: Boolean, val points: Int, val max: Int, val title: String, val detail: String, val fix: (() -> Unit)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Phone checkup"
        addButton("Check again") { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun load() {
        busy("Checking your phone…")
        val hasUsage = Perms.hasUsageAccess(this)
        bg({
            val checks = ArrayList<Check>()

            // Storage
            val total = StorageInfo.internalTotal()
            val free = StorageInfo.internalFree()
            val pct = if (total > 0) ((total - free) * 100 / total).toInt() else 0
            val sPts = when { pct < 70 -> 25; pct < 85 -> 12; else -> 0 }
            checks.add(Check(sPts == 25, sPts, 25, "Phone memory $pct% full",
                if (sPts == 25) "Plenty of room" else "Phones slow down when memory is nearly full. Tap to move files.",
                { startActivity(Intent(this, MainActivity::class.java)) }))

            // Junk
            val junk = Junk.scan(this).sumOf { it.size }
            val jPts = when { junk < 200L * 1024 * 1024 -> 15; junk < 1024L * 1024 * 1024 -> 7; else -> 0 }
            checks.add(Check(jPts == 15, jPts, 15, "Junk: ${StorageInfo.fmt(junk)}",
                if (jPts == 15) "Very little junk" else "Tap to clean it", { open(JunkActivity::class.java) }))

            // Large files on phone
            val min = 100L * 1024 * 1024
            var bigCount = 0
            var bigSize = 0L
            Scan.walk(Scan.phoneRoot()) { if (it.length() >= min) { bigCount++; bigSize += it.length() } }
            val lPts = when { bigCount == 0 -> 15; bigCount <= 5 -> 7; else -> 0 }
            checks.add(Check(lPts == 15, lPts, 15, "Big files on phone: $bigCount (${StorageInfo.fmt(bigSize)})",
                if (lPts == 15) "None over 100 MB" else "Tap to move them to the SD card", { open(LargeFilesActivity::class.java) }))

            // Unused apps
            if (hasUsage) {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val usage = usm.queryAndAggregateUsageStats(now - 365L * DAY_MS, now)
                @Suppress("DEPRECATION")
                val unused = packageManager.getInstalledApplications(0).count { ai ->
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && ai.packageName != packageName &&
                            now - (usage[ai.packageName]?.lastTimeUsed ?: 0L) > 30 * DAY_MS
                }
                val uPts = when { unused == 0 -> 15; unused <= 3 -> 8; else -> 0 }
                checks.add(Check(uPts == 15, uPts, 15, "Apps not used in 30 days: $unused",
                    if (uPts == 15) "No forgotten apps" else "They run in the background. Tap to remove them.",
                    { open(AppsActivity::class.java) }))
            } else {
                checks.add(Check(false, 0, 15, "Apps not used in 30 days: unknown",
                    "Tap to allow usage access so this can be checked", { Perms.openUsageAccess(this) }))
            }

            // Animations
            val scale = maxOf(
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
                Settings.Global.getFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
                Settings.Global.getFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
            )
            checks.add(Check(scale <= 0.5f, if (scale <= 0.5f) 10 else 0, 10, "Animations ${scale}x",
                if (scale <= 0.5f) "Fast" else "Set to 0.5x in Developer options (see main screen)",
                { openFirst(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }))

            // Battery saver
            val saver = (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
            checks.add(Check(!saver, if (saver) 0 else 10, 10, if (saver) "Battery saver ON" else "Battery saver off",
                if (saver) "It slows the phone. Turn off when you don't need it." else "Full speed",
                { openFirst(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }))

            // Restart
            val days = (SystemClock.elapsedRealtime() / DAY_MS).toInt()
            checks.add(Check(days < 7, if (days < 7) 10 else 0, 10, "Last restart: $days days ago",
                if (days < 7) "Good" else "Restart the phone to clear memory", null))
            checks
        }) { checks ->
            val score = checks.sumOf { it.points }
            val verdict = when { score >= 85 -> "Excellent 🚀"; score >= 65 -> "Good 👍"; score >= 40 -> "Needs a clean-up"; else -> "Slow — fix the ⚠ items" }
            header.text = "Phone score: $score / 100 — $verdict\nTap any ⚠ item to fix it."
            val out = ArrayList<CheckItem>()
            out.add(CheckItem("Checkup", isHeader = true, checkable = false))
            for (c in checks) {
                out.add(CheckItem((if (c.ok) "✓  " else "⚠  ") + c.title, c.detail, checkable = false, onClick = c.fix))
            }
            out.add(CheckItem("All tools", isHeader = true, checkable = false))
            out.add(tool("🗂  Large files", "Find big files, move or delete them", LargeFilesActivity::class.java))
            out.add(tool("👯  Duplicate files", "Remove extra copies", DuplicatesActivity::class.java))
            out.add(tool("🧹  Junk cleaner", "Leftovers, old installers, broken downloads", JunkActivity::class.java))
            out.add(tool("💬  WhatsApp cleaner", "Clear media by type, sent files, statuses", WhatsAppActivity::class.java))
            out.add(tool("📊  Background data", "Apps using your bundle when you are not using them", DataUsageActivity::class.java))
            out.add(tool("🔋  Battery-hungry apps", "Apps that keep running in the background", BatteryAppsActivity::class.java))
            out.add(tool("📶  Network check", "Signal, 4G/5G, Wi-Fi strength", NetworkActivity::class.java))
            out.add(tool("📦  Apps you don't use", "Remove forgotten apps", AppsActivity::class.java))
            out.add(CheckItem("💳  Google Play subscriptions", "See and cancel paid subscriptions", checkable = false,
                onClick = { openPlaySubscriptions(this) }))
            showItems(out)
        }
    }

    private fun tool(t: String, s: String, cls: Class<*>) =
        CheckItem(t, s, checkable = false, onClick = { open(cls) })

    companion object {
        fun openPlaySubscriptions(ctx: Context) {
            val url = Uri.parse("https://play.google.com/store/account/subscriptions")
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, url).setPackage("com.android.vending"))
            } catch (e: Exception) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, url))
            }
        }
    }
}
