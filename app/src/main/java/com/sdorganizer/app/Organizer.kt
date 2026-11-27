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
    var tidied = 0
    var error: String? = null

    fun summary(): String {
        val sb = StringBuilder()
        sb.append("Moved $moved files, freed ${StorageInfo.fmt(bytesFreed + thumbsFreed)}")
        if (tidied > 0) sb.append(" · tidied $tidied SD card files")
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
            rel.contains('/') -> rel.substringBefore('/')
            else -> "Loose files"
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

            if (!target.healthy()) {
                result.error = "The SD card stopped accepting files (it may be read-only or damaged). Nothing more was deleted from your phone. Back up the SD card and check it before trying again."
                return result
            }

            val base = Environment.getExternalStorageDirectory()
            var failsInRow = 0
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
                        if (existing != null && existing == len && target.sameContent(rel, name, f)) {
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

                        val written = target.write(rel, finalName, f)
                        if (written && f.delete()) {
                            result.moved++
                            result.bytesFreed += len
                            failsInRow = 0
                            scanPaths.add(f.absolutePath)
                            target.realPath(rel, finalName)?.let { scanPaths.add(it) }
                        } else {
                            result.failed++
                            if (!written) failsInRow++
                            if (failsInRow >= 3 || (!written && !target.healthy())) {
                                result.error = "The SD card stopped accepting files (it may be read-only or damaged). Nothing more was deleted from your phone. Back up the SD card and check it before trying again."
                                break@outer
                            }
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
            scanPaths.clear()

            if (prefs.tidySd && result.error == null) {
                onProgress("Tidying files already on the SD card…")
                when (target) {
                    is FileTarget -> tidySdFiles(ctx, target, result, onProgress)
                    is SafTarget -> tidySdSaf(target, result, onProgress)
                }
            }
            return result
        } catch (e: Exception) {
            result.error = e.message ?: e.toString()
            return result
        } finally {
            lock.unlock()
        }
    }

    /** SD card folders that are never touched. */
    private fun skipSdTop(name: String): Boolean {
        val l = name.lowercase(Locale.US)
        return l.startsWith(".") || l == "android" || l == "lost.dir" || l == ARCHIVE.lowercase(Locale.US) ||
                l == "system volume information" || l in SKIP_DIRS
    }

    private fun skipSdDir(name: String, depth: Int): Boolean {
        val l = name.lowercase(Locale.US)
        return if (depth == 0) skipSdTop(name) else (l.startsWith(".") || l in SKIP_DIRS)
    }

    private fun skipFileName(name: String): Boolean {
        val l = name.lowercase(Locale.US)
        return l.startsWith(".") || l.substringAfterLast('.', "") in PARTIAL_EXT
    }

    private fun uniqueName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        var cand: String
        do {
            cand = "$stem ($i)$ext"
            i++
        } while (taken(cand))
        return cand
    }

    /**
     * Re-files everything already on the SD card (outside PhoneArchive) into the same
     * PhoneArchive/<Category>/<Source>/<YYYY-MM> layout. Files stay on the SD card, so this is an
     * instant rename, not a copy.
     */
    private fun tidySdFiles(ctx: Context, target: FileTarget, result: RunResult, onProgress: (String) -> Unit) {
        val sd = target.root()
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        val files = ArrayList<File>()
        val tops = ArrayList<File>()
        sd.listFiles()?.forEach { top ->
            if (top.isDirectory) {
                if (!skipSdTop(top.name)) tops.add(top)
            } else if (!skipFileName(top.name)) {
                files.add(top)
            }
        }
        for (top in tops) {
            val stack = ArrayDeque<File>()
            stack.push(top)
            while (stack.isNotEmpty()) {
                val d = stack.pop()
                d.listFiles()?.forEach { f ->
                    if (f.isDirectory) {
                        if (!skipSdDir(f.name, 1)) stack.push(f)
                    } else if (!skipFileName(f.name)) {
                        files.add(f)
                    }
                }
            }
        }
        val scanPaths = ArrayList<String>()
        for (f in files) {
            if (f.length() <= 0L || f.lastModified() > cutoff) continue
            val rel = "$ARCHIVE/${category(f)}/${safeName(source(f, sd))}/${monthFmt.format(Date(f.lastModified()))}"
            val dir = File(sd, rel)
            val name = safeName(f.name)
            val existing = File(dir, name)
            if (existing.isFile && Fingerprint.same(existing, f)) {
                if (f.delete()) {
                    result.duplicates++
                    scanPaths.add(f.absolutePath)
                }
                continue
            }
            val finalName = uniqueName(name) { File(dir, it).exists() }
            val moved = target.moveWithin(f, rel, finalName)
            if (moved == null && !target.healthy()) {
                result.error = "The SD card stopped accepting files (it may be read-only or damaged). Nothing more was deleted from your phone. Back up the SD card and check it before trying again."
                break
            }
            if (moved != null) {
                result.tidied++
                scanPaths.add(f.absolutePath)
                scanPaths.add(moved.absolutePath)
            }
            if (moved != null && result.tidied % 25 == 0) onProgress("Tidied ${result.tidied} SD card files…")
            if (scanPaths.size >= 200) {
                scan(ctx, scanPaths)
                scanPaths.clear()
            }
        }
        scan(ctx, scanPaths)
        for (top in tops) removeEmptyDirs(top, isRoot = false)
    }

    /** Same as tidySdFiles, for phones where the SD card is reached through the system picker. */
    private fun tidySdSaf(target: SafTarget, result: RunResult, onProgress: (String) -> Unit) {
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        val files = target.listAll { name, depth -> skipSdDir(name, depth) }
        val sdBase = File("/sd")
        for (f in files) {
            if (skipFileName(f.name) || f.size <= 0L || f.modified > cutoff) continue
            val pseudo = File(sdBase, f.relPath)
            val rel = "$ARCHIVE/${category(pseudo)}/${safeName(source(pseudo, sdBase))}/${monthFmt.format(Date(f.modified))}"
            val name = safeName(f.name)
            if (target.sameDoc(f, rel, name)) {
                if (target.delete(f)) result.duplicates++
                continue
            }
            val finalName = uniqueName(name) { target.sizeOf(rel, it) != null }
            if (target.moveWithin(f, rel, finalName)) {
                result.tidied++
            } else if (!target.healthy()) {
                result.error = "The SD card stopped accepting files (it may be read-only or damaged). Nothing more was deleted from your phone. Back up the SD card and check it before trying again."
                return
            }
            if (result.tidied % 25 == 0) onProgress("Tidied ${result.tidied} SD card files…")
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
