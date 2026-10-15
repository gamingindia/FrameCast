package com.framecast

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget with Prev / Next buttons for the frame's slideshow. */
class FrameWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setOnClickPendingIntent(R.id.btn_prev, pending(context, "prev"))
        rv.setOnClickPendingIntent(R.id.btn_next, pending(context, "next"))
        appWidgetManager.updateAppWidget(appWidgetIds, rv)
    }

    private fun pending(ctx: Context, cmd: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, cmd.hashCode(),
            Intent(ctx, FrameWidget::class.java).setAction("com.framecast.$cmd"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val cmd = when (intent.action) {
            "com.framecast.next" -> "next"
            "com.framecast.prev" -> "prev"
            else -> return
        }
        val pr = goAsync()
        val app = context.applicationContext
        Thread {
            try { FrameApi.command(app, cmd) } finally { pr.finish() }
        }.start()
    }
}
