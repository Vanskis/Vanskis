package com.vanskis.liquidpocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.settings.AppSettings
import com.vanskis.liquidpocket.settings.SettingsViewModel
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onManageModel: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Sliders move locally and persist when released, so dragging does not write on every frame.
    var contextSize by remember(settings.contextSize) { mutableFloatStateOf(settings.contextSize.toFloat()) }
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }

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
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
            Text(
                "Longer conversations fit in a larger context, at the cost of memory. Changing it reloads the model.",
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

            OutlinedButton(onClick = onManageModel, modifier = Modifier.padding(top = 24.dp)) { Text("Manage model") }
        }
    }
}
