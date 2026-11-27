package com.sdorganizer.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import java.io.File

/** Cleans files that are safe to delete: leftovers, old installers, broken downloads, caches, logs, empty folders. */
class JunkActivity : ListScreen() {

    private lateinit var cleanBtn: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Junk cleaner"
        cleanBtn = addButton("Clean selected") { clean() }
        load()
    }

    override fun onSelectionChanged() {
        val sel = selected()
        val bytes = sel.sumOf { (it.data as Junk.Category).size }
        cleanBtn.text = if (sel.isEmpty()) "Clean selected" else "Clean ${sel.size} items (${StorageInfo.fmt(bytes)})"
    }

    private fun load() {
        busy("Looking for junk…")
        bg({ Junk.scan(this) }) { cats ->
            val total = cats.sumOf { it.size }
            header.text = if (cats.all { it.files.isEmpty() }) "Your phone is clean 👍"
            else "${StorageInfo.fmt(total)} of junk found. Tap a line to see its files."
            showItems(cats.filter { it.files.isNotEmpty() }.map { c ->
                CheckItem(
                    "${c.title} · ${if (c.size > 0) StorageInfo.fmt(c.size) else "${c.files.size} folders"}",
                    "${c.explain} · ${c.files.size} items · tap to see them",
                    checked = true,
                    data = c,
                    onClick = { showFiles(c) }
                )
            })
        }
    }

    private fun showFiles(c: Junk.Category) {
        val names = c.files.take(40).joinToString("\n") { "• " + Scan.pretty(this, it) }
        val more = if (c.files.size > 40) "\n…and ${c.files.size - 40} more" else ""
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(names + more)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun clean() {
        val cats = selected().map { it.data as Junk.Category }
        if (cats.isEmpty()) return
        val files: List<File> = cats.flatMap { it.files }
        confirm("Clean ${files.size} items?", "These files are safe to delete and cannot be recovered.", "Clean") {
            busy("Cleaning…")
            bg({ Scan.deleteAll(this, files) }) { (n, bytes) ->
                load()
                header.postDelayed({ header.text = "Cleaned $n items · freed ${StorageInfo.fmt(bytes)}" }, 600)
            }
        }
    }
}
