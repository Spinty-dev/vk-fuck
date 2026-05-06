package app.vkturn.daemon

/**
 * Thin, blocking RPC client for the `vkturnd` helper. One instance ==
 * one long-lived socket connection; callers in this codebase are
 * [DaemonRouteRunner] and the status probe in [AppViewModel].
 *
 * The client is deliberately small — it speaks newline-JSON directly.
 * Platform-specific implementations wire the actual socket transport
 * (UNIX on Linux/macOS, Named Pipe on Windows, N/A on Android).
 */
interface DaemonClient {
    /** True once a control connection has been established successfully. */
    val isConnected: Boolean

    /** Opens the connection. Safe to call repeatedly — no-op if already up. */
    fun connect(): DaemonResponse

    /**
     * Sends a single request and returns the first response. Logs streams
     * should use [subscribeLogs] instead.
     */
    fun request(req: DaemonRequest): DaemonResponse

    /**
     * Opens a sub-connection dedicated to streaming log lines for [routeId].
     * Calls [onLine] for every JSON envelope until [cancel] is invoked or
     * the daemon closes its side.
     */
    fun subscribeLogs(routeId: String, onLine: (String) -> Unit): () -> Unit

    fun close()
}

/** Configuration describing where the daemon socket lives on this platform. */
data class DaemonAddress(val path: String)

expect fun createDaemonClient(address: DaemonAddress): DaemonClient

expect fun defaultDaemonAddress(): DaemonAddress
