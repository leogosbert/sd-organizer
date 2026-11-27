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
    /** Copies src into rel/name, reporting bytes copied. Returns true only if the full file was written and verified. */
    fun write(rel: String, name: String, src: File, onBytes: (Long) -> Unit): Boolean
    /** Real file path for media scanning, if known. */
    fun realPath(rel: String, name: String): String?
    /** True if the file at rel/name has the same content as src (size + start/end bytes). */
    fun sameContent(rel: String, name: String, src: File): Boolean
    /** True if the SD card still accepts new files (false when it has turned read-only or failed). */
    fun healthy(): Boolean
}

/** Copies a stream while reporting how many bytes have been copied so far. */
fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream, onBytes: (Long) -> Unit) {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        onBytes(total)
    }
    out.flush()
}

/** Compares two files cheaply: same size, same first and last 64 KB. */
object Fingerprint {
    private const val CHUNK = 64 * 1024

    fun head(input: java.io.InputStream): ByteArray {
        val buf = ByteArray(CHUNK)
        var n = 0
        while (n < CHUNK) {
            val r = input.read(buf, n, CHUNK - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    fun tail(f: File): ByteArray = java.io.RandomAccessFile(f, "r").use { raf ->
        val len = raf.length()
        val start = maxOf(0L, len - CHUNK)
        val buf = ByteArray((len - start).toInt())
        raf.seek(start)
        raf.readFully(buf)
        buf
    }

    /** Reads both streams to the end and compares every byte. */
    fun fullSame(a: java.io.InputStream, b: java.io.InputStream): Boolean {
        val ba = ByteArray(256 * 1024)
        val bb = ByteArray(256 * 1024)
        while (true) {
            val na = readFully(a, ba)
            val nb = readFully(b, bb)
            if (na != nb) return false
            if (na <= 0) return true
            for (i in 0 until na) if (ba[i] != bb[i]) return false
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    fun same(a: File, b: File): Boolean = try {
        a.length() == b.length() &&
                a.inputStream().use { head(it) }.contentEquals(b.inputStream().use { head(it) }) &&
                tail(a).contentEquals(tail(b))
    } catch (e: Exception) {
        false
    }
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

    override fun write(rel: String, name: String, src: File, onBytes: (Long) -> Unit): Boolean {
        val dir = File(root, rel)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, ".$name.part")
        val dest = File(dir, name)
        return try {
            src.inputStream().use { input ->
                java.io.FileOutputStream(tmp).use { out ->
                    copyWithProgress(input, out, onBytes)
                    out.fd.sync() // make sure it is really on the card before checking
                }
            }
            if (tmp.length() != src.length()) { tmp.delete(); return false }
            // Read the copy back from the SD card and compare every byte with the original
            val ok = tmp.inputStream().use { a -> src.inputStream().use { b -> Fingerprint.fullSame(a, b) } }
            if (!ok) { tmp.delete(); return false }
            if (!tmp.renameTo(dest)) { tmp.delete(); return false }
            dest.setLastModified(src.lastModified())
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    override fun realPath(rel: String, name: String) = File(File(root, rel), name).absolutePath

    override fun sameContent(rel: String, name: String, src: File) =
        Fingerprint.same(File(File(root, rel), name), src)

    override fun healthy(): Boolean = canWrite(root)

    /** Moves a file that is already on the SD card (instant rename, no copying). */
    fun moveWithin(src: File, rel: String, name: String): File? {
        val dir = File(root, rel)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val dest = File(dir, name)
        return if (src.renameTo(dest)) dest else null
    }

    fun root(): File = root

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

    override fun write(rel: String, name: String, src: File, onBytes: (Long) -> Unit): Boolean {
        val d = dir(rel, true) ?: return false
        val doc = runCatching { DocumentsContract.createDocument(cr, d, "application/octet-stream", name) }.getOrNull()
            ?: return false
        return try {
            src.inputStream().use { input ->
                val out = cr.openOutputStream(doc, "w") ?: throw IllegalStateException("no stream")
                out.use { copyWithProgress(input, it, onBytes) }
            }
            // Check the written size
            var written = -1L
            cr.query(doc, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    written = c.getLong(0)
                    listing(d)[c.getString(1)] = Entry(doc, written, false)
                }
            }
            val ok = written == src.length() &&
                    (cr.openInputStream(doc)?.use { a -> src.inputStream().use { b -> Fingerprint.fullSame(a, b) } } ?: false)
            if (!ok) {
                runCatching { DocumentsContract.deleteDocument(cr, doc) }
                false
            } else true
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            false
        }
    }

    override fun realPath(rel: String, name: String) = File(File(root, rel), name).absolutePath

    override fun sameContent(rel: String, name: String, src: File): Boolean {
        val d = dir(rel, false) ?: return false
        val e = listing(d)[name] ?: return false
        if (e.isDir || e.size != src.length()) return false
        return try {
            val a = cr.openInputStream(e.uri)?.use { Fingerprint.head(it) } ?: return false
            val b = src.inputStream().use { Fingerprint.head(it) }
            a.contentEquals(b)
        } catch (ex: Exception) {
            false
        }
    }

    override fun healthy(): Boolean = try {
        val d = dir(Organizer.ARCHIVE, true)
        val probe = if (d != null) DocumentsContract.createDocument(cr, d, "application/octet-stream", ".probe") else null
        if (probe != null) {
            DocumentsContract.deleteDocument(cr, probe)
            true
        } else false
    } catch (e: Exception) {
        false
    }

    /** A file already on the SD card. relPath is relative to the SD card root. */
    class DocFile(val uri: Uri, val parent: Uri, val name: String, val size: Long, val modified: Long, val relPath: String)

    /** Lists every file on the SD card, skipping top-level folders for which skipTop returns true. */
    fun listAll(skipDir: (name: String, depth: Int) -> Boolean): List<DocFile> {
        val out = ArrayList<DocFile>()
        val stack = ArrayDeque<Triple<Uri, String, Int>>()
        stack.add(Triple(rootDoc, "", 0))
        while (stack.isNotEmpty()) {
            val (dirUri, path, depth) = stack.removeAt(stack.size - 1)
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dirUri))
            cr.query(
                q,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
                    Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    val name: String = c.getString(1) ?: ""
                    val rel = if (path.isEmpty()) name else "$path/$name"
                    if (name.isEmpty()) {
                        // skip unnamed entries
                    } else if (c.getString(3) == Document.MIME_TYPE_DIR) {
                        if (!skipDir(name, depth)) stack.add(Triple(u, rel, depth + 1))
                    } else {
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        val mod = if (c.isNull(4)) 0L else c.getLong(4)
                        out.add(DocFile(u, dirUri, name, size, mod, rel))
                    }
                }
            }
        }
        return out
    }

    /** Moves a file already on the SD card into rel/newName. No copying. */
    fun moveWithin(f: DocFile, rel: String, newName: String): Boolean {
        val dest = dir(rel, true) ?: return false
        return try {
            var src = f.uri
            if (newName != f.name) src = DocumentsContract.renameDocument(cr, src, newName) ?: return false
            val moved = DocumentsContract.moveDocument(cr, src, f.parent, dest) ?: return false
            listing(dest)[newName] = Entry(moved, f.size, false)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True if f has the same size and first bytes as rel/name. */
    fun sameDoc(f: DocFile, rel: String, name: String): Boolean {
        val d = dir(rel, false) ?: return false
        val e = listing(d)[name] ?: return false
        if (e.isDir || e.size != f.size) return false
        return try {
            val a = cr.openInputStream(e.uri)?.use { Fingerprint.head(it) } ?: return false
            val b = cr.openInputStream(f.uri)?.use { Fingerprint.head(it) } ?: return false
            a.contentEquals(b)
        } catch (ex: Exception) {
            false
        }
    }

    fun delete(f: DocFile): Boolean = runCatching { DocumentsContract.deleteDocument(cr, f.uri) }.getOrDefault(false)

    companion object {
        fun persist(ctx: Context, uri: Uri) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
