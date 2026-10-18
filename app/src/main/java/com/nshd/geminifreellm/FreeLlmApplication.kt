package com.nshd.geminifreellm

import android.app.Application
import com.nshd.geminifreellm.data.DiagnosticLog
import kotlinx.coroutines.*

class FreeLlmApplication : Application() {
    val diagnostics by lazy { DiagnosticLog(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        scope.launch { diagnostics.load() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { diagnostics.record("Crash", error = error) }
            previous?.uncaughtException(thread, error) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
        }
    }
}
