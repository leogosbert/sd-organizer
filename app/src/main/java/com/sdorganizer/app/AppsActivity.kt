package com.sdorganizer.app

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class AppsActivity : AppCompatActivity() {

    private data class AppRow(val pkg: String, val name: String, val daysUnused: Int, val bytes: Long, val cache: Long)

    private lateinit var list: ListView
    private lateinit var header: TextView
    private lateinit var grantBtn: MaterialButton
    private var rows: List<AppRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        header = TextView(this).apply { setPadding(0, 0, 0, pad / 2) }
        grantBtn = MaterialButton(this).apply {
            text = "Allow usage access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        list = ListView(this)
        root.addView(header)
        root.addView(grantBtn)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            header.text = "To see which apps you don't use and how much space they take, " +
                    "allow usage access for SD Organizer on the next screen."
            grantBtn.visibility = View.VISIBLE
            list.adapter = null
            return
        }
        grantBtn.visibility = View.GONE
        header.text = "Loading apps…"
        Thread {
            val data = load()
            runOnUiThread {
                rows = data
                val unused = data.count { it.daysUnused >= 30 }
                val unusedSize = data.filter { it.daysUnused >= 30 }.sumOf { it.bytes }
                header.text = "$unused apps not used in 30+ days, taking ${StorageInfo.fmt(unusedSize)}.\n" +
                        "Tap an app → Uninstall, or Storage → Clear cache."
                list.adapter = Adapter()
            }
        }.start()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun load(): List<AppRow> {
        val pm = packageManager
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val now = System.currentTimeMillis()
        val usage = usm.queryAndAggregateUsageStats(now - 365L * 86_400_000L, now)
        val out = ArrayList<AppRow>()
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        for (ai in apps) {
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (ai.packageName == packageName) continue
            val last = usage[ai.packageName]?.lastTimeUsed ?: 0L
            val days = if (last <= 0L) 365 else ((now - last) / 86_400_000L).toInt()
            var bytes = 0L
            var cache = 0L
            try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                bytes = st.appBytes + st.dataBytes + st.cacheBytes
                cache = st.cacheBytes
            } catch (_: Exception) {
            }
            out.add(AppRow(ai.packageName, pm.getApplicationLabel(ai).toString(), days, bytes, cache))
        }
        // Unused apps first, biggest first
        return out.sortedWith(compareByDescending<AppRow> { it.daysUnused >= 30 }.thenByDescending { it.bytes })
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val r = rows[p]
            v.findViewById<TextView>(android.R.id.text1).text = r.name
            val used = when {
                r.daysUnused >= 365 -> "Not used in a year"
                r.daysUnused >= 30 -> "Not used for ${r.daysUnused} days"
                r.daysUnused == 0 -> "Used today"
                else -> "Used ${r.daysUnused} days ago"
            }
            v.findViewById<TextView>(android.R.id.text2).text =
                "$used · ${StorageInfo.fmt(r.bytes)} (cache ${StorageInfo.fmt(r.cache)})"
            return v
        }
    }
}
