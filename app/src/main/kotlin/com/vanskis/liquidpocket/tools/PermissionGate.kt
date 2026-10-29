package com.vanskis.liquidpocket.tools

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime permissions for tools, asked for when a tool first needs them. A tool calls [ensure]; the
 * chat screen shows the system dialog for [request] and reports back with [onResult].
 */
class PermissionGate(private val context: Context) {
    class Request(val permissions: List<String>) {
        internal val answered = CompletableDeferred<Unit>()
        private val launched = AtomicBoolean(false)

        /** True the first time only, so a recreated screen does not show the dialog twice. */
        fun markLaunched(): Boolean = launched.compareAndSet(false, true)
    }

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val mutex = Mutex()

    /** True when all [permissions] are granted, after asking the user for the missing ones. */
    suspend fun ensure(vararg permissions: String): Boolean = mutex.withLock {
        val missing = permissions.filterNot(::granted)
        if (missing.isEmpty()) return@withLock true
        val request = Request(missing)
        _request.value = request
        try {
            request.answered.await()
        } finally {
            _request.value = null
        }
        permissions.all(::granted)
    }

    fun onResult() {
        _request.value?.answered?.complete(Unit)
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
