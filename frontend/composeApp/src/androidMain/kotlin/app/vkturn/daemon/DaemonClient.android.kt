package app.vkturn.daemon

/**
 * No daemon on Android — VpnService handles TUN in-process. We still
 * ship a trivial stub so shared code compiles and behaves as if the
 * daemon is permanently unavailable.
 */
private object NoopDaemonClient : DaemonClient {
    override val isConnected: Boolean = false
    override fun connect(): DaemonResponse =
        DaemonResponse(ok = false, error = "daemon is not used on android — VpnService handles TUN")

    override fun request(req: DaemonRequest): DaemonResponse =
        DaemonResponse(ok = false, error = "no daemon on android")

    override fun subscribeLogs(routeId: String, onLine: (String) -> Unit): () -> Unit = {}

    override fun close() {}
}

actual fun createDaemonClient(address: DaemonAddress): DaemonClient = NoopDaemonClient
actual fun defaultDaemonAddress(): DaemonAddress = DaemonAddress("n/a")
