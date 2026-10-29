package com.vanskis.liquidpocket.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.chat.Fact
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The "Things to remember" list; the chat reads it afresh for every reply. */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val chats = (app as LiquidPocketApp).chats

    val facts: StateFlow<List<Fact>> = chats.facts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(text: String) {
        viewModelScope.launch { chats.addFact(text) }
    }

    fun update(id: Long, text: String) {
        viewModelScope.launch { chats.updateFact(id, text) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { chats.deleteFact(id) }
    }
}
