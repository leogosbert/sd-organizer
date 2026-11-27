package com.sdorganizer.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget: phone memory %, SD free space, and a Move button (asks before moving). */
class MemoryWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        const val EXTRA_ASK_MOVE = "ask_move"

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MemoryWidget::class.java))
            if (ids.isNotEmpty()) for (id in ids) mgr.updateAppWidget(id, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val total = StorageInfo.internalTotal()
            val free = StorageInfo.internalFree()
            val pct = if (total > 0) ((total - free) * 100 / total).toInt() else 0
            val sdFree = StorageInfo.sdFree(ctx)

            val v = RemoteViews(ctx.packageName, R.layout.widget_memory)
            v.setTextViewText(R.id.wPct, "$pct%")
            v.setTextViewText(R.id.wDetail, "Phone · ${StorageInfo.fmt(free)} free")
            v.setTextViewText(R.id.wSd, if (sdFree > 0) "SD · ${StorageInfo.fmt(sdFree)} free" else "No SD card")
            v.setProgressBar(R.id.wBar, 100, pct, false)

            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val move = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_ASK_MOVE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.wRoot, open)
            v.setOnClickPendingIntent(R.id.wMove, move)
            return v
        }
    }
}
