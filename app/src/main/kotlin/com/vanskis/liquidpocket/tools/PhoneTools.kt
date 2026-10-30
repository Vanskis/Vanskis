package com.vanskis.liquidpocket.tools

import android.content.pm.PackageManager
import com.vanskis.liquidpocket.LiquidPocketApp

/**
 * Every tool the chat offers the model. Reads run at once; writes (needsConfirmation) wait for the user.
 * The web tools run only while [webSearch] (the setting) is on.
 */
fun phoneTools(app: LiquidPocketApp, gate: PermissionGate, webSearch: () -> Boolean): List<Tool> = listOf(
    TimeTool(),
    BatteryTool(app),
    CalendarLookupTool(app, gate),
    ContactsLookupTool(app, gate),
    SearchPhotosTool(app, gate),
    LookAtPhotosTool(app, gate),
    ListNotesTool(app.database.noteDao()),
    CreateEventTool(app, gate),
    CreateReminderTool(app, gate),
    CreateNoteTool(app.database.noteDao()),
    AlarmTool(app),
    RememberTool(app.chats),
    WebSearchTool(appVersion(app), webSearch),
    FetchPageTool(webSearch),
)

private fun appVersion(app: LiquidPocketApp): String =
    app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "0"
