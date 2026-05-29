package com.octalide.niky

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class NikyToggleWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> renderOne(ctx, mgr, id) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (NikyService.running.value) NikyService.stopSvc(ctx)
            else                            NikyService.startSvc(ctx)
        }
        if (intent.action == ACTION_TOGGLE || intent.action == ACTION_REFRESH) {
            refreshAll(ctx)
        }
    }

    companion object {
        const val ACTION_TOGGLE  = "com.octalide.niky.WIDGET_TOGGLE"
        const val ACTION_REFRESH = "com.octalide.niky.WIDGET_REFRESH"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NikyToggleWidget::class.java))
            ids.forEach { id -> renderOne(ctx, mgr, id) }
        }

        private fun renderOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val running = NikyService.running.value
            val views = RemoteViews(ctx.packageName, R.layout.widget_toggle).apply {
                setTextViewText(R.id.widget_label, if (running) "niky\non" else "niky\noff")
                setInt(
                    R.id.widget_root,
                    "setBackgroundResource",
                    if (running) R.drawable.widget_bg_on else R.drawable.widget_bg_off,
                )
                val pi = PendingIntent.getBroadcast(
                    ctx,
                    0,
                    Intent(ctx, NikyToggleWidget::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            mgr.updateAppWidget(id, views)
        }
    }
}
