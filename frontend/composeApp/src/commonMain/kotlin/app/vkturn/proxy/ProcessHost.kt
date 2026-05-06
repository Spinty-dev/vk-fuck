package app.vkturn.proxy

import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction over a single OS subprocess. Platform implementations wrap
 * `ProcessBuilder` on the JVM and Android runtimes; the Compose UI never
 * sees the underlying Process.
 *
 * A host is one-shot: [start] may only be called once. Subsequent reuse
 * should go through a fresh instance created by [createProcessHost].
 */
interface ProcessHost {
    data class Line(val stream: String, val text: String)

    data class Exit(val code: Int, val killed: Boolean)

    val stdout: SharedFlow<Line>
    val stderr: SharedFlow<Line>
    val exits: SharedFlow<Exit>

    fun start(binary: String, args: List<String>, env: Map<String, String> = emptyMap())
    fun stop(graceSeconds: Int = 5)

    val isRunning: Boolean
}

expect fun createProcessHost(): ProcessHost
