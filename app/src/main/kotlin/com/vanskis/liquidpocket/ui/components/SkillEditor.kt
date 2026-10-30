package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.Skills

/** The user's own skills in Settings: each with edit and delete, and a button to add one. */
@Composable
fun SkillEditor(skills: List<Skill>, onChange: (List<Skill>) -> Unit, modifier: Modifier = Modifier) {
    // The id being edited; "" adds a new skill, null shows no dialog.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier) {
        skills.forEach { skill ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("/${skill.name}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        skill.description.ifBlank { skill.instructions },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { editing = skill.id }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${skill.name}") }
                IconButton(onClick = { onChange(skills - skill) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${skill.name}") }
            }
        }
        OutlinedButton(onClick = { editing = "" }, modifier = Modifier.padding(top = 8.dp)) { Text("Add skill") }
    }
    editing?.let { id ->
        val existing = skills.firstOrNull { it.id == id }
        SkillDialog(
            existing,
            onDismiss = { editing = null },
            onSave = { name, description, instructions ->
                val skillId = existing?.id ?: "user-${System.currentTimeMillis()}"
                Skills.custom(skillId, name, description, instructions)?.let { saved ->
                    onChange(if (existing == null) skills + saved else skills.map { if (it.id == id) saved else it })
                }
                editing = null
            },
        )
    }
}

@Composable
private fun SkillDialog(skill: Skill?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(skill?.name.orEmpty()) }
    var description by remember { mutableStateOf(skill?.description.orEmpty()) }
    var instructions by remember { mutableStateOf(skill?.instructions.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (skill == null) "New skill" else "Edit skill") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Skills.MAX_NAME_CHARS) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(Skills.MAX_DESCRIPTION_CHARS) },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it.take(Skills.MAX_INSTRUCTIONS_CHARS) },
                    label = { Text("Instructions for the model") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    supportingText = { Text("${instructions.length} / ${Skills.MAX_INSTRUCTIONS_CHARS}") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, instructions) }, enabled = name.isNotBlank() && instructions.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
