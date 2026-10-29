package com.vanskis.liquidpocket.tools

import com.vanskis.liquidpocket.LiquidPocketApp

/** Every tool the chat offers the model. Reads run at once; writes (needsConfirmation) wait for the user. */
fun phoneTools(app: LiquidPocketApp, gate: PermissionGate): List<Tool> = listOf(
    TimeTool(),
    BatteryTool(app),
    CalendarLookupTool(app, gate),
    ContactsLookupTool(app, gate),
    SearchPhotosTool(app, gate),
    LookAtPhotosTool(app, gate, app.engine),
    ListNotesTool(app.database.noteDao()),
    CreateEventTool(app, gate),
    CreateReminderTool(app, gate),
    CreateNoteTool(app.database.noteDao()),
    AlarmTool(app),
    RememberTool(app.chats),
)
