package com.vanskis.liquidpocket.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.chat.ChatViewModel
import com.vanskis.liquidpocket.chat.modelStatus
import com.vanskis.liquidpocket.ui.components.ChatLayout

@Composable
fun ChatScreen(onOpenSettings: () -> Unit, vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.onChatOpened() }

    // A tool asks for a permission the first time it needs it; the answer goes back to the tool.
    val permissionRequest by vm.permissions.request.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.permissions.onResult()
    }
    LaunchedEffect(permissionRequest) {
        permissionRequest?.let { if (it.markLaunched()) permissionLauncher.launch(it.permissions.toTypedArray()) }
    }

    ChatLayout(
        state = state,
        status = modelStatus(engineState, state),
        modelName = ACTIVE_MODEL.displayName,
        conversations = conversations,
        actions = vm,
        // Android 13+ shows its own confirmation when something is copied, so no snackbar here.
        onCopy = { text ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Liquid Pocket reply", text))
        },
        onOpenSettings = onOpenSettings,
    )
}
