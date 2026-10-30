package com.vanskis.liquidpocket.access

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Reads Android intents into [LaunchRequest]s, and builds the intents the widget and tile send. */
object LaunchIntents {
    /**
     * The non-exported activity-alias of MainActivity (see the manifest). Other apps cannot address
     * it, so an intent arriving through it was sent by the app's own widget or tile.
     */
    const val OWN_ENTRY = "com.vanskis.liquidpocket.access.QuickLaunch"

    fun parse(context: Context, intent: Intent): LaunchRequest.NewChat<Uri>? {
        val streams = streams(context, intent).map { uri -> SharedStream(uri, runCatching { context.contentResolver.getType(uri) }.getOrNull()) }
        return LaunchRequest.from(
            IncomingIntent(
                action = intent.action,
                type = intent.type,
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                streams = streams,
                start = intent.getStringExtra(LaunchRequest.EXTRA_START),
                fromOwnEntry = intent.component?.className == OWN_ENTRY,
                fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
            ),
        )
    }

    /** Shared content URIs; not file URIs, nor the app's own provider, which could point at private files. */
    private fun streams(context: Context, intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.filter { it.scheme == "content" && it.authority?.startsWith(context.packageName) != true }

    /** Opens a new chat through [OWN_ENTRY]; [start] is one of the `LaunchRequest.START_` values. */
    fun newChat(context: Context, start: String): Intent = Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(context.packageName, OWN_ENTRY))
        .putExtra(LaunchRequest.EXTRA_START, start)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** [newChat] as a PendingIntent; a request code per start value, so the widget's buttons keep their own extras. */
    fun pendingNewChat(context: Context, start: String): PendingIntent = PendingIntent.getActivity(
        context,
        start.hashCode(),
        newChat(context, start),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
