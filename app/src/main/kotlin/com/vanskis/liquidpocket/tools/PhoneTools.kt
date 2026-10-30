package com.vanskis.liquidpocket.tools

import android.content.pm.PackageManager
import com.vanskis.liquidpocket.LiquidPocketApp

/**
 * Every tool the chat offers the model. Reads run at once; writes (needsConfirmation) wait for the user.
 * The web tools run only while [webSearch] (the setting) is on. Only [ToolCatalog.CORE] is shown to
 * the model in full; it loads the rest with tool_search.
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
    OfferChoicesTool(),
    FetchPageTool(webSearch),
    MathTool(app),
    WeatherTool(webSearch),
    WikipediaTool(appVersion(app), webSearch),
    CurrencyTool(webSearch),
    FlashlightTool(app),
    VolumeTool(app),
    RingerTool(app),
    DoNotDisturbTool(app),
    BrightnessTool(app),
    MediaTool(app),
    OpenAppTool(app),
    OpenSettingsTool(app),
    TimerTool(app),
    ShareTool(app),
)

private fun appVersion(app: LiquidPocketApp): String =
    app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "0"
