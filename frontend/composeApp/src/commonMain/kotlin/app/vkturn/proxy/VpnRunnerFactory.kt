package app.vkturn.proxy

import app.vkturn.model.Identity
import app.vkturn.model.Route
import app.vkturn.model.Server
import kotlinx.coroutines.flow.StateFlow

interface VpnRunner {
    val status: StateFlow<RouteStatus>
    fun start(server: Server, identity: Identity, route: Route, wgConfig: String, proxyBinary: String?, proxyArgs: List<String>)
    fun stop()
    fun dispose()
}

/** Returns null on platforms where this flow doesn't apply. */
expect fun createVpnRunner(logs: LogBus): VpnRunner?
