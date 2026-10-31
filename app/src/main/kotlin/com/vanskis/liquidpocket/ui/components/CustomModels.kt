package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.core.CustomModel
import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.core.RepoFile
import com.vanskis.liquidpocket.core.TemplateTraits
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.download.percent
import kotlinx.coroutines.flow.Flow

/** The Add model dialog's lookup of a Hugging Face repo. */
sealed interface RepoLookup {
    data object Idle : RepoLookup
    data object Loading : RepoLookup

    /** The repo's model [files] (no projectors or shards), what its template says, and whether it has a projector. */
    data class Found(val repo: String, val files: List<RepoFile>, val traits: TemplateTraits, val vision: Boolean) : RepoLookup
    data class Failed(val message: String) : RepoLookup
}

/** What the custom models section does; each change is stored at once. */
class CustomModelActions(
    val status: (ModelSpec) -> Flow<DownloadStatus>,
    val lookUp: (repo: String) -> Unit,
    val dismissLookup: () -> Unit,
    /** Stores the model and starts its download. */
    val add: (CustomModel) -> Unit,
    /** Opens the file picker for a .gguf on the phone. */
    val import: () -> Unit,
    val change: (CustomModel) -> Unit,
    val remove: (CustomModel) -> Unit,
    val download: (ModelSpec) -> Unit,
    val cancel: (ModelSpec) -> Unit,
)

/**
 * Settings' custom models: each with its download state, what it can do (detected, and switchable)
 * and an optional Heretic build; then Add from Hugging Face and Import a .gguf. [importing] is the
 * progress line of an import under way.
 */
@Composable
fun CustomModelsSection(models: List<CustomModel>, lookup: RepoLookup, importing: String?, actions: CustomModelActions) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Custom models", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Any GGUF chat model from Hugging Face, or a .gguf on this phone. Pick one in the chat's model menu.",
            style = MaterialTheme.typography.bodySmall,
        )
        models.forEach { CustomModelCard(it, actions) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("Add from Hugging Face") }
            OutlinedButton(onClick = actions.import, enabled = importing == null) { Text("Import .gguf") }
        }
        importing?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (adding) {
        AddModelDialog(lookup, actions, onClose = {
            adding = false
            actions.dismissLookup()
        })
    }
}

@Composable
private fun CustomModelCard(model: CustomModel, actions: CustomModelActions) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text(if (model.local) "Imported from this phone" else "${model.repo} · ${model.file}", style = MaterialTheme.typography.bodySmall)
            if (!model.local) DownloadRow(model.spec, actions)
            Trait("Sees photos", model.vision, enabled = !model.local) { actions.change(model.copy(vision = it)) }
            Trait("Uses tools", model.tools) { actions.change(model.copy(tools = it)) }
            Trait("Thinks", model.thinks) { actions.change(model.copy(thinks = it)) }
            HereticRow(model, actions)
            TextButton(onClick = { actions.remove(model) }) { Text("Remove") }
        }
    }
}

@Composable
private fun DownloadRow(spec: ModelSpec, actions: CustomModelActions) {
    val status by remember(spec) { actions.status(spec) }.collectAsState(DownloadStatus.NotDownloaded(0))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(downloadLine(status), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        when {
            status == DownloadStatus.Ready -> Unit
            status.active -> TextButton(onClick = { actions.cancel(spec) }) { Text("Cancel") }
            else -> TextButton(onClick = { actions.download(spec) }) { Text("Download") }
        }
    }
}

@Composable
private fun Trait(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A Heretic build's repo for [model], then its download state once the repo is valid. */
@Composable
private fun HereticRow(model: CustomModel, actions: CustomModelActions) {
    var text by remember(model.id, model.hereticRepo) { mutableStateOf(model.hereticRepo) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Heretic build (optional)") },
            placeholder = { Text("owner/repo-GGUF") },
            singleLine = true,
            isError = text.isNotBlank() && !Heretic.isRepo(text),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { actions.change(model.copy(hereticRepo = text.trim())) },
            enabled = text.trim() != model.hereticRepo && (text.isBlank() || Heretic.isRepo(text)),
        ) { Text("Set") }
    }
    model.heretic?.let { DownloadRow(it, actions) }
}

@Composable
private fun AddModelDialog(lookup: RepoLookup, actions: CustomModelActions, onClose: () -> Unit) {
    var repo by remember { mutableStateOf("") }
    var file by remember(lookup) { mutableStateOf((lookup as? RepoLookup.Found)?.files?.let(::preferredFile)) }
    val found = lookup as? RepoLookup.Found
    var vision by remember(lookup) { mutableStateOf(found?.vision ?: false) }
    var tools by remember(lookup) { mutableStateOf(found?.traits?.tools ?: false) }
    var thinks by remember(lookup) { mutableStateOf(found?.traits?.thinks ?: false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add from Hugging Face") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        label = { Text("GGUF repo") },
                        placeholder = { Text("owner/model-GGUF") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { actions.lookUp(repo.trim()) }, enabled = Heretic.isRepo(repo) && lookup != RepoLookup.Loading) {
                        Text("Look up")
                    }
                }
                when (lookup) {
                    RepoLookup.Idle -> Unit
                    RepoLookup.Loading -> Text("Looking up the repo…", style = MaterialTheme.typography.bodySmall)
                    is RepoLookup.Failed -> Text(lookup.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is RepoLookup.Found -> {
                        if (lookup.files.isEmpty()) Text("No single-file GGUF models in this repo.", style = MaterialTheme.typography.bodySmall)
                        lookup.files.forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().selectable(selected = file == f, onClick = { file = f }, role = Role.RadioButton),
                            ) {
                                RadioButton(selected = file == f, onClick = null)
                                Text(
                                    "${f.path.substringAfterLast('/')} · ${f.size / 1_000_000} MB",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Text("Detected from the repo; change if wrong.", style = MaterialTheme.typography.bodySmall)
                        Trait("Sees photos", vision, enabled = lookup.vision) { vision = it }
                        Trait("Uses tools", tools) { tools = it }
                        Trait("Thinks", thinks) { thinks = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val f = file ?: return@TextButton
                    val r = found?.repo ?: return@TextButton
                    actions.add(CustomModel(CustomModel.idFor(r, f.path), CustomModel.nameFor(f.path), r, f.path, vision, tools, thinks))
                    onClose()
                },
                enabled = found != null && file != null,
            ) { Text("Add and download") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Q4_K_M when there is one (small and good), else the smallest file. */
private fun preferredFile(files: List<RepoFile>): RepoFile? =
    files.firstOrNull { "q4_k_m" in it.path.lowercase() } ?: files.minByOrNull { it.size }

/** One line on a model's download, as Settings shows it. */
fun downloadLine(status: DownloadStatus): String = when (status) {
    is DownloadStatus.NotDownloaded ->
        if (status.partialBytes > 0) "Paused at ${status.partialBytes / 1_000_000} MB." else "Not downloaded."
    DownloadStatus.Waiting -> "Waiting for Wi-Fi."
    DownloadStatus.Resolving -> "Looking up the repo…"
    is DownloadStatus.Downloading -> "Downloading ${percent(status.bytes, status.total)}%"
    is DownloadStatus.Verifying -> "Checking the file…"
    DownloadStatus.Ready -> "Downloaded; used from the next reply."
    is DownloadStatus.Failed -> status.message
}
