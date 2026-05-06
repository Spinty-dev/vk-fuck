package app.vkturn.proxy

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import app.vkturn.model.Identity
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.tunnel.TunnelPhase
import app.vkturn.tunnel.TunnelStatus
import app.vkturn.tunnel.TunnelStatusBus
import app.vkturn.tunnel.VkturnVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * On Android the supervisor never spawns wg/sing-box as a subprocess —
 * instead we hand the config to [VkturnVpnService] which owns the TUN
 * descriptor. The runner exposed here fires the `ACTION_START` /
 * `ACTION_STOP` intents and mirrors [TunnelStatusBus] into [RouteStatus] so
 * the UI only reports **CONNECTED** after the tunnel is actually usable
 * (DTLS + WG handshake + public-IP probe via the VPN network).
 */
internal var androidVpnRunnerContext: Context? = null

private class AndroidVpnRunner(private val ctx: Context, private val logs: LogBus) : VpnRunner {
    private val _status = MutableStateFlow(RouteStatus(routeId = "", routeLabel = ""))
    override val status: StateFlow<RouteStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null
    private var activeRouteId: String = ""
    private var activeRouteLabel: String = ""

    override fun start(
        server: Server,
        identity: Identity,
        route: Route,
        wgConfig: String,
        proxyBinary: String?,
        proxyArgs: List<String>,
    ) {
        val permissionIntent = VpnService.prepare(ctx)
        if (permissionIntent != null) {
            _status.value = RouteStatus(
                routeId = route.id,
                routeLabel = route.label,
                state = RouteState.ERROR,
                message = "Нажмите ещё раз, чтобы подтвердить диалог VPN",
            )
            logs.appendApp("VpnService.prepare() требует разрешения", LogLevel.WARN)
            app.vkturn.androidActivityRef?.ensureVpnConsent()
            return
        }

        // Drop stale tunnel phases (ERROR / CONNECTED from a prior attempt).
        // Otherwise the StateFlow replays immediately as IDLE / ERROR and
        // RouteSupervisor tears the runner down before startForegroundService runs.
        TunnelStatusBus.reset()

        activeRouteId = route.id
        activeRouteLabel = route.label
        _status.value = RouteStatus(
            routeId = route.id,
            routeLabel = route.label,
            state = RouteState.STARTING,
            message = "Запуск VpnService…",
        )
        // Subscribe *before* dispatching the intent so the first status
        // emission (STARTING) from the service doesn't race past us.
        subscribeTunnelStatus()

        val intent = Intent(ctx, VkturnVpnService::class.java).apply {
            action = VkturnVpnService.ACTION_START
            putExtra(VkturnVpnService.EXTRA_IFACE_ADDR, identity.wg.address)
            putExtra(VkturnVpnService.EXTRA_DNS, identity.wg.dns)
            putExtra(VkturnVpnService.EXTRA_MTU, identity.wg.mtu)
            putExtra(VkturnVpnService.EXTRA_WG_CONF, wgConfig)
            putExtra(VkturnVpnService.EXTRA_ROUTE_ID, route.id)
            putExtra(VkturnVpnService.EXTRA_ROUTE_LABEL, route.label)
            if (!proxyBinary.isNullOrBlank()) {
                putExtra(VkturnVpnService.EXTRA_PROXY_BIN, proxyBinary)
                putExtra(VkturnVpnService.EXTRA_PROXY_ARGS, proxyArgs.toTypedArray())
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                ctx.startService(intent)
            }
        }.onFailure { e ->
            _status.value = RouteStatus(
                routeId = route.id,
                routeLabel = route.label,
                state = RouteState.ERROR,
                message = e.message?.take(120) ?: "Не удалось запустить VPN-сервис",
            )
            logs.appendApp("startForegroundService: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun subscribeTunnelStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            TunnelStatusBus.state.collect { tunnel -> publish(tunnel) }
        }
    }

    private fun publish(tunnel: TunnelStatus) {
        val routeId = activeRouteId
        val routeLabel = activeRouteLabel
        if (routeId.isBlank()) return

        // Initial MutableStateFlow replay + reset(): IDLE with no route — not our session yet.
        if (tunnel.phase == TunnelPhase.IDLE && tunnel.routeId.isBlank()) return
        // Leftovers from another route/session still sitting on the bus.
        if (tunnel.routeId.isNotBlank() && tunnel.routeId != routeId) return

        val newState = when (tunnel.phase) {
            TunnelPhase.IDLE -> RouteState.IDLE
            TunnelPhase.STARTING -> RouteState.STARTING
            TunnelPhase.PROXY_STARTING,
            TunnelPhase.PROXY_LISTEN,
            TunnelPhase.DTLS_READY,
            TunnelPhase.WG_UP,
            TunnelPhase.VERIFYING_IP -> RouteState.CONNECTING
            TunnelPhase.CONNECTED -> RouteState.CONNECTED
            TunnelPhase.ERROR -> RouteState.ERROR
        }
        val message = tunnel.message.ifBlank {
            when (tunnel.phase) {
                TunnelPhase.STARTING -> "Инициализация VPN…"
                TunnelPhase.PROXY_STARTING -> "Запуск vk-turn-proxy…"
                TunnelPhase.PROXY_LISTEN -> "Ждём dtls_ready…"
                TunnelPhase.DTLS_READY -> "DTLS готов — поднимаем WireGuard…"
                TunnelPhase.WG_UP -> "WireGuard поднят, проверяем IP…"
                TunnelPhase.VERIFYING_IP -> "Определяем внешний IP…"
                TunnelPhase.CONNECTED -> tunnel.tunnelIp?.let { "Внешний IP: $it" } ?: "Подключено"
                TunnelPhase.ERROR -> "Ошибка"
                TunnelPhase.IDLE -> ""
            }
        }
        _status.value = _status.value.copy(
            routeId = routeId,
            routeLabel = routeLabel,
            state = newState,
            message = message,
        )
    }

    override fun stop() {
        val intent = Intent(ctx, VkturnVpnService::class.java).apply {
            action = VkturnVpnService.ACTION_STOP
        }
        runCatching { ctx.startService(intent) }
        statusJob?.cancel()
        statusJob = null
        _status.value = _status.value.copy(state = RouteState.IDLE, message = "")
        TunnelStatusBus.reset()
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }
}

actual fun createVpnRunner(logs: LogBus): VpnRunner? {
    val ctx = app.vkturn.androidActivityRef ?: androidVpnRunnerContext ?: return null
    return AndroidVpnRunner(ctx, logs)
}
