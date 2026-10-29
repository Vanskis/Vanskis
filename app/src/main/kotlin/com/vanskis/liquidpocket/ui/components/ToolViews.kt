package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.ToolConfirmation
import com.vanskis.liquidpocket.tools.ToolResults

/** What a tool did, in the transcript's words. */
fun toolLabel(toolName: String?): String = when (toolName) {
    "get_time" -> "Checked the time"
    "get_battery" -> "Checked the battery"
    "calendar_lookup" -> "Checked the calendar"
    "contacts_lookup" -> "Looked up contacts"
    "search_photos" -> "Searched photos"
    "look_at_photos" -> "Looked at photos"
    "list_notes" -> "Checked notes"
    "create_calendar_event" -> "Calendar event"
    "create_reminder" -> "Reminder"
    "create_note" -> "Note"
    "set_alarm" -> "Alarm"
    "remember" -> "Remembered a fact"
    null -> "Tool"
    else -> toolName
}

/** The title on the card asking to approve [toolName]. */
private fun confirmationTitle(toolName: String): String = when (toolName) {
    "create_calendar_event" -> "Add to calendar?"
    "create_reminder" -> "Create a reminder?"
    "create_note" -> "Save a note?"
    "set_alarm" -> "Set an alarm?"
    "remember" -> "Remember this?"
    else -> "Allow ${toolName}?"
}

/** Asks the user to approve a write the model wants to make. Nothing happens until they do. */
@Composable
fun ToolConfirmationCard(
    confirmation: ToolConfirmation,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(confirmationTitle(confirmation.toolName), style = MaterialTheme.typography.titleMedium)
            Text(confirmation.description, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onApprove, modifier = Modifier.padding(start = 8.dp)) { Text("Approve") }
            }
        }
    }
}

/** A tool's result as one muted line ("Checked the calendar"); tapping shows what the model got. */
@Composable
fun ToolMessage(toolName: String?, text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = toolLabel(toolName) + if (ToolResults.isDeclined(text)) " · declined" else ""
    ToolNote(label, modifier, onClick = { expanded = !expanded }, expanded = expanded) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

/** The photos a tool showed the model (a turn the app sent for it). */
@Composable
fun ToolPhotosMessage(count: Int, modifier: Modifier = Modifier) {
    val label = when (count) {
        0 -> "Showed photos to the model"
        1 -> "Showed 1 photo to the model"
        else -> "Showed $count photos to the model"
    }
    ToolNote(label, modifier)
}

@Composable
private fun ToolNote(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    details: @Composable () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
                if (onClick != null) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = if (expanded) "Hide result" else "Show result",
                        modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                    )
                }
            }
            if (expanded) details()
        }
    }
}
