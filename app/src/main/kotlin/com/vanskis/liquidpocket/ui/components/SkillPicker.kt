package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Skill

/** The skills matching what follows "/" in the composer, above it; tapping one picks it for the chat. */
@Composable
fun SkillSuggestions(skills: List<Skill>, onPick: (Skill) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHighest,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp).padding(vertical = 6.dp)) {
            items(skills, key = { it.id }) { skill ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(skill) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("/${skill.name}", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(skill.group, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The chat's skill above the composer; its X drops it. */
@Composable
fun SkillChip(skill: Skill, onDrop: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onDrop,
        label = { Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Stop using ${skill.name}", modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = modifier,
    )
}
