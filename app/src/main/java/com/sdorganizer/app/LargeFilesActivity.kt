package com.sdorganizer.app

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.view.View
import java.io.File

/** Lists big files on the phone and SD card. Move them to the SD card or delete them. */
class LargeFilesActivity : ListScreen() {

    private val sizes = longArrayOf(50, 100, 250, 500)
    private var minMb = 100L
    private lateinit var moveBtn: com.google.android.material.button.MaterialButton
    private lateinit var delBtn: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Large files"

        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            sizes.map { "Files bigger than $it MB" }
        )
        spin.setSelection(1)
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (minMb != sizes[pos]) {
                    minMb = sizes[pos]
                    load()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        topArea.addView(spin)

        moveBtn = addButton("Move selected to SD card") { moveSelected() }
        delBtn = addButton("Delete selected", outlined = true) { deleteSelected() }
        load()
    }

    override fun onSelectionChanged() {
        val sel = selected()
        val bytes = sel.sumOf { (it.data as File).length() }
        moveBtn.text = if (sel.isEmpty()) "Move selected to SD card" else "Move ${sel.size} to SD card (${StorageInfo.fmt(bytes)})"
        delBtn.text = if (sel.isEmpty()) "Delete selected" else "Delete ${sel.size} files (${StorageInfo.fmt(bytes)})"
    }

    private fun load() {
        busy("Searching for big files…")
        val min = minMb * 1024 * 1024
        bg({
            val phone = ArrayList<File>()
            val sd = ArrayList<File>()
            Scan.walk(Scan.phoneRoot()) { if (it.length() >= min) phone.add(it) }
            StorageInfo.sdRoot(this)?.let { root -> Scan.walk(root) { if (it.length() >= min) sd.add(it) } }
            Pair(phone.sortedByDescending { it.length() }, sd.sortedByDescending { it.length() })
        }) { (phone, sd) ->
            val out = ArrayList<CheckItem>()
            out.add(CheckItem("On the phone (${phone.size} · ${StorageInfo.fmt(phone.sumOf { it.length() })})", isHeader = true, checkable = false))
            phone.forEach { out.add(row(it)) }
            if (sd.isNotEmpty()) {
                out.add(CheckItem("On the SD card (${sd.size} · ${StorageInfo.fmt(sd.sumOf { it.length() })})", isHeader = true, checkable = false))
                sd.forEach { out.add(row(it)) }
            }
            header.text = if (phone.isEmpty()) "No big files on the phone 👍" else
                "Tap files to select them. Moving to the SD card frees phone memory without losing anything."
            showItems(out)
        }
    }

    private fun row(f: File) = CheckItem(
        "${StorageInfo.fmt(f.length())} · ${f.name}",
        "${Scan.pretty(this, f.parentFile ?: f)} · ${Scan.ago(f.lastModified())}",
        data = f
    )

    private fun moveSelected() {
        val files = selected().map { it.data as File }.filter { !Scan.onSd(this, it) }
        if (files.isEmpty()) {
            header.text = "Select files that are on the phone to move them."
            return
        }
        val target = StorageInfo.pickTarget(this, Prefs(this))
        if (target == null || !target.healthy()) {
            header.text = "SD card is not ready. Check setup on the main screen."
            return
        }
        busy("Moving ${files.size} files to the SD card…")
        bg({
            val base = Scan.phoneRoot()
            var ok = 0
            var bytes = 0L
            for (f in files) {
                val len = f.length()
                if (Organizer.moveOne(this, target, f, base)) {
                    ok++
                    bytes += len
                }
            }
            Pair(ok, bytes)
        }) { (ok, bytes) ->
            load()
            header.postDelayed({ header.text = "Moved $ok files · freed ${StorageInfo.fmt(bytes)} of phone memory" }, 600)
        }
    }

    private fun deleteSelected() {
        val files = selected().map { it.data as File }
        if (files.isEmpty()) return
        confirm(
            "Delete ${files.size} files?",
            "They will be permanently deleted. This cannot be undone.",
            "Delete"
        ) {
            busy("Deleting…")
            bg({ Scan.deleteAll(this, files) }) { (n, bytes) ->
                load()
                header.postDelayed({ header.text = "Deleted $n files · freed ${StorageInfo.fmt(bytes)}" }, 600)
            }
        }
    }
}
