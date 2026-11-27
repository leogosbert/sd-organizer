package com.sdorganizer.app

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.DateFormat
import java.util.Date

class OrganizeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        var last = 0L
        var lastPhase = ""
        val r = Organizer.run(applicationContext) { p ->
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
                        KEY_CURRENT to p.current
                    )
                )
            }
        }
        val prefs = Prefs(applicationContext)
        if (r.error != "Already running") {
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
            prefs.lastRunSummary = "$time — ${r.summary()}"
            prefs.totalFreedBytes = prefs.totalFreedBytes + r.bytesFreed + r.thumbsFreed
        }
        return Result.success(workDataOf(KEY_MSG to r.summary()))
    }

    companion object {
        const val KEY_MSG = "msg"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_CURRENT = "current"
        const val AUTO = "organize_auto"
        const val NOW = "organize_now"

        /** The app now runs only when the user taps the button; this removes any old hourly job. */
        fun cancelAuto(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(AUTO)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<OrganizeWorker>().build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
