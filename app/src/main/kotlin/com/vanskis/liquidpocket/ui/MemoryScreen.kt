package com.vanskis.liquidpocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.memory.MemoryViewModel
import com.vanskis.liquidpocket.ui.components.MemoryLayout

@Composable
fun MemoryScreen(onBack: () -> Unit, vm: MemoryViewModel = viewModel()) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    MemoryLayout(
        facts = facts,
        onBack = onBack,
        onAdd = vm::add,
        onUpdate = vm::update,
        onDelete = vm::delete,
    )
}
