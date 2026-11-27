package com.sdorganizer.app

import android.content.Context

/** Simple settings storage. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** SAF tree URI of the SD card (used when direct writing is not allowed). */
    var sdTreeUri: String?
        get() = sp.getString("sd_tree", null)
        set(v) = sp.edit().putString("sd_tree", v).apply()

    /** Index into AGE_LABELS / AGE_HOURS. */
    var ageIndex: Int
        get() = sp.getInt("age_idx", 0)
        set(v) = sp.edit().putInt("age_idx", v).apply()

    var autoEnabled: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var moveCamera: Boolean
        get() = sp.getBoolean("camera", true)
        set(v) = sp.edit().putBoolean("camera", v).apply()

    var moveChatMedia: Boolean
        get() = sp.getBoolean("chat", true)
        set(v) = sp.edit().putBoolean("chat", v).apply()

    var cleanThumbnails: Boolean
        get() = sp.getBoolean("thumbs", true)
        set(v) = sp.edit().putBoolean("thumbs", v).apply()

    var lastRunSummary: String?
        get() = sp.getString("last_run", null)
        set(v) = sp.edit().putString("last_run", v).apply()

    var totalFreedBytes: Long
        get() = sp.getLong("total_freed", 0L)
        set(v) = sp.edit().putLong("total_freed", v).apply()

    /** Minimum file age before moving. Always at least 10 minutes so downloads in progress are never touched. */
    val minAgeMillis: Long
        get() {
            val h = AGE_HOURS.getOrElse(ageIndex) { 0 }
            return maxOf(h * 3_600_000L, 10 * 60_000L)
        }

    companion object {
        val AGE_LABELS = arrayOf(
            "Immediately (keeps phone emptiest)",
            "After 1 day",
            "After 7 days",
            "After 30 days"
        )
        val AGE_HOURS = intArrayOf(0, 24, 24 * 7, 24 * 30)
    }
}
