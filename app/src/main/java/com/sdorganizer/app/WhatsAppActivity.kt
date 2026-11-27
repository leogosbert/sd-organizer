package com.sdorganizer.app

import android.content.Intent
import android.os.Bundle
import java.io.File

/** Shows WhatsApp media by type (received / sent) and clears or moves it in bulk. */
class WhatsAppActivity : ListScreen() {

    private class Group(val label: String, val files: List<File>) {
        val size = files.sumOf { it.length() }
        val oldFiles = files.filter { System.currentTimeMillis() - it.lastModified() > 30 * DAY_MS }
        val oldSize = oldFiles.sumOf { it.length() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WhatsApp cleaner"
        addButton("Delete selected older than 30 days") { delete(onlyOld = true) }
        addButton("Delete all selected", outlined = true) { delete(onlyOld = false) }
        addButton("Move selected to SD card", outlined = true) { moveToSd() }
        addButton("Find duplicate forwarded files", outlined = true) {
            startActivity(Intent(this, DuplicatesActivity::class.java))
        }
        load()
    }

    private fun mediaDirs(): List<Pair<String, File>> {
        val base = Scan.phoneRoot()
        return listOf(
            "WhatsApp" to File(base, "Android/media/com.whatsapp/WhatsApp/Media"),
            "WhatsApp" to File(base, "WhatsApp/Media"),
            "WA Business" to File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"),
            "WA Business" to File(base, "WhatsApp Business/Media")
        ).filter { it.second.isDirectory }
    }

    private fun filesIn(dir: File, skipSub: Set<String>): List<File> {
        val out = ArrayList<File>()
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                if (!f.name.startsWith(".")) out.add(f)
            } else if (f.name !in skipSub) {
                f.walkTopDown().forEach { if (it.isFile && !it.name.startsWith(".")) out.add(it) }
            }
        }
        return out
    }

    private fun load() {
        busy("Reading WhatsApp folders…")
        bg({
            val groups = ArrayList<Group>()
            for ((app, media) in mediaDirs()) {
                media.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { typeDir ->
                    val type = typeDir.name.removePrefix("WhatsApp Business ").removePrefix("WhatsApp ").removePrefix(".")
                    if (typeDir.name == ".Statuses") {
                        groups.add(Group("$app · Viewed statuses", filesIn(typeDir, emptySet())))
                    } else if (!typeDir.name.startsWith(".")) {
                        groups.add(Group("$app · $type — received", filesIn(typeDir, setOf("Sent", "Private"))))
                        val sent = File(typeDir, "Sent")
                        if (sent.isDirectory) groups.add(Group("$app · $type — sent by you", filesIn(sent, emptySet())))
                    }
                }
            }
            groups.filter { it.files.isNotEmpty() }.sortedByDescending { it.size }
        }) { groups ->
            val total = groups.sumOf { it.size }
            header.text = if (groups.isEmpty()) "No WhatsApp media on the phone 👍"
            else "WhatsApp is using ${StorageInfo.fmt(total)} of phone memory. Select what to clean.\n" +
                    "Deleted files disappear from your chats too."
            showItems(groups.map { g ->
                CheckItem(
                    "${g.label} · ${StorageInfo.fmt(g.size)}",
                    "${g.files.size} files · ${StorageInfo.fmt(g.oldSize)} older than 30 days",
                    checked = g.label.contains("statuses") || g.label.contains("sent by you"),
                    data = g
                )
            })
        }
    }

    private fun delete(onlyOld: Boolean) {
        val groups = selected().map { it.data as Group }
        val files = groups.flatMap { if (onlyOld) it.oldFiles else it.files }
        if (files.isEmpty()) {
            header.text = "Nothing to delete in the selected groups."
            return
        }
        val bytes = files.sumOf { it.length() }
        confirm(
            "Delete ${files.size} WhatsApp files?",
            "This frees ${StorageInfo.fmt(bytes)}. The files will no longer open from your chats. This cannot be undone.",
            "Delete"
        ) {
            busy("Deleting…")
            bg({ Scan.deleteAll(this, files) }) { (n, freed) ->
                load()
                header.postDelayed({ header.text = "Deleted $n files · freed ${StorageInfo.fmt(freed)}" }, 600)
            }
        }
    }

    private fun moveToSd() {
        val files = selected().flatMap { (it.data as Group).files }
        if (files.isEmpty()) return
        val target = StorageInfo.pickTarget(this, Prefs(this))
        if (target == null || !target.healthy()) {
            header.text = "SD card is not ready. Check setup on the main screen."
            return
        }
        confirm(
            "Move ${files.size} files to the SD card?",
            "They go to PhoneArchive on the SD card and open from Gallery or Files, not from inside the chats.",
            "Move"
        ) {
            busy("Moving to SD card…")
            bg({
                val base = Scan.phoneRoot()
                var ok = 0
                var bytes = 0L
                for (f in files) {
                    val len = f.length()
                    if (Organizer.moveOne(this, target, f, base)) {
                        ok++; bytes += len
                    }
                }
                Pair(ok, bytes)
            }) { (ok, bytes) ->
                load()
                header.postDelayed({ header.text = "Moved $ok files · freed ${StorageInfo.fmt(bytes)}" }, 600)
            }
        }
    }
}
