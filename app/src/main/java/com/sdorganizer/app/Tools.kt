package com.sdorganizer.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.util.Locale

const val DAY_MS = 86_400_000L

object Perms {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccess(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

object Scan {
    fun phoneRoot(): File = Environment.getExternalStorageDirectory()

    /** Walks every file under root. Skips hidden folders and Android/data + Android/obb. */
    fun walk(root: File, includeHidden: Boolean = false, onFile: (File) -> Unit) {
        val base = phoneRoot().absolutePath
        val stack = ArrayList<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeAt(stack.size - 1)
            val p = dir.absolutePath
            if (p == "$base/Android/data" || p == "$base/Android/obb") continue
            if (p.endsWith("/Android/data") || p.endsWith("/Android/obb") || p.endsWith("/LOST.DIR")) continue
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    if (includeHidden || !f.name.startsWith(".")) stack.add(f)
                } else {
                    onFile(f)
                }
            }
        }
    }

    fun dirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** Deletes files (and folders) and tells the gallery. Returns (count deleted, bytes freed). */
    fun deleteAll(ctx: Context, files: List<File>): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        val paths = ArrayList<String>()
        for (f in files) {
            val len = if (f.isDirectory) dirSize(f) else f.length()
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            if (ok) {
                n++
                bytes += len
                paths.add(f.absolutePath)
            }
        }
        Organizer.scan(ctx, paths)
        return Pair(n, bytes)
    }

    fun ago(ms: Long): String {
        val days = ((System.currentTimeMillis() - ms) / DAY_MS).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            days < 31 -> "$days days ago"
            days < 365 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    fun hours(ms: Long): String {
        val m = ms / 60_000L
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    /** Path shown to the user, e.g. "Phone › Download › file.mp4". */
    fun pretty(ctx: Context, f: File): String {
        val phone = phoneRoot().absolutePath
        val sd = StorageInfo.sdRoot(ctx)?.absolutePath
        val p = f.absolutePath
        return when {
            p.startsWith(phone) -> "Phone" + p.removePrefix(phone).replace("/", " › ")
            sd != null && p.startsWith(sd) -> "SD card" + p.removePrefix(sd).replace("/", " › ")
            else -> p
        }
    }

    fun onSd(ctx: Context, f: File): Boolean {
        val sd = StorageInfo.sdRoot(ctx)?.absolutePath ?: return false
        return f.absolutePath.startsWith(sd)
    }
}

/** Finds files that are safe to delete. */
object Junk {
    class Category(
        val title: String,
        val explain: String,
        val files: List<File>,
        val size: Long
    )

    private fun installed(pm: PackageManager, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun apkAlreadyInstalled(pm: PackageManager, f: File): Boolean = try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
        if (info == null) false
        else {
            @Suppress("DEPRECATION")
            val inst = pm.getPackageInfo(info.packageName, 0)
            PackageInfoCompat.getLongVersionCode(inst) >= PackageInfoCompat.getLongVersionCode(info)
        }
    } catch (e: Exception) {
        false
    }

    fun scan(ctx: Context): List<Category> {
        val base = Scan.phoneRoot()
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()

        // 1. Folders left behind by apps you uninstalled
        val leftovers = ArrayList<File>()
        File(base, "Android/media").listFiles()?.forEach { d ->
            if (d.isDirectory && d.name.contains('.') && d.name != ctx.packageName && !installed(pm, d.name)) {
                leftovers.add(d)
            }
        }

        val apks = ArrayList<File>()
        val broken = ArrayList<File>()
        val logs = ArrayList<File>()
        Scan.walk(base) { f ->
            val ext = f.extension.lowercase(Locale.US)
            when {
                ext == "apk" -> if (apkAlreadyInstalled(pm, f)) apks.add(f)
                ext in Organizer.PARTIAL_EXT -> if (now - f.lastModified() > DAY_MS) broken.add(f)
                ext == "log" -> if (now - f.lastModified() > 7 * DAY_MS) logs.add(f)
            }
        }

        val thumbs = ArrayList<File>()
        for (p in listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "Music/.thumbnails")) {
            File(base, p).listFiles()?.forEach { if (it.isFile) thumbs.add(it) }
        }

        // Empty folders (not top-level folders like DCIM or Download, not Android)
        val empty = ArrayList<File>()
        base.listFiles()?.forEach { top ->
            if (top.isDirectory && top.name != "Android" && !top.name.startsWith(".")) {
                top.walkBottomUp().forEach { d ->
                    if (d != top && d.isDirectory && !d.name.startsWith(".") && (d.list()?.isEmpty() == true)) empty.add(d)
                }
            }
        }

        fun size(list: List<File>) = list.sumOf { if (it.isDirectory) Scan.dirSize(it) else it.length() }

        return listOf(
            Category("Leftovers of uninstalled apps", "Folders from apps that are no longer on the phone", leftovers, size(leftovers)),
            Category("Installers of apps you already have", ".apk files for apps already installed", apks, size(apks)),
            Category("Broken downloads", "Downloads that never finished (older than 1 day)", broken, size(broken)),
            Category("Gallery thumbnail cache", "Small previews; the gallery rebuilds them", thumbs, size(thumbs)),
            Category("Old log files", "App log files older than 7 days", logs, size(logs)),
            Category("Empty folders", "Folders with nothing inside", empty, 0L)
        )
    }
}
