package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.access.SharedDocument
import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.Skills

/**
 * The message pill at the bottom, like Gemini's: + (photo and document menu), the field, mic, the
 * context ring and Send (Stop while a reply streams).
 *
 * The + menu shows when [onPickPhoto], [onTakePhoto] or [onPickDocument] is given, the mic when [onMic] is; both
 * are disabled unless [attachEnabled] (the mic's stop stays live while it records). The
 * [ContextRing] shows once [contextTotal] is known; its Compact now runs [onCompact], disabled when
 * that is null. [pending] attachments show above the field, each with a remove button, then the
 * documents still [extracting]; [shared] documents show as chips above the pill. The Think chip
 * (selected when [think]) shows when [onToggleThink] is given. [focusRequester] is attached to the field.
 * Typing "/" lists the [skills] matching what follows it; picking one calls [onPickSkill] and clears
 * the field. The chat's [skill] shows as a chip above the pill, which [onDropSkill] removes.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    pending: List<Attachment> = emptyList(),
    onRemovePending: (Attachment) -> Unit = {},
    extracting: List<DocumentExtraction> = emptyList(),
    onCancelExtraction: (DocumentExtraction) -> Unit = {},
    shared: List<SharedDocument> = emptyList(),
    onUseShared: (SharedDocument) -> Unit = {},
    attachEnabled: Boolean = true,
    onPickPhoto: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onPickDocument: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onMic: (() -> Unit)? = null,
    recordingMillis: Long? = null,
    contextUsed: Int = 0,
    contextTotal: Int = 0,
    onCompact: (() -> Unit)? = null,
    think: Boolean = false,
    onToggleThink: (() -> Unit)? = null,
    skills: List<Skill> = emptyList(),
    skill: Skill? = null,
    onPickSkill: ((Skill) -> Unit)? = null,
    onDropSkill: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val command = if (editing || onPickSkill == null) null else Skills.command(value)
    val suggestions = command?.let { Skills.matching(skills, it) }.orEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (shared.isNotEmpty()) SharedDocumentChips(shared, onUseShared, modifier = Modifier.padding(bottom = 4.dp))
        if (suggestions.isNotEmpty() && onPickSkill != null) {
            SkillSuggestions(suggestions, onPick = { onPickSkill(it); onValueChange("") }, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (skill != null) SkillChip(skill, onDropSkill, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                if (editing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                        Text(
                            "Editing your last message",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelEdit) { Text("Cancel") }
                    }
                }
                if (pending.isNotEmpty() || extracting.isNotEmpty()) {
                    PendingAttachments(
                        pending,
                        onRemovePending,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                        extracting = extracting,
                        onCancelExtraction = onCancelExtraction,
                    )
                }
                // Two rows: the field gets the full width, the buttons go under it.
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
                    placeholder = { Text("Ask Liquid Pocket") },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp, end = 4.dp)) {
                    if (onPickPhoto != null || onTakePhoto != null || onPickDocument != null) {
                        AttachMenuButton(
                            enabled = attachEnabled && recordingMillis == null,
                            onPickPhoto = onPickPhoto,
                            onTakePhoto = onTakePhoto,
                            onPickDocument = onPickDocument,
                        )
                    }
                    onToggleThink?.let { ThinkChip(think, it) }
                    Spacer(modifier = Modifier.weight(1f))
                    onMic?.let { MicButton(enabled = attachEnabled, onMic = it, recordingMillis = recordingMillis) }
                    if (contextTotal > 0) {
                        ContextRing(
                            used = contextUsed,
                            total = contextTotal,
                            onCompact = { onCompact?.invoke() },
                            compactEnabled = onCompact != null && !generating,
                        )
                    }
                    if (generating) {
                        RoundAction(
                            onClick = onStop,
                            enabled = true,
                            filled = SolidColor(colors.inverseSurface),
                            contentColor = colors.inverseOnSurface,
                        ) { Icon(AppIcons.Stop, contentDescription = "Stop the reply", modifier = Modifier.size(20.dp)) }
                    } else {
                        RoundAction(
                            onClick = onSend,
                            enabled = canSend,
                            filled = if (canSend) LocalAppStyle.current.brush else SolidColor(colors.onSurface.copy(alpha = 0.10f)),
                            contentColor = if (canSend) LocalAppStyle.current.onAccent else colors.onSurface.copy(alpha = 0.38f),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (editing) "Send the edited message" else "Send",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Think": whether replies reason before answering. */
@Composable
private fun ThinkChip(think: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = think,
        onClick = onToggle,
        label = { Text("Think") },
        leadingIcon = { Icon(AppIcons.Lightbulb, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** A 40 dp round button filled with [filled] (a gradient or a solid color). */
@Composable
private fun RoundAction(
    onClick: () -> Unit,
    enabled: Boolean,
    filled: Brush,
    contentColor: Color,
    icon: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 4.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(filled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = icon)
    }
}
