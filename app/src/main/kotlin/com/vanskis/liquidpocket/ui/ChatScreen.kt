package com.vanskis.liquidpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.chat.ChatViewModel
import com.vanskis.liquidpocket.chat.Turn
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.Role
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.onChatOpened() }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    val itemCount = state.turns.size + if (state.generating) 1 else 0
    LaunchedEffect(itemCount, state.streaming?.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    val loading = state.loading || engineState is EngineState.Loading
    val canSend = !loading && !state.generating && engineState is EngineState.Ready && input.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Liquid Pocket") },
                actions = {
                    TextButton(onClick = { vm.newChat() }) { Text("New chat") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                )
                if (state.generating) {
                    Button(onClick = { vm.stop() }) { Text("Stop") }
                } else {
                    Button(onClick = { vm.send(input); input = "" }, enabled = canSend) { Text("Send") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Loading the model…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (itemCount == 0) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Ask anything. Everything stays on this phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.turns) { turn -> MessageBubble(turn) }
                    state.streaming?.let { text ->
                        item { MessageBubble(Turn(Role.ASSISTANT, text.ifEmpty { "…" })) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(turn: Turn) {
    val mine = turn.role == Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(turn.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        turn.stats?.let { stats ->
            Text(
                String.format(Locale.ROOT, "decode %.1f tok/s", stats.decodeTokensPerSecond),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}
