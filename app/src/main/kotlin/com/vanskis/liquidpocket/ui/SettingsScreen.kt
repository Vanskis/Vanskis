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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.settings.AppSettings
import com.vanskis.liquidpocket.settings.SettingsViewModel
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val INSTRUCTIONS_SAVE_DELAY_MS = 500L

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
    // Sliders move locally and persist when released, so dragging does not write on every frame.
    var contextSize by remember(settings.contextSize) { mutableFloatStateOf(settings.contextSize.toFloat()) }
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }

    // Seeded once and saved debounced, so typing is never overwritten by the stored value coming back.
    var instructions by rememberSaveable { mutableStateOf(settings.customInstructions) }
    var instructionsEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(instructions) {
        if (!instructionsEdited) return@LaunchedEffect
        delay(INSTRUCTIONS_SAVE_DELAY_MS)
        vm.setCustomInstructions(instructions)
    }
    // Leaving the screen inside the delay still saves.
    val latestInstructions by rememberUpdatedState(instructions)
    val latestEdited by rememberUpdatedState(instructionsEdited)
    DisposableEffect(Unit) {
        onDispose { if (latestEdited) vm.setCustomInstructions(latestInstructions) }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val step = AppSettings.CONTEXT_STEP
            Text("Context size: ${contextSize.roundToInt() / step}K tokens", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = contextSize,
                onValueChange = { contextSize = ((it / step).roundToInt() * step).toFloat() },
                valueRange = ContextSize.MIN.toFloat()..ContextSize.MAX.toFloat(),
                steps = (ContextSize.MAX - ContextSize.MIN) / step - 1,
                onValueChangeFinished = { vm.setContextSize(contextSize.roundToInt()) },
            )
            // Follows the slider as it moves, before the size is saved.
            Text(
                "Memory: " + MemoryEstimate.label(ACTIVE_MODEL, contextSize.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
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

            Row(modifier = Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use wallpaper colors", style = MaterialTheme.typography.titleMedium)
                    Text("Off uses the app's own colors.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.dynamicColor, onCheckedChange = vm::setDynamicColor)
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
                    AssistChip(onClick = { instructions = text; instructionsEdited = true }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                value = instructions,
                onValueChange = {
                    instructions = it.take(SystemPrompt.MAX_CUSTOM_CHARS)
                    instructionsEdited = true
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                minLines = 3,
                supportingText = { Text("${instructions.length} / ${SystemPrompt.MAX_CUSTOM_CHARS}") },
            )

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
                    benchmark ?: "Decode speed of the loaded model on the CPU and, in builds that have it, the NPU."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onOpenMemory, modifier = Modifier.padding(top = 24.dp)) { Text("Things to remember") }
            Text(
                "Facts about you that the model is given at the start of every chat.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onManageModel, modifier = Modifier.padding(top = 24.dp)) { Text("Manage model") }
        }
    }
}

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
