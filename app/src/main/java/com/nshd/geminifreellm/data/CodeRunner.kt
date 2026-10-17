package com.nshd.geminifreellm.data

import kotlinx.coroutines.CancellationException
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Result of running a JavaScript snippet on-device. */
sealed interface JsRunResult {
    data class Success(val output: String, val returned: String?) : JsRunResult
    data class Failure(val message: String, val line: Int) : JsRunResult
}

/**
 * Runs JavaScript fully on-device with the Rhino interpreter (no network, no server).
 * console.log / console.error / print are captured; infinite loops are killed by a
 * watchdog; syntax and runtime errors report a line number. Java interop is not
 * exposed, so snippets cannot touch Android APIs or the filesystem.
 */
object CodeRunner {
    const val TIMEOUT_MS = 8000L
    private const val MAX_CHARS = 120_000

    fun isRunnable(language: String) = language.lowercase() in setOf("js", "javascript", "mjs")
    fun isPreviewable(language: String) = language.lowercase() in setOf("html", "htm")

    private class JsTimeoutException : RuntimeException("Timed out after ${TIMEOUT_MS / 1000} seconds")

    fun runJs(code: String): JsRunResult {
        if (code.length > MAX_CHARS) return JsRunResult.Failure("Code exceeds $MAX_CHARS characters", 0)
        val output = StringBuilder()
        val start = System.currentTimeMillis()
        val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (System.currentTimeMillis() - start > TIMEOUT_MS) throw JsTimeoutException()
            }
            override fun makeContext(): Context = super.makeContext().apply {
                // Interpreted mode is required on Android (no JIT classloading).
                optimizationLevel = -1
                instructionThreshold = 20_000
                maximumInterpreterStackDepth = 256
            }
        }
        return try {
            var returned: String? = null
            factory.call { cx ->
                val scope: Scriptable = cx.initStandardObjects()
                val logFn = object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                        output.appendLine(args.joinToString(" ") { Context.toString(it) })
                        return Context.getUndefinedValue()
                    }
                }
                val consoleObj: Scriptable = cx.newObject(scope)
                consoleObj.put("log", consoleObj, logFn)
                consoleObj.put("error", consoleObj, logFn)
                ScriptableObject.putProperty(scope, "console", consoleObj)
                ScriptableObject.putProperty(scope, "print", logFn)
                val result = cx.evaluateString(scope, code, "code", 1, null)
                if (result !== Context.getUndefinedValue()) returned = Context.toString(result)
                null
            }
            JsRunResult.Success(output.toString().trimEnd(), returned)
        } catch (e: RhinoException) {
            JsRunResult.Failure((e.message() ?: "Script error").take(500), e.lineNumber())
        } catch (e: JsTimeoutException) {
            JsRunResult.Failure(e.message ?: "Timed out", 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            JsRunResult.Failure("Execution failed: ${(e.message ?: e.javaClass.simpleName).take(300)}", 0)
        }
    }
}
