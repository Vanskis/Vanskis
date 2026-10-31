package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.ModelChoice
import com.vanskis.liquidpocket.engine.Sampling
import com.vanskis.liquidpocket.settings.ModelTuning
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings' per-model section: each model with a summary of its own settings, and a Tune button
 * opening [TuningDialog]. [onChange] gets the model id and its new settings.
 */
@Composable
fun ModelTuningSection(models: List<ModelChoice>, tunings: Map<String, ModelTuning>, onChange: (String, ModelTuning) -> Unit) {
    var editing by remember { mutableStateOf<ModelChoice?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Per-model settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        Text("Sampling, reply length and instructions for one model; a Heretic build uses its model's.", style = MaterialTheme.typography.bodySmall)
        models.forEach { model ->
            val tuning = tunings[model.id] ?: ModelTuning()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.bodyLarge)
                    Text(summary(tuning), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { editing = model }) { Text("Tune") }
            }
        }
    }
    editing?.let { model ->
        TuningDialog(model.name, tunings[model.id] ?: ModelTuning(), onDone = {
            onChange(model.id, it)
            editing = null
        }, onDismiss = { editing = null })
    }
}

/** "Defaults", or what differs from them. */
fun summary(t: ModelTuning): String {
    val d = ModelTuning()
    val s = t.sampling
    val parts = listOfNotNull(
        "top-p ${num(s.topP)}".takeIf { s.topP != d.sampling.topP },
        "top-k ${s.topK}".takeIf { s.topK != d.sampling.topK },
        "min-p ${num(s.minP)}".takeIf { s.minP != d.sampling.minP },
        "repeat ${num(s.repeatPenalty)}".takeIf { s.repeatPenalty != d.sampling.repeatPenalty },
        "seed ${s.seed}".takeIf { s.seed != Sampling.RANDOM_SEED },
        "max ${t.maxReply} tokens".takeIf { t.maxReply > 0 },
        "own instructions".takeIf { t.instructions.isNotBlank() },
    )
    return if (parts.isEmpty()) "Defaults" else parts.joinToString(" · ")
}

private fun num(v: Float) = String.format(Locale.ROOT, "%.2f", v)

@Composable
private fun TuningDialog(name: String, initial: ModelTuning, onDone: (ModelTuning) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    var seedText by remember { mutableStateOf(initial.sampling.seed.takeIf { it >= 0 }?.toString().orEmpty()) }
    fun sampling(change: (Sampling) -> Sampling) { t = t.copy(sampling = change(t.sampling)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Labeled("Top-p: ${num(t.sampling.topP)}", "Keeps the likeliest words that add up to this share; 1 is off.") {
                    Slider(t.sampling.topP, { v -> sampling { it.copy(topP = (v * 20).roundToInt() / 20f) } }, valueRange = 0f..1f)
                }
                Labeled("Top-k: ${if (t.sampling.topK == 0) "off" else t.sampling.topK}", "Keeps only this many likeliest words; 0 is off.") {
                    Slider(t.sampling.topK.toFloat(), { v -> sampling { it.copy(topK = v.roundToInt()) } }, valueRange = 0f..ModelTuning.MAX_TOP_K.toFloat())
                }
                Labeled("Min-p: ${num(t.sampling.minP)}", "Drops words far less likely than the top one; 0 is off.") {
                    Slider(t.sampling.minP, { v -> sampling { it.copy(minP = (v * 100).roundToInt() / 100f) } }, valueRange = 0f..0.5f)
                }
                Labeled("Repeat penalty: ${num(t.sampling.repeatPenalty)}", "Above 1 discourages repeating recent words.") {
                    Slider(t.sampling.repeatPenalty, { v -> sampling { it.copy(repeatPenalty = (v * 20).roundToInt() / 20f) } }, valueRange = 1f..1.5f)
                }
                Text("Longest reply", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                ModelTuning.MAX_REPLY_OPTIONS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { n ->
                            FilterChip(
                                selected = t.maxReply == n,
                                onClick = { t = t.copy(maxReply = n) },
                                label = { Text(if (n == 0) "No limit" else tokensLabel(n)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = seedText,
                    onValueChange = { v ->
                        val digits = v.filter(Char::isDigit).take(9)
                        seedText = digits
                        sampling { it.copy(seed = digits.toIntOrNull() ?: Sampling.RANDOM_SEED) }
                    },
                    label = { Text("Seed") },
                    placeholder = { Text("Random") },
                    supportingText = { Text("The same seed and message give the same reply.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = t.instructions,
                    onValueChange = { t = t.copy(instructions = it.take(ModelTuning.MAX_INSTRUCTIONS)) },
                    label = { Text("Instructions for this model") },
                    supportingText = { Text("Added to your custom instructions when this model answers.") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(onClick = { t = ModelTuning(); seedText = "" }, enabled = t != ModelTuning()) { Text("Reset to defaults") }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(t) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun tokensLabel(n: Int) = if (n >= 1024) "${n / 1024}K" else "$n"

@Composable
private fun Labeled(label: String, hint: String, control: @Composable () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall)
    control()
}
