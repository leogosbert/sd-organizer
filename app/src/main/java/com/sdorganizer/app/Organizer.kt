package com.sdorganizer.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class RunResult {
    var moved = 0
    var duplicates = 0
    var failed = 0
    var bytesFreed = 0L
    var thumbsFreed = 0L
    var error: String? = null

    fun summary(): String {
        val sb = StringBuilder()
        sb.append("Moved $moved files, freed ${StorageInfo.fmt(bytesFreed + thumbsFreed)}")
        if (duplicates > 0) sb.append(" · $duplicates duplicates removed")
        if (failed > 0) sb.append(" · $failed could not be moved")
        error?.let { sb.append("\n⚠ ").append(it) }
        return sb.toString()
    }
}

/**
 * Scans the phone's internal shared storage and moves user files to the SD card, organized as
 *   PhoneArchive/<Category>/<Source app>/<YYYY-MM>/<file>
 */
object Organizer {
    const val ARCHIVE = "PhoneArchive"
    private const val SD_RESERVE = 100L * 1024 * 1024
    private val lock = ReentrantLock()

    /** Folders that must never be moved (would break apps, backups or ringtones). */
    private val SKIP_DIRS = setOf(
        "databases", "backups", "ringtones", "notifications", "alarms",
        "wallpapers", "wallpaper", ".statuses", ".shared", ".links", ".trash",
        "cache", ".cache", "temp", ".temp", "logs"
    )
    private val PARTIAL_EXT = setOf("part", "crdownload", "tmp", "download", "partial", "pending", "!ut")

    private val PHOTO = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng", "raw")
    private val VIDEO = setOf("mp4", "mkv", "3gp", "avi", "mov", "webm", "m4v", "flv", "wmv", "ts")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "amr", "flac", "wma", "3ga", "mid")
    private val DOCS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "xlsm", "csv", "ppt", "pptx", "txt", "rtf", "odt", "ods",
        "odp", "epub", "vcf", "json", "xml", "html", "htm", "md"
    )
    private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val APPS = setOf("apk", "xapk", "apks", "apkm")

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

    fun category(f: File): String {
        val ext = f.extension.lowercase(Locale.US)
        val path = f.absolutePath.lowercase(Locale.US)
        return when {
            ext in AUDIO && (path.contains("voice notes") || path.contains("/voice")) -> "Voice Notes"
            ext in PHOTO -> "Photos"
            ext in VIDEO -> "Videos"
            ext in AUDIO -> "Audio"
            ext in DOCS -> "Documents"
            ext in ARCHIVES -> "Archives"
            ext in APPS -> "App Installers"
            else -> "Other"
        }
    }

    fun source(f: File, base: File): String {
        val path = f.absolutePath.lowercase(Locale.US)
        val rel = f.absolutePath.removePrefix(base.absolutePath).trimStart('/')
        return when {
            path.contains("com.whatsapp.w4b") || path.contains("whatsapp business") -> "WhatsApp Business"
            path.contains("whatsapp") -> "WhatsApp"
            path.contains("telegram") -> "Telegram"
            path.contains("/dcim/camera") -> "Camera"
            path.contains("screenshot") -> "Screenshots"
            path.contains("screenrecord") || path.contains("screen recordings") -> "Screen Recordings"
            path.contains("bluetooth") -> "Bluetooth"
            path.contains("xender") -> "Xender"
            path.contains("shareit") -> "SHAREit"
            path.contains("record") -> "Recordings"
            path.contains("/download") -> "Downloads"
            rel.startsWith("Android/media/") -> rel.removePrefix("Android/media/").substringBefore('/')
            else -> rel.substringBefore('/').ifEmpty { "Phone" }
        }
    }

    private fun roots(base: File, prefs: Prefs): List<File> {
        val names = mutableListOf(
            "Download", "Downloads", "Documents", "Movies", "Music", "Pictures", "Recordings",
            "Podcasts", "Audiobooks", "Bluetooth", "Snapchat", "Xender", "SHAREit", "Screenshots",
            "Voice Recorder", "Sounds", "Call", "Record", "MIUI/sound_recorder", "Audio", "Video", "Videos"
        )
        if (prefs.moveCamera) names.add("DCIM")
        if (prefs.moveChatMedia) names.addAll(listOf("WhatsApp", "WhatsApp Business", "Telegram"))
        val list = names.map { File(base, it) }.toMutableList()
        // App media folders (newer WhatsApp, Telegram, etc. save here on Android 11+)
        File(base, "Android/media").listFiles()?.forEach { appDir ->
            val n = appDir.name.lowercase(Locale.US)
            val isChat = n.contains("whatsapp") || n.contains("telegram")
            if (appDir.isDirectory && appDir.name != "com.sdorganizer.app" && (!isChat || prefs.moveChatMedia)) {
                list.add(appDir)
            }
        }
        return list.filter { it.isDirectory }.distinctBy { it.canonicalPath }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    fun run(ctx: Context, onProgress: (String) -> Unit = {}): RunResult {
        val result = RunResult()
        if (!lock.tryLock()) {
            result.error = "Already running"
            return result
        }
        try {
            val prefs = Prefs(ctx)
            if (!StorageInfo.hasFileAccess(ctx)) {
                result.error = "File access not allowed. Open the app and tap step 1."
                return result
            }
            if (StorageInfo.sdRoot(ctx) == null) {
                result.error = "No SD card found."
                return result
            }
            val target = StorageInfo.pickTarget(ctx, prefs)
            if (target == null) {
                result.error = "SD card not set up. Open the app and tap step 2."
                return result
            }

            val base = Environment.getExternalStorageDirectory()
            val cutoff = System.currentTimeMillis() - prefs.minAgeMillis
            val scanPaths = ArrayList<String>()
            var count = 0

            if (prefs.cleanThumbnails) result.thumbsFreed = cleanThumbnails(base)

            outer@ for (root in roots(base, prefs)) {
                val stack = ArrayDeque<File>()
                stack.push(root)
                while (stack.isNotEmpty()) {
                    val dir = stack.pop()
                    val children = dir.listFiles() ?: continue
                    for (f in children) {
                        val lname = f.name.lowercase(Locale.US)
                        if (f.isDirectory) {
                            if (lname.startsWith(".") || lname in SKIP_DIRS) continue
                            stack.push(f)
                            continue
                        }
                        if (lname.startsWith(".")) continue
                        if (f.extension.lowercase(Locale.US) in PARTIAL_EXT) continue
                        val len = f.length()
                        if (len <= 0L) continue
                        if (f.lastModified() > cutoff) continue

                        if (target.freeBytes() < len + SD_RESERVE) {
                            result.error = "SD card is full. Free some space on the SD card."
                            break@outer
                        }

                        val cat = category(f)
                        val src = safeName(source(f, base))
                        val month = monthFmt.format(Date(f.lastModified()))
                        val rel = "$ARCHIVE/$cat/$src/$month"
                        val name = safeName(f.name)

                        val existing = target.sizeOf(rel, name)
                        if (existing != null && existing == len) {
                            // Same file already on SD card: just remove the internal copy
                            if (f.delete()) {
                                result.duplicates++
                                result.bytesFreed += len
                                scanPaths.add(f.absolutePath)
                            }
                            continue
                        }
                        var finalName = name
                        if (existing != null) {
                            val stem = name.substringBeforeLast('.', name)
                            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                            var i = 1
                            while (target.sizeOf(rel, finalName) != null) {
                                finalName = "$stem ($i)$ext"
                                i++
                            }
                        }

                        if (target.write(rel, finalName, f) && f.delete()) {
                            result.moved++
                            result.bytesFreed += len
                            scanPaths.add(f.absolutePath)
                            target.realPath(rel, finalName)?.let { scanPaths.add(it) }
                        } else {
                            result.failed++
                        }

                        count++
                        if (count % 10 == 0) {
                            onProgress("Moved ${result.moved} files (${StorageInfo.fmt(result.bytesFreed)})…")
                        }
                        if (scanPaths.size >= 200) {
                            scan(ctx, scanPaths)
                            scanPaths.clear()
                        }
                    }
                }
                removeEmptyDirs(root, isRoot = true)
            }
            scan(ctx, scanPaths)
            return result
        } catch (e: Exception) {
            result.error = e.message ?: e.toString()
            return result
        } finally {
            lock.unlock()
        }
    }

    /** Thumbnail caches are rebuilt automatically by the gallery, so they are safe to delete. */
    private fun cleanThumbnails(base: File): Long {
        var freed = 0L
        for (p in listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "Music/.thumbnails")) {
            File(base, p).listFiles()?.forEach { f ->
                if (f.isFile) {
                    val l = f.length()
                    if (f.delete()) freed += l
                }
            }
        }
        return freed
    }

    private fun removeEmptyDirs(dir: File, isRoot: Boolean) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory && !c.name.startsWith(".") && c.name.lowercase(Locale.US) !in SKIP_DIRS) {
                removeEmptyDirs(c, isRoot = false)
            }
        }
        // Keep top folders and app folders (WhatsApp expects its Media subfolders to exist)
        if (!isRoot && dir.parentFile?.name != "Media" && dir.list()?.isEmpty() == true) dir.delete()
    }

    private fun scan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching { MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null) }
    }
}
