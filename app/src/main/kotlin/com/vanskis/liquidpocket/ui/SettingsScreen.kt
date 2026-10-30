package com.vanskis.liquidpocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.chat.InstructionPresets
import com.vanskis.liquidpocket.ui.components.SkillEditor
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.ui.components.HereticPicker
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.settings.AppSettings
import com.vanskis.liquidpocket.settings.AppTheme
import com.vanskis.liquidpocket.settings.SettingsViewModel
import com.vanskis.liquidpocket.settings.ThinkBudget
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageModel: () -> Unit,
    onOpenMemory: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val benchmarking by vm.benchmarking.collectAsStateWithLifecycle()
    val benchmark by vm.benchmark.collectAsStateWithLifecycle()
    val omniInstalled by vm.omniInstalled.collectAsStateWithLifecycle()
    // Sliders move locally and update the draft when released.
    var contextStop by remember(settings.contextSize) { mutableFloatStateOf(ContextSize.nearestOption(settings.contextSize).toFloat()) }
    val contextSize = ContextSize.OPTIONS[contextStop.roundToInt()]
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var thinkStep by remember(settings.thinkBudget) { mutableFloatStateOf(ThinkBudget.STEPS.indexOf(settings.thinkBudget).toFloat()) }

    val unsaved by vm.unsaved.collectAsStateWithLifecycle()
    val instructions = settings.customInstructions

    // Leaving with unsaved edits asks first.
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = { if (unsaved) { confirmLeave = true } else { onBack() } }
    BackHandler(onBack = leave)
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save changes?") },
            text = { Text("Your changes to the settings are not saved yet.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.save(); onBack() }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmLeave = false; vm.discard(); onBack() }) { Text("Discard") }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = leave) { Text("Back") } },
                actions = { TextButton(onClick = vm::save, enabled = unsaved) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Context size: ${contextSize / 1024}K tokens", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = contextStop,
                onValueChange = { contextStop = it.roundToInt().toFloat() },
                valueRange = 0f..ContextSize.OPTIONS.lastIndex.toFloat(),
                steps = ContextSize.OPTIONS.size - 2,
                onValueChangeFinished = { vm.setContextSize(contextSize) },
            )
            // Follows the slider as it moves, before the size is saved.
            Text(
                "Memory: " + MemoryEstimate.label(ACTIVE_MODEL, contextSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (contextSize > ContextSize.WARN_ABOVE) {
                Text(
                    "Above 32K, replies get slower as the conversation grows, and the app may run out of memory and " +
                        "close. The model never uses more than it was trained for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Longer conversations fit in a larger context, at the cost of memory (a rough estimate: the model plus " +
                    "its cache for every token). Changing it reloads the model.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                String.format(Locale.ROOT, "Temperature: %.1f", temperature),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Slider(
                value = temperature,
                onValueChange = { temperature = (it * 10).roundToInt() / 10f },
                valueRange = 0f..AppSettings.MAX_TEMPERATURE,
                steps = (AppSettings.MAX_TEMPERATURE * 10).roundToInt() - 1,
                onValueChangeFinished = { vm.setTemperature(temperature) },
            )
            Text("Lower is more focused, higher is more varied.", style = MaterialTheme.typography.bodySmall)

            val budget = ThinkBudget.STEPS[thinkStep.roundToInt().coerceIn(0, ThinkBudget.STEPS.lastIndex)]
            // Read when released, not from this composition: the last drag step may not have recomposed yet.
            val stepBudget = { ThinkBudget.STEPS[thinkStep.roundToInt().coerceIn(0, ThinkBudget.STEPS.lastIndex)] }
            Text(
                "Thinking budget: " + ThinkBudget.label(budget),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Slider(
                value = thinkStep,
                onValueChange = { thinkStep = it.roundToInt().toFloat() },
                valueRange = 0f..ThinkBudget.STEPS.lastIndex.toFloat(),
                steps = ThinkBudget.STEPS.size - 2,
                onValueChangeFinished = { vm.setThinkBudget(stepBudget()) },
            )
            Text(
                "How long the model may reason (with Think on) before it must answer. More can help hard " +
                    "questions but takes longer and uses more of the context.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Theme", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Column(modifier = Modifier.selectableGroup()) {
                AppTheme.entries.forEach { theme ->
                    VoiceEngineOption(
                        label = theme.label,
                        detail = theme.detail,
                        selected = settings.theme == theme,
                        onSelect = { vm.setTheme(theme) },
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Web search", style = MaterialTheme.typography.titleMedium)
                    Text("Queries leave the phone (DuckDuckGo, Wikipedia, Jina)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.webSearch, onCheckedChange = vm::setWebSearch)
            }

            Text("Custom instructions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            Text("Added to the system prompt of every new chat.", style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                InstructionPresets.ALL.forEach { (label, text) ->
                    AssistChip(onClick = { vm.setCustomInstructions(text) }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                value = instructions,
                onValueChange = vm::setCustomInstructions,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                minLines = 3,
                supportingText = { Text("${instructions.length} / ${SystemPrompt.MAX_CUSTOM_CHARS}") },
            )

            Text("Skills", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            Text("Type / in a chat to pick one. Add your own here; they are listed after the built-in ones.", style = MaterialTheme.typography.bodySmall)
            SkillEditor(settings.userSkills, vm::setUserSkills, modifier = Modifier.padding(top = 8.dp))

            Text("Voice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Speak replies", style = MaterialTheme.typography.titleMedium)
                    Text("Read each reply aloud as it is written.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.speakReplies, onCheckedChange = vm::setSpeakReplies)
            }
            Text("Voice engine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Column(modifier = Modifier.selectableGroup()) {
                VoiceEngineOption(
                    label = "Model (Omni)",
                    detail = "The model hears your voice messages and answers in its own voice.",
                    selected = settings.voiceEngine == VoiceEngine.MODEL,
                    onSelect = { vm.setVoiceEngine(VoiceEngine.MODEL) },
                )
                VoiceEngineOption(
                    label = "Android",
                    detail = "Android types what you say into the message field and reads replies aloud.",
                    selected = settings.voiceEngine == VoiceEngine.ANDROID,
                    onSelect = { vm.setVoiceEngine(VoiceEngine.ANDROID) },
                )
            }
            Text(
                "Android is also used whenever the loaded model cannot hear or speak.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Live voice model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            val live = LiveVoiceModel.resolve(settings.liveVoiceModel, omniInstalled)
            Column(modifier = Modifier.selectableGroup()) {
                VoiceEngineOption(
                    label = "${MEDIA_MODEL.displayName} (fast, its own voice)",
                    detail = if (omniInstalled) {
                        "Hears you directly and answers in its own voice."
                    } else {
                        "Download it in Manage model to use it."
                    },
                    selected = live == LiveVoiceModel.OMNI,
                    onSelect = { vm.setLiveVoiceModel(LiveVoiceModel.OMNI) },
                    enabled = omniInstalled,
                )
                VoiceEngineOption(
                    label = "${ACTIVE_MODEL.displayName} with Android voice",
                    detail = "Android types what you say; the model can think and search, and Android reads the reply.",
                    selected = live == LiveVoiceModel.TEXT,
                    onSelect = { vm.setLiveVoiceModel(LiveVoiceModel.TEXT) },
                )
            }

            Text("Performance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use NPU (experimental)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.npuAvailable) {
                            "Runs the model on the phone's NPU, or on the CPU when it cannot. Changing it reloads the model."
                        } else {
                            "This build is CPU-only."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.useNpu, onCheckedChange = vm::setUseNpu, enabled = vm.npuAvailable)
            }
            (engineState as? EngineState.Ready)?.acceleratorNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = vm::runBenchmark,
                    enabled = !benchmarking && engineState != EngineState.Generating,
                ) { Text("Run benchmark") }
                if (benchmarking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                if (benchmarking) {
                    "Running…"
                } else {
                    benchmark ?: "Speed and battery use of the loaded model on the CPU and, in builds that have it, the NPU. The NPU is kept on only where it wins."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onOpenMemory, modifier = Modifier.padding(top = 24.dp)) { Text("Things to remember") }
            Text(
                "Facts about you that the model is given at the start of every chat.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Model builds", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            HereticChoice(vm, ACTIVE_MODEL, settings.hereticText, settings.hereticTextRepo, media = false)
            HereticChoice(vm, MEDIA_MODEL, settings.hereticMedia, settings.hereticMediaRepo, media = true)

            OutlinedButton(onClick = onManageModel, modifier = Modifier.padding(top = 24.dp)) { Text("Manage model") }
        }
    }
}

/** Standard or Heretic for [base], with the Heretic build's download state and button. */
@Composable
private fun HereticChoice(vm: SettingsViewModel, base: ModelSpec, use: Boolean, repo: String, media: Boolean) {
    val valid = Heretic.isRepo(repo)
    val spec = Heretic.spec(base, if (valid) repo else "none/none")
    val status by remember(spec.id) { vm.downloadStatus(spec) }.collectAsStateWithLifecycle(DownloadStatus.NotDownloaded(0))
    val shown = use && valid
    HereticPicker(
        model = base.displayName,
        heretic = use,
        repo = repo,
        repoValid = valid,
        onChange = { heretic, text -> vm.setHeretic(media, heretic, text) },
        status = if (shown) downloadLine(status) else null,
        action = when {
            !shown || status == DownloadStatus.Ready -> null
            status.active -> "Cancel"
            else -> "Download"
        },
        onAction = { if (status.active) vm.cancelDownload(spec) else vm.download(spec) },
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun downloadLine(status: DownloadStatus): String = when (status) {
    is DownloadStatus.NotDownloaded ->
        if (status.partialBytes > 0) "Paused at ${status.partialBytes / 1_000_000} MB." else "Not downloaded."
    DownloadStatus.Waiting -> "Waiting for Wi-Fi."
    DownloadStatus.Resolving -> "Looking up the repo…"
    is DownloadStatus.Downloading -> "Downloading ${percent(status.bytes, status.total)}%"
    is DownloadStatus.Verifying -> "Checking the file…"
    DownloadStatus.Ready -> "Downloaded; used from the next reply."
    is DownloadStatus.Failed -> status.message
}

private fun percent(bytes: Long, total: Long) = if (total > 0) (bytes * 100 / total).toInt() else 0

@Composable
private fun VoiceEngineOption(label: String, detail: String, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, enabled = enabled, role = Role.RadioButton)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
