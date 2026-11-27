package com.sdorganizer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.DateFormat
import java.util.Date

class OrganizeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun doWork(): Result {
        // Idle guard: only run right after the user pressed "Move & organize now".
        // If Android tries to re-run an old or interrupted job later, it does nothing.
        val prefs0 = Prefs(applicationContext)
        val requested = prefs0.moveRequestedAt
        prefs0.moveRequestedAt = 0L
        if (requested == 0L || System.currentTimeMillis() - requested > 2 * 60_000L) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(AUTO)
            return Result.success(workDataOf(KEY_MSG to "Idle"))
        }

        // Run as a foreground task so Android does not stop long moves (or a long pause) after 10 minutes
        runCatching { setForegroundAsync(foregroundInfo("Preparing…", 0, true, false)).get() }

        var last = 0L
        var lastNotif = 0L
        var lastPhase = ""
        val r = Organizer.run(applicationContext, shouldStop = { isStopped }) { p ->
            val now = System.currentTimeMillis()
            if (now - last >= 250 || p.phase != lastPhase) {
                last = now
                lastPhase = p.phase
                setProgressAsync(
                    workDataOf(
                        KEY_PHASE to p.phase,
                        KEY_DONE to p.done,
                        KEY_TOTAL to p.total,
                        KEY_BYTES_DONE to p.bytesDone,
                        KEY_BYTES_TOTAL to p.bytesTotal,
                        KEY_CURRENT to p.current,
                        KEY_PAUSED to p.paused,
                        KEY_PHONE_START to p.phoneFreeStart,
                        KEY_SD_START to p.sdFreeStart
                    )
                )
            }
            if (now - lastNotif >= 1000) {
                lastNotif = now
                val pct = if (p.bytesTotal > 0) (p.bytesDone * 100 / p.bytesTotal).toInt() else 0
                val text = when {
                    p.paused -> "Paused · $pct%"
                    p.phase == Organizer.PHASE_MOVE ->
                        "$pct% · ${StorageInfo.fmt(p.bytesDone)} of ${StorageInfo.fmt(p.bytesTotal)}"
                    p.phase == Organizer.PHASE_TIDY -> "Tidying the SD card…"
                    else -> "Looking for files…"
                }
                runCatching {
                    nm.notify(NOTIF_ID, buildNotification(text, pct, p.phase != Organizer.PHASE_MOVE, p.paused))
                }
            }
        }
        Organizer.paused = false
        runCatching { MemoryWidget.refresh(applicationContext) }

        val prefs = Prefs(applicationContext)
        if (r.error != "Already running") {
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
            prefs.lastRunSummary = "$time — ${r.summary()}"
            prefs.totalFreedBytes = prefs.totalFreedBytes + r.bytesFreed + r.thumbsFreed
        }
        return Result.success(workDataOf(KEY_MSG to r.summary()))
    }

    private fun foregroundInfo(text: String, pct: Int, indeterminate: Boolean, paused: Boolean): ForegroundInfo {
        val n = buildNotification(text, pct, indeterminate, paused)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String, pct: Int, indeterminate: Boolean, paused: Boolean): android.app.Notification {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Moving files", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (paused) "SD Organizer — paused" else "SD Organizer — moving to SD card")
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        const val KEY_MSG = "msg"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_CURRENT = "current"
        const val KEY_PAUSED = "paused"
        const val KEY_PHONE_START = "phone_start"
        const val KEY_SD_START = "sd_start"
        const val AUTO = "organize_auto"
        const val NOW = "organize_now"
        private const val CHANNEL = "moving"
        private const val NOTIF_ID = 42

        /** The app runs only when the user taps the button; this removes any old hourly job. */
        fun cancelAuto(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(AUTO)
        }

        fun runNow(ctx: Context) {
            Prefs(ctx).moveRequestedAt = System.currentTimeMillis()
            val req = OneTimeWorkRequestBuilder<OrganizeWorker>().build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        fun stop(ctx: Context) {
            Organizer.paused = false
            WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
        }
    }
}
