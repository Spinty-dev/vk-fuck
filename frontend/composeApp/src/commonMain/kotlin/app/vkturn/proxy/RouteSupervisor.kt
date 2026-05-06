package app.vkturn.proxy

import app.vkturn.daemon.DaemonClient
import app.vkturn.daemon.DaemonRouteRunner
import app.vkturn.daemon.VkturndElevator
import app.vkturn.model.AppConfig
import app.vkturn.model.Identity
import app.vkturn.model.Route
import app.vkturn.model.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns every live [VkTurnRunner] / [SingBoxRunner] instance in the app,
 * keyed by route id. The UI interacts with the supervisor; individual
 * runners stay hidden behind it.
 *
 * Invariants:
 *   - At most one runner per route id.
 *   - Starting a route that's already running is a no-op.
 *   - `stopAll()` always returns with every runner transitioned to IDLE.
 */
class RouteSupervisor(
    private val logs: LogBus,
    private val resolver: BinaryResolver,
    private val hostFactory: () -> ProcessHost,
    private val daemonClient: DaemonClient,
    private val vkturndElevator: VkturndElevator? = null,
    private val vpnRunnerFactory: () -> VpnRunner? = {
        createPlatformVpnRunner(logs, resolver, hostFactory, daemonClient, vkturndElevator)
    },
) {
    private data class Entry(
        val routeId: String,
        val stop: () -> Unit,
        val dispose: () -> Unit,
        val status: StateFlow<RouteStatus>,
    )

    private val entries = mutableMapOf<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var aggregationJob: Job? = null

    private val _statuses = MutableStateFlow<Map<String, RouteStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, RouteStatus>> = _statuses.asStateFlow()

    @Synchronized
    fun start(config: AppConfig, server: Server, identity: Identity, route: Route) {
        if (entries.containsKey(route.id)) return

        val entry = when (route) {
            is Route.VkTurnProxy -> startVkTurn(config, server, identity, route)
            is Route.SingBox -> startSingBox(config, identity, route)
            is Route.Direct -> {
                logs.appendApp("direct route '${route.label}' is a no-op runner", LogLevel.INFO)
                null
            }
        }
        if (entry != null) {
            entries[route.id] = entry
            watch(entry)
        }
    }

    @Synchronized
    fun stop(routeId: String) {
        val entry = entries[routeId] ?: return
        entry.stop()
    }

    @Synchronized
    fun stopAll() {
        entries.values.forEach { it.stop() }
    }

    @Synchronized
    fun dispose() {
        entries.values.forEach { it.dispose() }
        entries.clear()
        aggregationJob?.cancel()
        scope.cancel()
    }

    fun isRunning(routeId: String): Boolean =
        statuses.value[routeId]?.isRunning == true

    private fun startVkTurn(
        config: AppConfig,
        server: Server,
        identity: Identity,
        route: Route.VkTurnProxy,
    ): Entry {
        // On Android the supervisor prefers the VpnService path so system
        // routing and DNS are actually applied. On JVM desktop,
        // [createPlatformVpnRunner] wires [DesktopVkTurnVpnRunner], which shells
        // out to vk-turnproxy then lifts sing-box TUN via vkturnd.
        val vpn = vpnRunnerFactory()
        if (vpn != null) {
            val wgConf = app.vkturn.wireguard.WgConfigGenerator.render(
                w = identity.wg,
                localEndpoint = "${route.listenHost}:${route.listenPort}",
            )
            val proxyBinary = resolver.resolveVkTurnProxy(config.proxyBinaryOverride)
            val proxyArgv = ProxyArgs.build(server, route)
            vpn.start(server, identity, route, wgConf, proxyBinary, proxyArgv)
            return Entry(
                routeId = route.id,
                stop = vpn::stop,
                dispose = vpn::dispose,
                status = vpn.status,
            )
        }

        val runner = VkTurnRunner(logs, resolver, hostFactory)
        runner.start(
            server = server,
            route = route,
            binaryOverride = config.proxyBinaryOverride,
        )
        return Entry(
            routeId = route.id,
            stop = runner::stop,
            dispose = runner::dispose,
            status = runner.status,
        )
    }

    private fun startSingBox(
        config: AppConfig,
        identity: Identity,
        route: Route.SingBox,
    ): Entry {
        val chainedListen = route.chainedThroughProxyRouteId?.let { chainId ->
            identity.routes
                .filterIsInstance<Route.VkTurnProxy>()
                .firstOrNull { it.id == chainId }
                ?.let { "${it.listenHost}:${it.listenPort}" }
        }
        val cfg = SingBoxConfig.render(
            SingBoxConfig.BuildInput(
                route = route,
                chainedProxyListen = chainedListen,
                clashApiPort = clashApiPortFor(route.socksListenPort),
            ),
        )
        // TUN requires the privileged daemon; user-mode SOCKS can stay
        // in-process. We gate the branch strictly on the route setting.
        return if (route.tunEnabled) {
            val runner = DaemonRouteRunner(daemonClient, logs, vkturndElevator)
            runner.start(route, cfg)
            Entry(routeId = route.id, stop = runner::stop, dispose = runner::dispose, status = runner.status)
        } else {
            val runner = SingBoxRunner(logs, resolver, hostFactory)
            runner.start(route, binaryOverride = config.singBoxBinaryOverride, configJson = cfg)
            Entry(routeId = route.id, stop = runner::stop, dispose = runner::dispose, status = runner.status)
        }
    }

    private fun watch(entry: Entry) {
        scope.launch {
            entry.status.collect { status ->
                _statuses.update { current -> current + (entry.routeId to status) }
                if (status.state == RouteState.IDLE || status.state == RouteState.ERROR) {
                    // Once a runner returns to IDLE we can drop it. ERROR also
                    // releases the slot so the user can try starting again.
                    // Disposal is defered to allow the final status to render.
                    unregister(entry.routeId)
                }
            }
        }
    }

    /** Same derivation as [SingBoxRunner.clashApiPortFor]. Duplicated here
     *  so the supervisor can build the config before the runner is created. */
    private fun clashApiPortFor(socksPort: Int): Int {
        val base = socksPort.coerceIn(1024, 60000)
        return ((base + 11111) % 60000).coerceAtLeast(10000)
    }

    @Synchronized
    private fun unregister(routeId: String) {
        val entry = entries.remove(routeId) ?: return
        // Small delay implicit: the coroutine context transitions before the
        // UI renders — no explicit sleep needed.
        entry.dispose()
    }
}
