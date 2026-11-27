package com.sdorganizer.app

import android.os.Bundle
import java.io.File
import java.security.MessageDigest

/** Finds exact copies of the same file (phone + SD card) and removes the extra copies. */
class DuplicatesActivity : ListScreen() {

    private lateinit var delBtn: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Duplicate files"
        delBtn = addButton("Delete selected copies") { deleteSelected() }
        addButton("Scan again", outlined = true) { load() }
        load()
    }

    override fun onSelectionChanged() {
        val sel = selected()
        val bytes = sel.sumOf { (it.data as File).length() }
        delBtn.text = if (sel.isEmpty()) "Delete selected copies" else "Delete ${sel.size} copies (frees ${StorageInfo.fmt(bytes)})"
    }

    private fun md5(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun quick(f: File): String = try {
        val head = f.inputStream().use { Fingerprint.head(it) }
        val tail = Fingerprint.tail(f)
        f.length().toString() + ":" + head.contentHashCode() + ":" + tail.contentHashCode()
    } catch (e: Exception) {
        "err:" + f.absolutePath
    }

    private fun load() {
        busy("Looking for duplicate files… (this can take a minute)")
        bg({
            val all = ArrayList<File>()
            val minSize = 50L * 1024
            Scan.walk(Scan.phoneRoot()) { if (it.length() >= minSize) all.add(it) }
            StorageInfo.sdRoot(this)?.let { root -> Scan.walk(root) { if (it.length() >= minSize) all.add(it) } }

            val groups = ArrayList<List<File>>()
            for (bySize in all.groupBy { it.length() }.values) {
                if (bySize.size < 2) continue
                for (byQuick in bySize.groupBy { quick(it) }.values) {
                    if (byQuick.size < 2) continue
                    for (byHash in byQuick.groupBy { runCatching { md5(it) }.getOrDefault(it.absolutePath) }.values) {
                        if (byHash.size >= 2) groups.add(byHash)
                    }
                }
            }
            groups.sortedByDescending { it.first().length() * (it.size - 1) }
        }) { groups ->
            val out = ArrayList<CheckItem>()
            var wasted = 0L
            for (g in groups) {
                // Keep one copy: prefer the organized copy on the SD card, otherwise the oldest
                val keep = g.firstOrNull { it.absolutePath.contains("/${Organizer.ARCHIVE}/") }
                    ?: g.minByOrNull { it.lastModified() }!!
                val size = g.first().length()
                wasted += size * (g.size - 1)
                out.add(
                    CheckItem(
                        "${g.size} copies of ${keep.name}",
                        "${StorageInfo.fmt(size)} each · one copy is always kept",
                        isHeader = true, checkable = false
                    )
                )
                for (f in g) {
                    out.add(
                        CheckItem(
                            if (f == keep) "KEEP · ${Scan.pretty(this, f)}" else Scan.pretty(this, f),
                            Scan.ago(f.lastModified()),
                            checked = f != keep,
                            data = f
                        )
                    )
                }
            }
            header.text = if (groups.isEmpty()) "No duplicate files found 👍"
            else "${groups.size} files have copies · ${StorageInfo.fmt(wasted)} can be freed. Extra copies are already selected."
            showItems(out)
        }
    }

    private fun deleteSelected() {
        // Safety: never delete every copy of a file
        var i = 0
        while (i < items.size) {
            if (items[i].isHeader) {
                var j = i + 1
                val group = ArrayList<CheckItem>()
                while (j < items.size && !items[j].isHeader) {
                    group.add(items[j]); j++
                }
                if (group.isNotEmpty() && group.all { it.checked }) group.first().checked = false
                i = j
            } else i++
        }
        adapter.notifyDataSetChanged()
        val files = selected().map { it.data as File }
        if (files.isEmpty()) return
        confirm(
            "Delete ${files.size} extra copies?",
            "One copy of each file is kept. Deleted copies cannot be recovered.",
            "Delete"
        ) {
            busy("Deleting copies…")
            bg({ Scan.deleteAll(this, files) }) { (n, bytes) ->
                load()
                header.postDelayed({ header.text = "Deleted $n copies · freed ${StorageInfo.fmt(bytes)}" }, 600)
            }
        }
    }
}
