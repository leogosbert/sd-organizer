package com.sdorganizer.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class OrganizeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val r = Organizer.run(applicationContext) { msg ->
            setProgressAsync(workDataOf(KEY_MSG to msg))
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
        const val AUTO = "organize_auto"
        const val NOW = "organize_now"

        fun scheduleAuto(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) {
                wm.cancelUniqueWork(AUTO)
                return
            }
            val req = PeriodicWorkRequestBuilder<OrganizeWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<OrganizeWorker>().build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
