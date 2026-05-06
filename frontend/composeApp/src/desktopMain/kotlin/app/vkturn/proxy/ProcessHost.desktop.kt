package app.vkturn.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private class DesktopProcessHost : ProcessHost {
    override val stdout = MutableSharedFlow<ProcessHost.Line>(extraBufferCapacity = 1024)
    override val stderr = MutableSharedFlow<ProcessHost.Line>(extraBufferCapacity = 256)
    override val exits = MutableSharedFlow<ProcessHost.Exit>(replay = 1)

    private val process = AtomicReference<Process?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isRunning: Boolean
        get() = process.get()?.isAlive == true

    override fun start(binary: String, args: List<String>, env: Map<String, String>) {
        check(process.get() == null) { "ProcessHost is one-shot; create a new instance" }
        val pb = ProcessBuilder(listOf(binary) + args).apply {
            environment().putAll(env)
            redirectErrorStream(false)
        }
        val proc = pb.start()
        process.set(proc)

        pump(proc.inputStream.bufferedReader(), stdout, tag = "stdout")
        pump(proc.errorStream.bufferedReader(), stderr, tag = "stderr")

        scope.launch {
            val code = runCatching { proc.waitFor() }.getOrNull() ?: -1
            exits.emit(ProcessHost.Exit(code = code, killed = !proc.isAlive && code != 0 && wasKilled))
        }
    }

    @Volatile private var wasKilled = false

    override fun stop(graceSeconds: Int) {
        val proc = process.get() ?: return
        wasKilled = true
        proc.destroy()
        scope.launch {
            if (!proc.waitFor(graceSeconds.toLong(), TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
    }

    private fun pump(
        reader: BufferedReader,
        into: MutableSharedFlow<ProcessHost.Line>,
        tag: String,
    ) {
        scope.launch {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    into.emit(ProcessHost.Line(stream = tag, text = line))
                }
            } catch (_: Throwable) {
                // stream closed — expected on shutdown
            } finally {
                runCatching { reader.close() }
            }
        }
    }
}

actual fun createProcessHost(): ProcessHost = DesktopProcessHost()
