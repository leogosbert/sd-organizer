package com.sdorganizer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File

/** Where organized files are written. Paths are relative, e.g. "PhoneArchive/Videos/WhatsApp/2026-11". */
interface MoveTarget {
    val label: String
    fun freeBytes(): Long
    /** Size of an existing file at rel/name, or null if none. */
    fun sizeOf(rel: String, name: String): Long?
    /** Copies src into rel/name. Returns true only if the full file was written. */
    fun write(rel: String, name: String, src: File): Boolean
    /** Real file path for media scanning, if known. */
    fun realPath(rel: String, name: String): String?
}

object StorageInfo {
    /** Root of the removable SD card, e.g. /storage/1A2B-3C4D, or null if no card. */
    fun sdRoot(ctx: Context): File? {
        val dirs = ctx.getExternalFilesDirs(null)
        for (d in dirs) {
            if (d == null) continue
            if (!Environment.isExternalStorageRemovable(d)) continue
            val p = d.absolutePath
            val idx = p.indexOf("/Android/")
            if (idx > 0) return File(p.substring(0, idx))
        }
        return null
    }

    fun internalTotal(): Long = StatFs(Environment.getDataDirectory().path).totalBytes
    fun internalFree(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    fun sdTotal(ctx: Context): Long = sdRoot(ctx)?.let { runCatching { StatFs(it.path).totalBytes }.getOrNull() } ?: 0L
    fun sdFree(ctx: Context): Long = sdRoot(ctx)?.let { runCatching { StatFs(it.path).availableBytes }.getOrNull() } ?: 0L

    fun hasFileAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasSafAccess(ctx: Context, prefs: Prefs): Boolean {
        val s = prefs.sdTreeUri ?: return false
        val uri = Uri.parse(s)
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    /** Picks the best available way to write to the SD card. */
    fun pickTarget(ctx: Context, prefs: Prefs): MoveTarget? {
        val root = sdRoot(ctx) ?: return null
        if (hasFileAccess(ctx) && FileTarget.canWrite(root)) return FileTarget(root)
        if (hasSafAccess(ctx, prefs)) return SafTarget(ctx, Uri.parse(prefs.sdTreeUri), root)
        return null
    }

    fun fmt(bytes: Long): String {
        val kb = 1024.0
        val b = bytes.toDouble()
        return when {
            b >= kb * kb * kb -> String.format("%.1f GB", b / (kb * kb * kb))
            b >= kb * kb -> String.format("%.0f MB", b / (kb * kb))
            b >= kb -> String.format("%.0f KB", b / kb)
            else -> "$bytes B"
        }
    }
}

/** Direct file writing (Android 11+ with "All files access"). Fastest. */
class FileTarget(private val root: File) : MoveTarget {
    override val label = "direct"

    override fun freeBytes(): Long = StatFs(root.path).availableBytes

    override fun sizeOf(rel: String, name: String): Long? {
        val f = File(File(root, rel), name)
        return if (f.isFile) f.length() else null
    }

    override fun write(rel: String, name: String, src: File): Boolean {
        val dir = File(root, rel)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, ".$name.part")
        val dest = File(dir, name)
        return try {
            src.inputStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out, 256 * 1024) } }
            if (tmp.length() != src.length()) { tmp.delete(); return false }
            if (!tmp.renameTo(dest)) { tmp.delete(); return false }
            dest.setLastModified(src.lastModified())
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    override fun realPath(rel: String, name: String) = File(File(root, rel), name).absolutePath

    companion object {
        fun canWrite(root: File): Boolean = try {
            val dir = File(root, Organizer.ARCHIVE)
            if (!dir.isDirectory) dir.mkdirs()
            val probe = File(dir, ".probe")
            probe.writeText("ok")
            val ok = probe.exists()
            probe.delete()
            ok
        } catch (e: Exception) {
            false
        }
    }
}

/** Storage Access Framework writing (works on all versions once the user picks the SD card). */
class SafTarget(ctx: Context, private val tree: Uri, private val root: File) : MoveTarget {
    override val label = "saf"
    private val cr = ctx.contentResolver

    private data class Entry(val uri: Uri, val size: Long, val isDir: Boolean)

    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val listings = HashMap<Uri, MutableMap<String, Entry>>()
    private val dirs = HashMap<String, Uri>()

    override fun freeBytes(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun listing(dir: Uri): MutableMap<String, Entry> = listings.getOrPut(dir) {
        val m = HashMap<String, Entry>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        cr.query(
            q,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                val size = if (c.isNull(2)) 0L else c.getLong(2)
                m[c.getString(1)] = Entry(u, size, c.getString(3) == Document.MIME_TYPE_DIR)
            }
        }
        m
    }

    private fun dir(rel: String, create: Boolean): Uri? {
        dirs[rel]?.let { return it }
        var cur = rootDoc
        val parts = rel.split('/').filter { it.isNotEmpty() }
        var key = ""
        for (part in parts) {
            key = if (key.isEmpty()) part else "$key/$part"
            val cached = dirs[key]
            if (cached != null) { cur = cached; continue }
            val e = listing(cur)[part]
            cur = if (e != null && e.isDir) {
                e.uri
            } else {
                if (!create) return null
                val u = runCatching { DocumentsContract.createDocument(cr, cur, Document.MIME_TYPE_DIR, part) }.getOrNull()
                    ?: return null
                listing(cur)[part] = Entry(u, 0, true)
                listings[u] = HashMap()
                u
            }
            dirs[key] = cur
        }
        return cur
    }

    override fun sizeOf(rel: String, name: String): Long? {
        val d = dir(rel, false) ?: return null
        val e = listing(d)[name] ?: return null
        return if (e.isDir) -1L else e.size
    }

    override fun write(rel: String, name: String, src: File): Boolean {
        val d = dir(rel, true) ?: return false
        val doc = runCatching { DocumentsContract.createDocument(cr, d, "application/octet-stream", name) }.getOrNull()
            ?: return false
        return try {
            src.inputStream().use { input ->
                val out = cr.openOutputStream(doc, "w") ?: throw IllegalStateException("no stream")
                out.use { input.copyTo(it, 256 * 1024) }
            }
            // Check the written size
            var written = -1L
            cr.query(doc, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    written = c.getLong(0)
                    listing(d)[c.getString(1)] = Entry(doc, written, false)
                }
            }
            if (written != src.length()) {
                runCatching { DocumentsContract.deleteDocument(cr, doc) }
                false
            } else true
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            false
        }
    }

    override fun realPath(rel: String, name: String) = File(File(root, rel), name).absolutePath

    companion object {
        fun persist(ctx: Context, uri: Uri) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
