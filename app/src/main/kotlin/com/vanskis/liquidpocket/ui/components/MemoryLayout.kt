package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Fact
import com.vanskis.liquidpocket.chat.SystemPrompt
import java.util.Locale

/** "Things to remember": facts the model is given at the start of every chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryLayout(
    facts: List<Fact>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    // Fact being edited: null for none, NEW_FACT for the add dialog.
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Fact?>(null) }

    val texts = facts.map { it.text }
    val fitting = SystemPrompt.fittingCount(texts)
    val used = SystemPrompt.normalize(texts.take(fitting)).sumOf { it.length }
    val budget = SystemPrompt.FACTS_BUDGET_CHARS

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Things to remember") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingId = NEW_FACT },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "The model reads these at the start of every chat, oldest first. Up to " +
                            String.format(Locale.ROOT, "%,d", budget) + " characters in total are used, " +
                            "${SystemPrompt.MAX_FACT_CHARS} per item; items past that are left out.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { used.toFloat() / budget },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        String.format(Locale.ROOT, "%,d of %,d characters used", used, budget),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (facts.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Add things like your name, where you live, or how you like answers.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(facts, key = { _, fact -> fact.id }) { index, fact ->
                ListItem(
                    headlineContent = { Text(fact.text) },
                    supportingContent = if (index >= fitting) {
                        { Text("Not used: over the limit", color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { editingId = fact.id }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { deleting = fact }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    },
                )
            }
        }
    }

    editingId?.let { id ->
        val existing = facts.firstOrNull { it.id == id }
        // A fact deleted while its dialog was open (or not loaded yet after a restore) has nothing to edit.
        if (id == NEW_FACT || existing != null) FactDialog(
            initial = existing?.text.orEmpty(),
            title = if (existing == null) "Add something to remember" else "Edit",
            onDismiss = { editingId = null },
            onConfirm = { text ->
                if (existing == null) onAdd(text) else onUpdate(existing.id, text)
                editingId = null
            },
        )
    }

    deleting?.let { fact ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Forget this?") },
            text = { Text(fact.text) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(fact.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private const val NEW_FACT = -1L

@Composable
private fun FactDialog(initial: String, title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val max = SystemPrompt.MAX_FACT_CHARS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(max) },
                placeholder = { Text("e.g. I'm vegetarian") },
                supportingText = { Text("${text.length} / $max") },
                minLines = 2,
                maxLines = 5,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
