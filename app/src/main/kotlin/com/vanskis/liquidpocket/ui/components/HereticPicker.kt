package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Standard or Heretic for one model. Heretic takes a Hugging Face GGUF repo the user enters;
 * [status] and [action] describe its download (null hides them), and [onAction] runs the action.
 * [suggested] is a known repo for this model: picking Heretic with no repo fills it in.
 */
@Composable
fun HereticPicker(
    model: String,
    heretic: Boolean,
    repo: String,
    repoValid: Boolean,
    onChange: (heretic: Boolean, repo: String) -> Unit,
    status: String?,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    suggested: String? = null,
) {
    Column(modifier = modifier) {
        Text(model, style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.selectableGroup()) {
            Choice("Standard", "The official build from Liquid AI.", !heretic) { onChange(false, repo) }
            Choice("Heretic", "A build with refusals removed, from a Hugging Face GGUF repo you choose.", heretic) {
                onChange(true, repo.ifEmpty { suggested.orEmpty() })
            }
        }
        if (!heretic) return@Column
        OutlinedTextField(
            value = repo,
            onValueChange = { onChange(true, it.trim()) },
            label = { Text("Hugging Face repo") },
            placeholder = { Text(suggested ?: "e.g. your-name/$model-heretic-GGUF") },
            singleLine = true,
            isError = repo.isNotEmpty() && !repoValid,
            supportingText = {
                Text(
                    if (repo.isNotEmpty() && !repoValid) {
                        "Use owner/name, as in the repo's address."
                    } else {
                        "Until it is downloaded, the standard model is used."
                    },
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (suggested != null && repo != suggested) {
            TextButton(onClick = { onChange(true, suggested) }) { Text("Use suggested: $suggested") }
        }
        if (status != null || action != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
                action?.let { OutlinedButton(onClick = onAction) { Text(it) } }
            }
        }
    }
}

@Composable
private fun Choice(label: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
