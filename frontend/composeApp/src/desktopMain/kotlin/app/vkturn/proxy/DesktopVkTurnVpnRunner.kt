package app.vkturn.proxy

import app.vkturn.daemon.DaemonClient
import app.vkturn.daemon.DaemonRouteRunner
import app.vkturn.daemon.VkturndElevator
import app.vkturn.debug.AgentDebugLog
import app.vkturn.model.Identity
import app.vkturn.proxy.LogBus
import app.vkturn.proxy.LogLevel
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.wireguard.SingBoxWgThroughVkTurnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop analogue of Android [app.vkturn.tunnel.VkturnVpnService]: vk-turn-proxy listens on localhost,
 * then sing-box (+ WireGuard outbound → that UDP port) starts under **privileged** [vkturnd] so the
 * system default route switches like on Android VPN.
 *
 * Requires `vkturnd` reachable at [daemonClient].
 */
internal class DesktopVkTurnVpnRunner(
    private val logs: LogBus,
    private val resolver: BinaryResolver,
    private val hostFactory: () -> ProcessHost,
    private val daemonClient: DaemonClient,
    private val vkturndElevator: VkturndElevator? = null,
) : VpnRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vk = VkTurnRunner(logs, resolver, hostFactory)

    private var wg: DaemonRouteRunner? = null
    private var vkJob: Job? = null
    private var wgJob: Job? = null

    private val wgArmScheduled = AtomicBoolean(false)

    private var activeRoute: Route.VkTurnProxy? = null
    private var activeIdentity: Identity? = null

    private val _status = MutableStateFlow(RouteStatus(routeId = "", routeLabel = ""))
    override val status: StateFlow<RouteStatus> = _status.asStateFlow()

    override fun start(
        server: Server,
        identity: Identity,
        route: Route,
        @Suppress("UNUSED_PARAMETER") wgConfig: String,
        proxyBinary: String?,
        @Suppress("UNUSED_PARAMETER") proxyArgs: List<String>,
    ) {
        val vkRoute = route as? Route.VkTurnProxy ?: return
        logs.appendApp("=== DesktopVkTurnVpnRunner.start() ===", LogLevel.INFO)
        AgentDebugLog.log(
            hypothesisId = "C",
            location = "DesktopVkTurnVpnRunner.kt:start",
            message = "start called",
            data = mapOf("routeId" to vkRoute.id.take(16), "routeLabelLen" to vkRoute.label.length),
        )

        vkJob?.cancel()
        teardownWg(resetArm = true)
        vk.stop()

        activeRoute = vkRoute
        activeIdentity = identity

        // Remove config validation since wg-quick config generator supports AWG too

        vkJob =
            scope.launch {
                vk.status.collect {
                    maybeScheduleWg()
                    rebuildStatus()
                }
            }

        vk.start(server, vkRoute, binaryOverride = proxyBinary.orEmpty(), protectSocketPath = "vkturn-protect")
        rebuildStatus()
    }

    private fun maybeScheduleWg() {
        val route = activeRoute ?: return
        val identity = activeIdentity ?: return
        val vkSt = vk.status.value
        logs.appendApp("WG: maybeScheduleWg() called, vkSt=${vkSt.state}, wgPresent=${wg != null}", LogLevel.INFO)
        if (vkSt.state != RouteState.CONNECTED || wg != null) {
            logs.appendApp("WG: skipping - vk not connected or wg already present", LogLevel.INFO)
            return
        }
        if (!wgArmScheduled.compareAndSet(false, true)) {
            logs.appendApp("WG: skipping - already scheduled", LogLevel.INFO)
            return
        }
        logs.appendApp("WG: scheduling WireGuard launch...", LogLevel.INFO)
        scope.launch(Dispatchers.IO) {
            val endpoint = "${route.listenHost}:${route.listenPort}"
            val cfg = app.vkturn.wireguard.WgConfigGenerator.render(identity.wg, endpoint)
            logs.appendApp("WG: config generated, endpoint=$endpoint", LogLevel.INFO)

            val runner = DaemonRouteRunner(daemonClient, logs, vkturndElevator)
            wg = runner

            wgJob?.cancel()
            wgJob =
                scope.launch {
                    runner.status.collect { rebuildStatus() }
                }

            logs.appendApp("WG: calling startPrivilegedWireguard()...", LogLevel.INFO)
            runner.startPrivilegedWireguard(
                daemonRouteId = "${route.id}-vk-wg-tun",
                uiRouteId = route.id,
                uiRouteLabel = route.label,
                configText = cfg,
            )
            logs.appendApp("WG: startPrivilegedWireguard() returned", LogLevel.INFO)
            rebuildStatus()
        }
    }

    private fun RouteStatus.bound(route: Route.VkTurnProxy) =
        copy(routeId = route.id, routeLabel = route.label)

    private fun rebuildStatus() {
        val route = activeRoute ?: return
        val vkRaw = vk.status.value
        val vkSt = vkRaw.bound(route)
        AgentDebugLog.log(
            hypothesisId = "C",
            location = "DesktopVkTurnVpnRunner.kt:rebuildStatus",
            message = "status snapshot",
            data = mapOf(
                "vkState" to vkRaw.state.name,
                "wgPresent" to (wg != null),
                "wgState" to (wg?.status?.value?.state?.name),
            ),
        )

        if (vkRaw.state == RouteState.IDLE ||
            vkRaw.state == RouteState.ERROR ||
            vkRaw.state == RouteState.LOCKOUT
        ) {
            teardownWg(resetArm = true)
            _status.value = vkSt
            return
        }

        val wgSt = wg?.status?.value

        if (wgSt?.state == RouteState.ERROR) {
            val msg = wgSt.message.ifBlank { "Ошибка WireGuard / vkturnd — см. журнал" }
            // Do not tear down the already-established vk-turn-proxy session.
            // WG/TUN is an optional "system VPN" layer on desktop; when it fails,
            // keep the proxy connection alive and surface a warning instead.
            AgentDebugLog.log(
                hypothesisId = "B",
                location = "DesktopVkTurnVpnRunner.kt:rebuildStatus",
                message = "WG error observed",
                data = mapOf("msg" to msg.take(160)),
            )
            _status.value =
                vkSt.copy(
                    state = RouteState.CONNECTED,
                    message = "⚠ СИСТЕМНЫЙ VPN (TUN) НЕ ПОДНЯТ: $msg",
                )
            // Prevent immediate rescheduling loops; user can retry manually.
            teardownWg(resetArm = false)
            return
        }

        when (vkSt.state) {
            RouteState.STOPPING -> _status.value = vkSt
            RouteState.CONNECTED ->
                when (wgSt?.state) {
                    null ->
                        _status.value =
                            vkSt.copy(
                                state = RouteState.CONNECTING,
                                message = "Поднимаем системный VPN (WireGuard через vkturnd)…",
                            )
                    RouteState.CONNECTED ->
                        _status.value =
                            vkSt.copy(
                                state = RouteState.CONNECTED,
                                message = wgSt.message,
                                inBytes = wgSt.inBytes.takeIf { it >= 0L } ?: vkSt.inBytes,
                                outBytes = wgSt.outBytes.takeIf { it >= 0L } ?: vkSt.outBytes,
                            )
                    RouteState.ERROR -> { /* handled above */
                    }

                    RouteState.STARTING, RouteState.CONNECTING ->
                        _status.value =
                            vkSt.copy(
                                state = RouteState.CONNECTING,
                                message =
                                    wgSt.message.ifBlank {
                                        "WireGuard через vkturnd: запуск…"
                                    },
                            )
                    else ->
                        _status.value =
                            vkSt.copy(
                                state = RouteState.CONNECTING,
                                message =
                                    wgSt.message.ifBlank {
                                        "WireGuard: ${wgSt.state}"
                                    },
                            )
                }
            else ->
                when (vkSt.state) {
                    RouteState.CONNECTING, RouteState.CAPTCHA, RouteState.STARTING ->
                        _status.value = vkSt
                    else -> _status.value = vkSt
                }
        }
    }

    private fun teardownWg(resetArm: Boolean) {
        wgJob?.cancel()
        wgJob = null
        wg?.dispose()
        wg = null
        if (resetArm) wgArmScheduled.set(false)
    }

    override fun stop() {
        teardownWg(resetArm = true)
        vk.stop()
        rebuildStatusSafelyOrIdle()
    }

    override fun dispose() {
        vkJob?.cancel()
        vkJob = null
        teardownWg(resetArm = true)
        vk.dispose()
        scope.cancel()
    }

    private fun rebuildStatusSafelyOrIdle() {
        activeRoute ?: run {
            _status.value =
                RouteStatus(
                    "",
                    "",
                    RouteState.IDLE,
                )
            return
        }
        rebuildStatus()
    }
}
