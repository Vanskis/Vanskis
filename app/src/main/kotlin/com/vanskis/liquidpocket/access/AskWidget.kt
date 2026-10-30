package com.vanskis.liquidpocket.access

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.vanskis.liquidpocket.R

/** The 4x1 home-screen pill: "Ask Liquid Pocket" opens a new chat to type in, the mic one to speak. */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.ask_widget).apply {
            setOnClickPendingIntent(R.id.ask_widget_text, LaunchIntents.pendingNewChat(context, LaunchRequest.START_TEXT))
            setOnClickPendingIntent(R.id.ask_widget_mic, LaunchIntents.pendingNewChat(context, LaunchRequest.START_VOICE))
        }
        manager.updateAppWidget(appWidgetIds, views)
    }
}
