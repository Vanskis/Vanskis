package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.AttachmentKind
import com.vanskis.liquidpocket.access.SharedDocument
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

/**
 * A document being read before it is attached: [done] of [total] pages, scanned pages with OCR when
 * [ocr]. [name] is null until known (a picked file). [key] tells extractions apart.
 */
data class DocumentExtraction(val key: Long, val name: String?, val ocr: Boolean = false, val done: Int = 0, val total: Int = 0)

/** What the chat screen needs for photos, documents and voice beyond [ChatActions]; the Android screen fills it in. */
class ChatMediaUi(
    /** Attachments waiting to go with the next message. */
    val pending: List<Attachment> = emptyList(),
    val onRemovePending: (Attachment) -> Unit = {},
    /** Documents still being read; each shows as a chip with its progress and a cancel button. */
    val extracting: List<DocumentExtraction> = emptyList(),
    val onCancelExtraction: (DocumentExtraction) -> Unit = {},
    /** Documents shared from other apps, shown above the composer; a tap reads and attaches one. */
    val shared: List<SharedDocument> = emptyList(),
    val onUseShared: (SharedDocument) -> Unit = {},
    /**
     * The composer's + menu: pick a photo, take one (null: no camera), attach a PDF or text file.
     * The menu hides when all are null.
     */
    val onPickPhoto: (() -> Unit)? = null,
    val onTakePhoto: (() -> Unit)? = null,
    val onPickDocument: (() -> Unit)? = null,
    /** Attached to the message field, so the screen can focus it (the widget's Ask). */
    val focusRequester: FocusRequester? = null,
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

/** The attachments waiting in the composer, each with a remove button, then the documents still being read. */
@Composable
fun PendingAttachments(
    items: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    extracting: List<DocumentExtraction> = emptyList(),
    onCancelExtraction: (DocumentExtraction) -> Unit = {},
) {
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
                    AttachmentKind.DOCUMENT -> DocumentChip(item.name, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
                }
                RemoveButton("Remove attachment", Modifier.align(Alignment.TopEnd)) { onRemove(item) }
            }
        }
        extracting.forEach { item ->
            Box {
                ExtractingChip(item, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
                RemoveButton("Stop reading the document", Modifier.align(Alignment.TopEnd)) { onCancelExtraction(item) }
            }
        }
    }
}

@Composable
private fun RemoveButton(description: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(24.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        Icon(Icons.Filled.Close, contentDescription = description, modifier = Modifier.size(14.dp))
    }
}

/** A user message's photos, voice messages and documents, above its text. */
@Composable
fun MessageAttachments(items: List<Attachment>, modifier: Modifier = Modifier) {
    val images = items.filter { it.kind == AttachmentKind.IMAGE }
    val clips = items.filter { it.kind == AttachmentKind.AUDIO }
    val documents = items.filter { it.kind == AttachmentKind.DOCUMENT }
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
        documents.forEach { DocumentChip(it.name) }
    }
}

/** A document icon and its file [name] ("document" when unknown). */
@Composable
fun DocumentChip(name: String?, modifier: Modifier = Modifier) {
    ChipSurface(modifier) {
        Icon(AppIcons.Description, contentDescription = null, modifier = Modifier.size(16.dp))
        ChipLabel(name ?: "document")
    }
}

/** "Reading report.pdf · 3/12", or "Scanning" while OCR reads scanned pages, with a spinner. */
@Composable
private fun ExtractingChip(item: DocumentExtraction, modifier: Modifier = Modifier) {
    ChipSurface(modifier) {
        CircularProgressIndicator(
            progress = { if (item.total > 0) item.done.toFloat() / item.total else 0f },
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        val count = if (item.total > 0) " · ${item.done}/${item.total}" else ""
        ChipLabel((if (item.ocr) "Scanning " else "Reading ") + (item.name ?: "document") + count)
    }
}

@Composable
private fun ChipSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { content() }
    }
}

@Composable
private fun ChipLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp).widthIn(max = 220.dp),
    )
}

/** Documents shared from other apps, one chip each; a tap attaches it to the next message. */
@Composable
fun SharedDocumentChips(documents: List<SharedDocument>, onUse: (SharedDocument) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        documents.forEach { document ->
            AssistChip(
                onClick = { onUse(document) },
                label = { Text("Attach ${document.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(AppIcons.Description, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
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

/** The composer's +: a small menu to pick a photo, take one, or attach a document; each entry shows when given. */
@Composable
fun AttachMenuButton(
    enabled: Boolean,
    onPickPhoto: (() -> Unit)?,
    onTakePhoto: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onPickDocument: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Filled.Add, contentDescription = "Add a photo or document") }
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
            onPickDocument?.let { pick ->
                DropdownMenuItem(
                    text = { Text("Attach document") },
                    leadingIcon = { Icon(AppIcons.Description, contentDescription = null) },
                    onClick = { open = false; pick() },
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
