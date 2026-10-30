package com.vanskis.liquidpocket.tools

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Exact math with math.js in an isolated JavaScript sandbox (androidx.javascriptengine): no files,
 * no network, offline. The sandbox and the loaded library stay up between calls.
 */
class MathTool(context: Context) : JsonTool(
    MathScript.TOOL,
    "Exact math and unit conversion, offline: arithmetic to 64 digits, fractions, algebra (simplify, derivative), " +
        "matrices, statistics, trigonometry, and units like \"5 km to mile\" or \"100 degF to degC\". " +
        "Uses math.js syntax; later expressions can use variables set by earlier ones (\"a = 7\", \"a * 3\").",
    """
    {"type": "object", "properties": {
      "expressions": {"type": "array", "items": {"type": "string"}, "description": "math.js expressions, evaluated in order"},
      "mode": {"type": "string", "enum": ["exact", "fraction"], "description": "exact: 64-digit decimals (default); fraction: exact fractions like 1/3"}
    }, "required": ["expressions"]}
    """,
    needsConfirmation = false,
) {
    private val context = context.applicationContext
    private val lock = Mutex()
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null

    override fun check(args: JsonObject) = if (MathScript.expressions(args).isEmpty()) "expressions is missing" else null

    override fun describe(args: JsonObject) = "Calculate ${MathScript.expressions(args).joinToString("; ")}"

    override suspend fun run(args: JsonObject): ToolResult {
        val expressions = MathScript.expressions(args)
        if (expressions.isEmpty()) return ToolResult(ToolResults.error("expressions is missing"))
        if (!JavaScriptSandbox.isSupported()) {
            return ToolResult(ToolResults.error("This phone's WebView cannot run the math sandbox. Updating Android System WebView may fix it."))
        }
        return lock.withLock {
            try {
                val answer = withTimeoutOrNull(TIMEOUT_MS) {
                    isolate().evaluateJavaScriptAsync(MathScript.call(expressions, args.string("mode"))).await()
                }
                if (answer == null) {
                    close()
                    ToolResult(ToolResults.error("The calculation took longer than ${TIMEOUT_MS / 1000} s and was stopped."))
                } else {
                    ToolResult(answer)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                close()
                ToolResult(ToolResults.error("The math sandbox failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /** The isolate with math.js and the runner loaded, starting the sandbox the first time. */
    private suspend fun isolate(): JavaScriptIsolate {
        isolate?.let { return it }
        val box = sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(context).await().also { sandbox = it }
        if (!box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            throw IllegalStateException("this WebView cannot load the math library")
        }
        val fresh = box.createIsolate()
        try {
            for (asset in listOf(MathScript.LIBRARY, MathScript.RUNNER)) {
                val source = context.assets.open(asset).bufferedReader().use { it.readText() }
                fresh.evaluateJavaScriptAsync(source).await()
            }
        } catch (e: Exception) {
            fresh.close()
            throw e
        }
        isolate = fresh
        return fresh
    }

    private fun close() {
        isolate?.close()
        isolate = null
        sandbox?.close()
        sandbox = null
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** Waits for the future without blocking; cancelling the wait cancels it. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { waiting ->
    addListener({
        try {
            waiting.resume(get())
        } catch (e: ExecutionException) {
            waiting.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            waiting.resumeWithException(e)
        }
    }, Executor(Runnable::run))
    waiting.invokeOnCancellation { cancel(true) }
}
