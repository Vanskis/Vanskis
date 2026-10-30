package com.vanskis.liquidpocket.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.chat.ChatSearchHit
import com.vanskis.liquidpocket.chat.ChatState
import com.vanskis.liquidpocket.chat.Conversation
import com.vanskis.liquidpocket.chat.ModelStatus
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.Skills
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.tools.Choices
import kotlinx.coroutines.launch

/** Scrolled up more than this (px) into the newest message, the scroll-down button shows. */
private const val SCROLL_BUTTON_OFFSET = 200

/** What Continue under a cut-off reply sends. */
private const val CONTINUE = "Continue from where you stopped."

/** What the chat screen can ask for; the view model implements it. */
interface ChatActions {
    fun send(text: String)
    fun stop()
    fun regenerate()
    fun editAndResend(text: String)
    fun newChat()
    fun openConversation(id: Long)
    fun renameConversation(id: Long, title: String)
    fun deleteConversation(id: Long)

    /** Saved conversations whose title or messages contain [query]. */
    suspend fun searchChats(query: String): List<ChatSearchHit>

    /** A saved conversation as Markdown, null if it is gone. */
    suspend fun exportChat(id: Long): String?
    fun dismissError()

    /** Answers the tool call waiting in [ChatState.confirmation], if [confirmationId] is the shown card's. */
    fun approveTool(confirmationId: Long)
    fun declineTool(confirmationId: Long)

    /** Uses [skill] for the open chat; null drops it. */
    fun setSkill(skill: Skill?) {}
}

/** The chat screen: the conversation drawer around the top bar, the messages and the composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatLayout(
    state: ChatState,
    status: ModelStatus,
    modelName: String,
    conversations: List<Conversation>,
    actions: ChatActions,
    onCopy: (String) -> Unit,
    /** Hands a chat's title and Markdown to the system share sheet. */
    onShare: (title: String, markdown: String) -> Unit,
    onOpenSettings: () -> Unit,
    media: ChatMediaUi = ChatMediaUi(),
    /** The composer's model and thinking menu ([ModelMenuChip]); hidden when null. */
    modelMenu: ModelMenuState? = null,
    onThinking: (think: Boolean, budget: Int) -> Unit = { _, _ -> },
    onPickModel: (String) -> Unit = {},
    /** The loaded model's context window in tokens; 0 hides the composer's context ring. */
    contextTotal: Int = 0,
    /** Summarizes older turns to free context (the ring's Compact now); disabled when null. */
    onCompact: (() -> Unit)? = null,
    /** What "/" in the composer lists: the built-in skills and the user's own. */
    skills: List<Skill> = Skills.BUILT_IN,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); actions.dismissError() }
    }
    LaunchedEffect(media.transcript) {
        media.transcript?.let { spoken ->
            input = if (input.isBlank()) spoken else input.trimEnd() + " " + spoken
            media.onTranscriptUsed()
        }
    }

    val ready = status == ModelStatus.Ready && !state.busy
    // Editing applies to the newest user turn; it lapses if that turn goes away.
    val isEditing = editing && state.lastUserIndex >= 0 && !state.generating
    // Pending attachments go with a new message; an edit keeps the turn's own. A document still being
    // read would miss the message it was meant for, so sending waits for it.
    val canSend = ready && (input.isNotBlank() || (!isEditing && media.pending.isNotEmpty())) &&
        (isEditing || media.extracting.isEmpty())

    fun closeDrawerThen(action: () -> Unit) {
        editing = false
        action()
        scope.launch { drawerState.close() }
    }

    fun submit() {
        if (isEditing) actions.editAndResend(input) else actions.send(input)
        input = ""
        editing = false
    }

    val title = conversations.firstOrNull { it.id == state.conversationId }?.title ?: "Liquid Pocket"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState) {
                ConversationDrawer(
                    conversations = conversations,
                    selectedId = state.conversationId,
                    onNewChat = { closeDrawerThen(actions::newChat) },
                    onOpen = { id -> closeDrawerThen { actions.openConversation(id) } },
                    onRename = actions::renameConversation,
                    onDelete = actions::deleteConversation,
                    onSearch = actions::searchChats,
                    onExport = actions::exportChat,
                    onShare = onShare,
                    onCopyAll = onCopy,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ModelStatusLine(status, modelName)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Chats")
                            }
                        },
                        actions = {
                            media.onLive?.let { IconButton(onClick = it) { Icon(AppIcons.GraphicEq, contentDescription = "Live voice") } }
                            media.speakReplies?.let { on ->
                                IconButton(onClick = media.onToggleSpeakReplies) {
                                    Icon(
                                        if (on) AppIcons.VolumeUp else AppIcons.VolumeOff,
                                        contentDescription = if (on) "Stop speaking replies" else "Speak replies",
                                    )
                                }
                            }
                            IconButton(onClick = { editing = false; actions.newChat() }) {
                                Icon(Icons.Filled.Add, contentDescription = "New chat")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        },
                    )
                    if (status == ModelStatus.Loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    canSend = canSend,
                    generating = state.generating,
                    onSend = ::submit,
                    onStop = actions::stop,
                    editing = isEditing,
                    onCancelEdit = { editing = false; input = "" },
                    pending = if (isEditing) emptyList() else media.pending,
                    onRemovePending = media.onRemovePending,
                    extracting = if (isEditing) emptyList() else media.extracting,
                    onCancelExtraction = media.onCancelExtraction,
                    shared = if (isEditing) emptyList() else media.shared,
                    onUseShared = media.onUseShared,
                    attachEnabled = status == ModelStatus.Ready && !state.loading,
                    onPickPhoto = media.onPickPhoto,
                    onTakePhoto = media.onTakePhoto,
                    onPickDocument = media.onPickDocument,
                    focusRequester = media.focusRequester,
                    onMic = media.onMic,
                    recordingMillis = media.recordingMillis,
                    modelMenu = modelMenu,
                    onThinking = onThinking,
                    onPickModel = onPickModel,
                    contextUsed = state.contextUsed,
                    contextTotal = contextTotal,
                    // Enabled only with more than a few turns since the newest summary, and nothing running.
                    onCompact = onCompact?.takeIf { state.canCompact },
                    skills = skills,
                    skill = state.skill,
                    onPickSkill = actions::setSkill,
                    onDropSkill = { actions.setSkill(null) },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val rows = state.turns.size + if (state.generating) 1 else 0
            if (rows == 0) {
                EmptyState(
                    modelName = modelName,
                    onSuggestion = { suggestion -> if (ready) actions.send(suggestion) else input = suggestion },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                MessageList(
                    state = state,
                    rows = rows,
                    contentPadding = padding,
                    onCopy = onCopy,
                    onRegenerate = actions::regenerate,
                    onSend = actions::send,
                    onTypeOwn = { media.focusRequester?.requestFocus() },
                    onEdit = { text ->
                        input = text
                        editing = true
                    },
                    onApproveTool = actions::approveTool,
                    onDeclineTool = actions::declineTool,
                )
            }
        }
    }
}

/**
 * Newest message at the bottom; the list is reversed so a growing reply stays anchored above the
 * composer. Row keys are turn indices, and the streaming reply takes the index it will be saved at,
 * so its Markdown is not parsed again from scratch when it completes. A new message scrolls to the
 * bottom; scrolled up, a round button brings it back.
 */
@Composable
private fun MessageList(
    state: ChatState,
    rows: Int,
    contentPadding: PaddingValues,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    /** Sends a message: a tapped choice, or Continue under a cut-off reply. */
    onSend: (String) -> Unit,
    onTypeOwn: () -> Unit,
    onEdit: (String) -> Unit,
    onApproveTool: (confirmationId: Long) -> Unit,
    onDeclineTool: (confirmationId: Long) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(rows) { listState.animateScrollToItem(0) }
    val scope = rememberCoroutineScope()
    val scrolledUp by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > SCROLL_BUTTON_OFFSET }
    }
    val turns = state.turns
    val idle = !state.busy
    // Stats show under the newest finished reply only, and not while the next one streams.
    val statsIndex = if (state.generating) -1 else turns.indexOfLast { it.role == Role.ASSISTANT && it.text.isNotEmpty() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count = rows, key = { row -> rows - 1 - row }) { row ->
                val index = rows - 1 - row
                val turn = turns.getOrNull(index)
                val isLast = index == turns.lastIndex
                val user = turn?.takeIf { it.role == Role.USER && !it.fromTool }
                val tool = turn?.takeIf { it.role != Role.USER && it.role != Role.ASSISTANT }
                val confirmation = state.confirmation
                when {
                    turn != null && turn.summary -> SummaryDivider(turn.text)
                    // The photos show under the look_at_photos line before it.
                    turn != null && turn.fromTool -> Unit
                    // A reply that only called tools: its tool turns that follow show what happened, under
                    // what it thought first. (One that offered choices still shows them.)
                    turn != null && turn.role == Role.ASSISTANT && turn.text.isEmpty() && turn.toolCalls.isNotEmpty() &&
                        Choices.offerIn(turn.toolCalls) == null -> if (!turn.thinking.isNullOrBlank()) {
                        ThinkingSection(
                            turn.thinking,
                            turn.thinkingMillis?.let { (it / 1000).toInt() },
                            streaming = false,
                            tokens = turn.thinkingTokens,
                            onCopyCode = onCopy,
                        )
                    }
                    turn == null && confirmation != null -> ToolConfirmationCard(confirmation, onApproveTool, onDeclineTool)
                    user != null -> {
                        val newest = idle && index == state.lastUserIndex
                        UserMessage(
                            text = user.text,
                            attachments = user.attachments,
                            onEdit = if (newest) ({ onEdit(user.text) }) else null,
                            // A user turn with no reply after it: the reply failed or was stopped before its first token.
                            onRetry = if (newest && isLast) onRegenerate else null,
                        )
                    }
                    // The chips under the reply already show an offer.
                    tool != null && tool.toolName == Choices.TOOL -> Unit
                    tool != null -> ToolMessage(tool.toolName, tool.text)
                    // One call site for the streaming and the saved reply, so its Markdown state carries over.
                    else -> {
                        val text = turn?.text ?: state.streaming.orEmpty()
                        val newest = idle && isLast && state.lastUserIndex >= 0
                        val offer = turn?.let { Choices.offerIn(it.toolCalls) }
                        AssistantMessage(
                            text = text,
                            streaming = turn == null,
                            stats = turn?.stats?.takeIf { index == statsIndex },
                            onCopyCode = onCopy,
                            onCopy = { onCopy(text) },
                            onRegenerate = if (newest) onRegenerate else null,
                            onContinue = if (newest && turn?.stats?.cutOff == true) ({ onSend(CONTINUE) }) else null,
                            offer = offer,
                            // Only the newest offer takes a tap: once the user has said anything after it, it is answered.
                            onChoose = if (offer != null && idle && index > state.lastUserIndex) onSend else null,
                            onTypeOwn = onTypeOwn,
                            thinking = if (turn == null) state.streamingThinking else turn.thinking,
                            thinkingSeconds = turn?.thinkingMillis?.let { (it / 1000).toInt() },
                            thinkingTokens = if (turn == null) state.streamingThinkingTokens else turn.thinkingTokens,
                            liveTokens = if (turn == null) state.streamingTokens else 0,
                            model = turn?.model,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = scrolledUp,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to the newest message")
            }
        }
    }
}

/**
 * Where compaction summarized the turns above it: "Earlier messages summarized" between two rules.
 * The turns above stay shown, but the model now sees only [summary] of them; a tap expands it.
 */
@Composable
private fun SummaryDivider(summary: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = colors.outlineVariant)
            TextButton(onClick = { expanded = !expanded }) {
                Text("Earlier messages summarized", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide the summary" else "Show the summary",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.weight(1f), color = colors.outlineVariant)
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surfaceContainerLow,
                contentColor = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

/** "● LFM3-1.7B · Ready" under the title. */
@Composable
private fun ModelStatusLine(status: ModelStatus, modelName: String) {
    val colors = MaterialTheme.colorScheme
    val (label, dot) = when (status) {
        ModelStatus.Ready -> "$modelName · Ready" to Color(0xFF2E7D32)
        ModelStatus.Loading -> "$modelName · Loading…" to colors.tertiary
        ModelStatus.NotLoaded -> "$modelName · Not loaded" to colors.outline
        is ModelStatus.Error -> "$modelName · Error" to colors.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
