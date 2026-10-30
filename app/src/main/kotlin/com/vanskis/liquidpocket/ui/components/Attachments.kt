package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.AttachmentKind
import com.vanskis.liquidpocket.media.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Draws an attached image file, cropped to fill [Modifier]. The Android screen decodes; elsewhere a placeholder. */
val LocalImageThumbnail = staticCompositionLocalOf<@Composable (path: String, modifier: Modifier) -> Unit> {
    { _, modifier ->
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the chat screen needs for photos and voice beyond [ChatActions]; the Android screen fills it in. */
class ChatMediaUi(
    /** Attachments waiting to go with the next message. */
    val pending: List<Attachment> = emptyList(),
    val onRemovePending: (Attachment) -> Unit = {},
    /** The composer's + menu: pick a photo, take one (null: no camera). The menu hides when both are null. */
    val onPickPhoto: (() -> Unit)? = null,
    val onTakePhoto: (() -> Unit)? = null,
    /** The composer's mic: starts and stops a voice message or dictation; hidden when null. */
    val onMic: (() -> Unit)? = null,
    /** How long the mic has been on; null when it is off. */
    val recordingMillis: Long? = null,
    /** Dictated text to add to the message field, then [onTranscriptUsed]. */
    val transcript: String? = null,
    val onTranscriptUsed: () -> Unit = {},
    /** The top bar's speak-replies toggle; hidden when null. */
    val speakReplies: Boolean? = null,
    val onToggleSpeakReplies: () -> Unit = {},
    /** The top bar's Live button: opens the hands-free live voice screen; hidden when null. */
    val onLive: (() -> Unit)? = null,
)

/** The attachments waiting in the composer, each with a remove button. */
@Composable
fun PendingAttachments(items: List<Attachment>, onRemove: (Attachment) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Box {
                when (item.kind) {
                    AttachmentKind.IMAGE -> LocalImageThumbnail.current(item.path, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    AttachmentKind.AUDIO -> VoiceChip(item.path, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
                }
                IconButton(
                    onClick = { onRemove(item) },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** A user message's photos and voice messages, above its text. */
@Composable
fun MessageAttachments(items: List<Attachment>, modifier: Modifier = Modifier) {
    val images = items.filter { it.kind == AttachmentKind.IMAGE }
    val clips = items.filter { it.kind == AttachmentKind.AUDIO }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (images.isNotEmpty()) {
            val side: Dp = if (images.size == 1) 200.dp else 120.dp
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEach { LocalImageThumbnail.current(it.path, Modifier.size(side).clip(RoundedCornerShape(16.dp))) }
            }
        }
        clips.forEach { VoiceChip(it.path) }
    }
}

/** "voice message · 0:04". */
@Composable
fun VoiceChip(path: String, modifier: Modifier = Modifier) {
    val millis by produceState<Long?>(null, path) { value = withContext(Dispatchers.IO) { Wav.durationMillis(File(path)) } }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                "voice message" + (millis?.let { " · " + clock(it) } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** The composer's +: a small menu to pick a photo or, when [onTakePhoto] is given, take one. */
@Composable
fun AttachMenuButton(enabled: Boolean, onPickPhoto: (() -> Unit)?, onTakePhoto: (() -> Unit)?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Filled.Add, contentDescription = "Add a photo") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onPickPhoto?.let { pick ->
                DropdownMenuItem(
                    text = { Text("Photo from gallery") },
                    leadingIcon = { Icon(AppIcons.Image, contentDescription = null) },
                    onClick = { open = false; pick() },
                )
            }
            onTakePhoto?.let { take ->
                DropdownMenuItem(
                    text = { Text("Take a photo") },
                    leadingIcon = { Icon(AppIcons.PhotoCamera, contentDescription = null) },
                    onClick = { open = false; take() },
                )
            }
        }
    }
}

/** The composer's mic. [recordingMillis] is non-null while it records or listens: a timer and a stop button then. */
@Composable
fun MicButton(enabled: Boolean, onMic: () -> Unit, recordingMillis: Long?) {
    if (recordingMillis == null) {
        IconButton(onClick = onMic, enabled = enabled) { Icon(AppIcons.Mic, contentDescription = "Record a voice message") }
    } else {
        Text(
            clock(recordingMillis),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = onMic,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AppIcons.Mic, contentDescription = "Stop recording")
                Box(
                    Modifier.align(Alignment.TopEnd).size(8.dp)
                        .background(LocalContentColor.current, CircleShape),
                )
            }
        }
    }
}

private fun clock(millis: Long): String {
    val seconds = millis / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
