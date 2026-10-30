package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.ChatSearch
import com.vanskis.liquidpocket.chat.ChatSearchHit
import com.vanskis.liquidpocket.chat.Conversation
import com.vanskis.liquidpocket.chat.ConversationTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The drawer's contents: New chat, a search field, then the saved conversations, newest first. While
 * the search field has text, matching conversations (from [onSearch]) replace the list. [onExport]
 * gives a conversation as Markdown for the item menu's Share and Copy all.
 */
@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    selectedId: Long?,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSearch: suspend (String) -> List<ChatSearchHit>,
    onExport: suspend (Long) -> String?,
    onShare: (title: String, markdown: String) -> Unit,
    onCopyAll: (markdown: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    val itemPadding = NavigationDrawerItemDefaults.ItemPadding
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ChatSearchHit>>(emptyList()) }
    val searching = query.isNotBlank()
    LaunchedEffect(query) {
        if (query.isBlank()) {
            hits = emptyList()
        } else {
            delay(ChatSearch.DEBOUNCE_MILLIS)
            hits = runCatching { onSearch(query) }.getOrDefault(emptyList())
        }
    }
    fun export(id: Long, deliver: (String) -> Unit) {
        scope.launch { onExport(id)?.let(deliver) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Chats",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            selected = selectedId == null,
            onClick = onNewChat,
            modifier = Modifier.padding(itemPadding),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        if (searching) {
            if (hits.isEmpty()) {
                Text(
                    "No chats match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(hits, key = { it.conversationId }) { hit ->
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(highlighted(hit.title, query), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                hit.snippet?.let {
                                    Text(
                                        highlighted(it, query),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        selected = hit.conversationId == selectedId,
                        onClick = { onOpen(hit.conversationId) },
                        modifier = Modifier.padding(itemPadding),
                    )
                }
            }
        } else if (conversations.isEmpty()) {
            Text(
                "Your chats are saved on this phone and listed here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        if (!searching) LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    selected = conversation.id == selectedId,
                    onOpen = { onOpen(conversation.id) },
                    onRename = { renaming = conversation },
                    onDelete = { deleting = conversation },
                    onShare = { export(conversation.id) { onShare(conversation.title, it) } },
                    onCopyAll = { export(conversation.id, onCopyAll) },
                )
            }
        }
    }

    renaming?.let { conversation ->
        RenameDialog(
            current = conversation.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                onRename(conversation.id, title)
                renaming = null
            },
        )
    }

    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${conversation.title}” and all its messages will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conversation.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopyAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        label = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onOpen,
        modifier = modifier,
        badge = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${conversation.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("Copy all") }, onClick = { menuOpen = false; onCopyAll() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(ConversationTitle.MAX_LENGTH * 2) },
                singleLine = true,
                label = { Text("Title") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [text] with the first case-insensitive match of [query] in bold. */
private fun highlighted(text: String, query: String) = buildAnnotatedString {
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (at < 0) {
        append(text)
    } else {
        append(text.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(at, at + q.length)) }
        append(text.substring(at + q.length))
    }
}
