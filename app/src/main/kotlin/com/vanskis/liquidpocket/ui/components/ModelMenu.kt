package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.ModelChoice
import com.vanskis.liquidpocket.settings.ThinkBudget

/**
 * What the composer's model menu shows: the thinking level ([think] and its [budget]) and the
 * installed [models], [selected] being the one the next reply goes to.
 */
data class ModelMenuState(
    val think: Boolean,
    val budget: Int,
    val models: List<ModelChoice>,
    val selected: String?,
) {
    val current: ModelChoice? get() = models.firstOrNull { it.id == selected }

    /** Thinking levels are offered unless the chosen model is known not to think. */
    val canThink: Boolean get() = current?.thinks ?: true
}

/**
 * The composer chip naming the model and its thinking level ("LFM3-1.7B · Medium"); a tap opens a
 * menu of thinking levels (Off or a [ThinkBudget.PRESETS] budget, hidden for a model that cannot
 * think) and the installed models. [onThinking] gets whether to think and the budget; [onModel] a model id.
 */
@Composable
fun ModelMenuChip(
    state: ModelMenuState,
    onThinking: (think: Boolean, budget: Int) -> Unit,
    onModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val name = state.current?.name ?: "Model"
    val label = if (state.canThink && state.think) "$name · ${ThinkBudget.level(true, state.budget)}" else name
    Box(modifier = modifier.padding(end = 4.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = colors.onSurfaceVariant, trailingIconContentColor = colors.onSurfaceVariant),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.canThink) {
                MenuHeading("Thinking")
                MenuChoice("Off", null, checked = !state.think) { open = false; onThinking(false, state.budget) }
                ThinkBudget.PRESETS.forEach { (level, budget) ->
                    MenuChoice(level, ThinkBudget.label(budget), checked = state.think && state.budget == budget) {
                        open = false
                        onThinking(true, budget)
                    }
                }
                if (state.models.isNotEmpty()) HorizontalDivider()
            }
            if (state.models.isNotEmpty()) {
                MenuHeading("Model")
                state.models.forEach { model ->
                    MenuChoice(model.name, null, checked = model.id == state.selected) {
                        open = false
                        if (model.id != state.selected) onModel(model.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MenuChoice(text: String, detail: String?, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (detail == null) text else "$text · $detail") },
        trailingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
        onClick = onClick,
    )
}
