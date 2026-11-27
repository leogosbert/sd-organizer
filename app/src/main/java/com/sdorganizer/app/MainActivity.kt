package com.sdorganizer.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private val pickSd = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary")) {
            Toast.makeText(this, "That is phone memory. Please choose the SD card.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            SafTarget.persist(this, uri)
            prefs.sdTreeUri = uri.toString()
            Toast.makeText(this, "SD card ready ✓", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Could not get SD card access. Try again.", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<MaterialButton>(R.id.btnAccess).setOnClickListener { requestFileAccess() }
        findViewById<MaterialButton>(R.id.btnSd).setOnClickListener { chooseSd() }

        val spin = findViewById<Spinner>(R.id.spinAge)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.AGE_LABELS)
        spin.setSelection(prefs.ageIndex)
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.ageIndex = pos }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        bindSwitch(R.id.swCamera, prefs.moveCamera) { prefs.moveCamera = it }
        bindSwitch(R.id.swChat, prefs.moveChatMedia) { prefs.moveChatMedia = it }
        bindSwitch(R.id.swTidy, prefs.tidySd) { prefs.tidySd = it }
        bindSwitch(R.id.swThumbs, prefs.cleanThumbnails) { prefs.cleanThumbnails = it }

        findViewById<MaterialButton>(R.id.btnRun).setOnClickListener {
            if (!ready()) {
                Toast.makeText(this, "Finish setup steps 1 and 2 first", Toast.LENGTH_LONG).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                confirmAndMove()
            }
        }

        findViewById<MaterialButton>(R.id.btnPause).setOnClickListener {
            Organizer.paused = !Organizer.paused
            (it as MaterialButton).text = if (Organizer.paused) "▶ Resume" else "⏸ Pause"
            if (!Organizer.paused) runStartTime = 0L // restart the speed estimate after a pause
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Stop moving?")
                .setMessage("Files already moved stay on the SD card. The file being copied right now stays safely on your phone.")
                .setPositiveButton("Stop") { _, _ -> OrganizeWorker.stop(this) }
                .setNegativeButton("Keep going", null)
                .show()
        }

        findViewById<MaterialButton>(R.id.btnCheckup).setOnClickListener {
            startActivity(Intent(this, CheckupActivity::class.java))
        }
        buildToolGrid()
        handleWidgetIntent(intent)

        findViewById<MaterialButton>(R.id.btnUnused).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnCache).setOnClickListener { openCleaner() }
        findViewById<MaterialButton>(R.id.btnAnim).setOnClickListener { showAnimationHelp() }

        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(OrganizeWorker.NOW).observe(this) { infos ->
            val info = infos?.lastOrNull() ?: return@observe
            val status = findViewById<TextView>(R.id.txtStatus)
            val btn = findViewById<MaterialButton>(R.id.btnRun)
            val card = findViewById<View>(R.id.progressCard)
            when (info.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                    btn.isEnabled = false
                    btn.text = "Working…"
                    status.text = ""
                    card.visibility = View.VISIBLE
                    showProgress(info.progress)
                }
                else -> {
                    btn.isEnabled = true
                    btn.text = "Move & organize now"
                    card.visibility = View.GONE
                    runStartTime = 0L
                    refresh()
                }
            }
        }

        // Manual mode only: remove the hourly job left by older versions
        OrganizeWorker.cancelAuto(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        MemoryWidget.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    /** The widget's "Move now" opens the app and asks first, so nothing moves without confirming. */
    private fun handleWidgetIntent(i: Intent?) {
        if (i?.getBooleanExtra(MemoryWidget.EXTRA_ASK_MOVE, false) == true) {
            i.removeExtra(MemoryWidget.EXTRA_ASK_MOVE)
            if (ready()) confirmAndMove()
            else Toast.makeText(this, "Finish setup steps 1 and 2 first", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildToolGrid() {
        val grid = findViewById<android.widget.GridLayout>(R.id.toolGrid)
        val tools = listOf<Pair<String, () -> Unit>>(
            "🗂 Large files" to { startActivity(Intent(this, LargeFilesActivity::class.java)) },
            "👯 Duplicates" to { startActivity(Intent(this, DuplicatesActivity::class.java)) },
            "🧹 Junk cleaner" to { startActivity(Intent(this, JunkActivity::class.java)) },
            "💬 WhatsApp" to { startActivity(Intent(this, WhatsAppActivity::class.java)) },
            "📊 Background data" to { startActivity(Intent(this, DataUsageActivity::class.java)) },
            "🔋 Battery apps" to { startActivity(Intent(this, BatteryAppsActivity::class.java)) },
            "📶 Network check" to { startActivity(Intent(this, NetworkActivity::class.java)) },
            "💳 Play subscriptions" to { CheckupActivity.openPlaySubscriptions(this) }
        )
        for ((label, action) in tools) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { action() }
            val lp = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.setMargins(4, 0, 4, 0)
            grid.addView(b, lp)
        }
    }

    private var runStartTime = 0L
    private var runStartBytes = 0L

    /** Updates the live progress card from the worker's progress data. */
    private fun showProgress(d: androidx.work.Data) {
        val phase = d.getString(OrganizeWorker.KEY_PHASE) ?: Organizer.PHASE_SCAN
        val done = d.getInt(OrganizeWorker.KEY_DONE, 0)
        val total = d.getInt(OrganizeWorker.KEY_TOTAL, 0)
        val bDone = d.getLong(OrganizeWorker.KEY_BYTES_DONE, 0L)
        val bTotal = d.getLong(OrganizeWorker.KEY_BYTES_TOTAL, 0L)
        val current = d.getString(OrganizeWorker.KEY_CURRENT) ?: ""

        val title = findViewById<TextView>(R.id.txtProgTitle)
        val pct = findViewById<TextView>(R.id.txtProgPct)
        val bar = findViewById<LinearProgressIndicator>(R.id.barProg)
        val bytes = findViewById<TextView>(R.id.txtProgBytes)
        val files = findViewById<TextView>(R.id.txtProgFiles)
        val speed = findViewById<TextView>(R.id.txtProgSpeed)
        val cur = findViewById<TextView>(R.id.txtProgCurrent)

        if (runStartTime == 0L) {
            runStartTime = System.currentTimeMillis()
            runStartBytes = bDone
        }

        when (phase) {
            Organizer.PHASE_SCAN -> {
                title.text = "Looking for files to move…"
                pct.text = ""
                bar.isIndeterminate = true
                bytes.text = if (total > 0) "Found $total files · ${StorageInfo.fmt(bTotal)}" else ""
                files.text = ""
                speed.text = ""
                cur.text = ""
            }
            Organizer.PHASE_MOVE -> {
                title.text = "Moving to SD card"
                val p = if (bTotal > 0) (bDone * 1000 / bTotal).toInt() else 0
                bar.isIndeterminate = false
                bar.setProgressCompat(p, true)
                pct.text = "${p / 10}%"
                bytes.text = "${StorageInfo.fmt(bDone)} of ${StorageInfo.fmt(bTotal)} moved"
                files.text = "File ${minOf(done + 1, total)} of $total"
                val secs = (System.currentTimeMillis() - runStartTime) / 1000.0
                if (secs > 2 && bDone > runStartBytes) {
                    val rate = (bDone - runStartBytes) / secs
                    val left = ((bTotal - bDone) / rate).toLong()
                    val leftText = if (left >= 60) "${left / 60} min ${left % 60} s" else "$left s"
                    speed.text = "${StorageInfo.fmt(rate.toLong())}/s · about $leftText left"
                } else speed.text = ""
                cur.text = if (current.isNotEmpty()) "Now: $current" else ""
            }
            else -> {
                title.text = "Tidying the SD card…"
                pct.text = ""
                bar.isIndeterminate = true
                bytes.text = current
                files.text = ""
                speed.text = ""
                cur.text = ""
            }
        }

        // Pause button label
        val paused = d.getBoolean(OrganizeWorker.KEY_PAUSED, false)
        findViewById<MaterialButton>(R.id.btnPause).text = if (paused) "▶ Resume" else "⏸ Pause"
        if (paused) {
            title.text = "Paused"
            speed.text = "Tap Resume to continue"
        }

        // Live memory: phone used goes DOWN and SD card used goes UP as bytes are moved
        val phoneTotal = StorageInfo.internalTotal()
        val sdTotal = StorageInfo.sdTotal(this)
        val phoneFree0 = d.getLong(OrganizeWorker.KEY_PHONE_START, 0L).takeIf { it > 0 } ?: StorageInfo.internalFree()
        val sdFree0 = d.getLong(OrganizeWorker.KEY_SD_START, 0L).takeIf { it > 0 } ?: StorageInfo.sdFree(this)
        val moved = if (phase == Organizer.PHASE_MOVE) bDone else if (phase == Organizer.PHASE_TIDY) bTotal else 0L

        val phoneUsed0 = phoneTotal - phoneFree0
        val phoneUsedNow = maxOf(0L, phoneUsed0 - moved)
        val phoneUsedEnd = maxOf(0L, phoneUsed0 - bTotal)
        val sdUsed0 = sdTotal - sdFree0
        val sdUsedNow = minOf(sdTotal, sdUsed0 + moved)
        val sdUsedEnd = minOf(sdTotal, sdUsed0 + bTotal)

        fun pct(used: Long, total: Long) = if (total > 0) (used * 100 / total).toInt() else 0
        fun permille(used: Long, total: Long) = if (total > 0) (used * 1000 / total).toInt() else 0

        findViewById<LinearProgressIndicator>(R.id.barSimPhone).setProgressCompat(permille(phoneUsedNow, phoneTotal), true)
        findViewById<TextView>(R.id.txtSimPhone).text =
            "Was ${pct(phoneUsed0, phoneTotal)}% → now ${pct(phoneUsedNow, phoneTotal)}% → when done ${pct(phoneUsedEnd, phoneTotal)}%\n" +
                    "${StorageInfo.fmt(phoneTotal - phoneUsedNow)} free of ${StorageInfo.fmt(phoneTotal)} · " +
                    "${StorageInfo.fmt(moved)} freed"
        findViewById<LinearProgressIndicator>(R.id.barSimSd).setProgressCompat(permille(sdUsedNow, sdTotal), true)
        findViewById<TextView>(R.id.txtSimSd).text =
            "Was ${pct(sdUsed0, sdTotal)}% → now ${pct(sdUsedNow, sdTotal)}% → when done ${pct(sdUsedEnd, sdTotal)}%\n" +
                    "${StorageInfo.fmt(sdTotal - sdUsedNow)} free of ${StorageInfo.fmt(sdTotal)}"

        // Keep the top storage card moving too
        findViewById<LinearProgressIndicator>(R.id.barInternal).setProgressCompat(pct(phoneUsedNow, phoneTotal), true)
        findViewById<TextView>(R.id.txtInternal).text =
            "${StorageInfo.fmt(phoneUsedNow)} used of ${StorageInfo.fmt(phoneTotal)} (${pct(phoneUsedNow, phoneTotal)}%) · " +
                    "${StorageInfo.fmt(phoneTotal - phoneUsedNow)} free"
        findViewById<LinearProgressIndicator>(R.id.barSd).setProgressCompat(pct(sdUsedNow, sdTotal), true)
        findViewById<TextView>(R.id.txtSd).text =
            "${StorageInfo.fmt(sdTotal - sdUsedNow)} free of ${StorageInfo.fmt(sdTotal)} · files go to “${Organizer.ARCHIVE}”"
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun ready(): Boolean = StorageInfo.pickTarget(this, prefs) != null

    private fun refresh() {
        val iTotal = StorageInfo.internalTotal()
        val iFree = StorageInfo.internalFree()
        val iUsed = iTotal - iFree
        val iPct = if (iTotal > 0) (iUsed * 100 / iTotal).toInt() else 0
        findViewById<LinearProgressIndicator>(R.id.barInternal).progress = iPct
        findViewById<TextView>(R.id.txtInternal).text =
            "${StorageInfo.fmt(iUsed)} used of ${StorageInfo.fmt(iTotal)} ($iPct%) · ${StorageInfo.fmt(iFree)} free"

        val sdRoot = StorageInfo.sdRoot(this)
        val sdText = findViewById<TextView>(R.id.txtSd)
        val sdBar = findViewById<LinearProgressIndicator>(R.id.barSd)
        if (sdRoot == null) {
            sdText.text = "No SD card found. Insert an SD card."
            sdBar.progress = 0
        } else {
            val sTotal = StorageInfo.sdTotal(this)
            val sFree = StorageInfo.sdFree(this)
            val sPct = if (sTotal > 0) ((sTotal - sFree) * 100 / sTotal).toInt() else 0
            sdBar.progress = sPct
            sdText.text = "${StorageInfo.fmt(sFree)} free of ${StorageInfo.fmt(sTotal)} · files go to “${Organizer.ARCHIVE}”"
        }
        findViewById<TextView>(R.id.txtTotal).text =
            "Total freed so far: ${StorageInfo.fmt(prefs.totalFreedBytes)}"

        val access = StorageInfo.hasFileAccess(this)
        val target = StorageInfo.pickTarget(this, prefs)
        findViewById<MaterialButton>(R.id.btnAccess).text =
            if (access) "1. Access to files ✓" else "1. Allow access to files"
        findViewById<MaterialButton>(R.id.btnSd).text = when {
            target != null -> "2. SD card ready ✓"
            else -> "2. Choose the SD card"
        }
        findViewById<TextView>(R.id.txtSetup).text = when {
            sdRoot == null -> "Insert an SD card to continue."
            !access -> "Tap step 1 and turn ON “Allow access to manage all files”."
            target == null -> "Tap step 2, open the SD card (top level) and press “Use this folder”."
            else -> "All set. Files move only when you tap “Move & organize now”."
        }

        buildSpeedCheck(iPct)

        val btn = findViewById<MaterialButton>(R.id.btnRun)
        if (btn.isEnabled) {
            findViewById<TextView>(R.id.txtStatus).text = prefs.lastRunSummary?.let { "Last run: $it" } ?: ""
        }
    }

    /** Checks the things that really make a phone slow, and links each to its fix. */
    private fun buildSpeedCheck(internalPct: Int) {
        val items = ArrayList<Triple<Boolean, String, () -> Unit>>()

        items.add(
            Triple(
                internalPct < 80,
                if (internalPct < 80) "Phone memory $internalPct% full — good"
                else "Phone memory $internalPct% full — phones slow down above 80%. Tap to move files now.",
                { if (ready()) confirmAndMove() else requestFileAccess() }
            )
        )

        val scale = maxOf(
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
            Settings.Global.getFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
            Settings.Global.getFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        )
        items.add(
            Triple(
                scale <= 0.5f,
                if (scale <= 0.5f) "Animations ${scale}x — fast"
                else "Animations ${scale}x — set to 0.5x so screens open twice as fast",
                { showAnimationHelp() }
            )
        )

        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val saver = pm.isPowerSaveMode
        items.add(
            Triple(
                !saver,
                if (!saver) "Battery saver off — full speed"
                else "Battery saver is ON — it slows the phone down. Tap to turn off when not needed.",
                { openFirst(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }
            )
        )

        val live = runCatching { android.app.WallpaperManager.getInstance(this).wallpaperInfo != null }
            .getOrDefault(false)
        items.add(
            Triple(
                !live,
                if (!live) "Normal wallpaper — good"
                else "Live (moving) wallpaper uses power and memory. Tap to choose a normal picture.",
                { openFirst(Intent(Intent.ACTION_SET_WALLPAPER), Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
            )
        )

        val days = (android.os.SystemClock.elapsedRealtime() / 86_400_000L).toInt()
        items.add(
            Triple(
                days < 7,
                if (days < 7) "Restarted recently — good"
                else "Phone not restarted for $days days. Restart it to clear memory.",
                {
                    Toast.makeText(this, "Hold the power button and tap Restart", Toast.LENGTH_LONG).show()
                }
            )
        )

        items.add(
            Triple(
                false,
                "Remove apps you don't use — they run in the background and slow the phone.",
                { startActivity(Intent(this, AppsActivity::class.java)) }
            )
        )

        val list = findViewById<android.widget.LinearLayout>(R.id.speedList)
        list.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        for ((ok, text, action) in items) {
            val tv = TextView(this)
            tv.text = (if (ok) "✓  " else "⚠  ") + text
            tv.setPadding(0, pad, 0, pad)
            tv.textSize = 15f
            if (!ok) tv.setOnClickListener { action() }
            list.addView(tv)
        }
        val good = items.count { it.first }
        findViewById<TextView>(R.id.txtSpeedTitle).text = "Speed check: $good of ${items.size - 1} good"
    }

    /** The ONLY way a move starts: the user presses Move, sees what will happen, and confirms. */
    private fun confirmAndMove() {
        val what = buildString {
            append("• Age: ").append(Prefs.AGE_LABELS[prefs.ageIndex]).append("\n")
            append("• Camera photos & videos: ").append(if (prefs.moveCamera) "MOVED" else "stay on phone").append("\n")
            append("• WhatsApp & Telegram files: ").append(if (prefs.moveChatMedia) "MOVED" else "stay on phone").append("\n")
            append("• Reorganize SD card files: ").append(if (prefs.tidySd) "yes" else "no")
        }
        AlertDialog.Builder(this)
            .setTitle("Move files to the SD card now?")
            .setMessage(what + "\n\nYou can pause or stop at any time.")
            .setPositiveButton("Move now") { _, _ ->
                runStartTime = 0L
                OrganizeWorker.runNow(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {
            }
        }
    }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPerms.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun chooseSd() {
        val root = StorageInfo.sdRoot(this)
        if (root == null) {
            Toast.makeText(this, "No SD card found", Toast.LENGTH_LONG).show()
            return
        }
        if (StorageInfo.hasFileAccess(this) && FileTarget.canWrite(root)) {
            Toast.makeText(this, "SD card is ready ✓", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose the SD card")
            .setMessage("In the next screen, open the menu (☰), tap your SD card, then press “Use this folder” and “Allow”.")
            .setPositiveButton("OK") { _, _ -> pickSd.launch(null) }
            .show()
    }

    private fun openCleaner() {
        val intents = listOf(
            Intent(StorageManager.ACTION_MANAGE_STORAGE),
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {
            }
        }
    }

    private fun showAnimationHelp() {
        AlertDialog.Builder(this)
            .setTitle("Faster animations")
            .setMessage(
                "This makes every screen open faster:\n\n" +
                        "1. Settings → About phone → tap “Build number” 7 times.\n" +
                        "2. Go back to Settings → System → Developer options.\n" +
                        "3. Set “Window animation scale”, “Transition animation scale” and " +
                        "“Animator duration scale” to 0.5x.\n\n" +
                        "Android only lets you change this yourself."
            )
            .setPositiveButton("Open Developer options") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
