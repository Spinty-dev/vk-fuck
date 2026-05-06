package app.vkturn.daemon

import app.vkturn.model.Route
import app.vkturn.proxy.LogBus
import app.vkturn.proxy.LogClassifier
import app.vkturn.proxy.LogLevel
import app.vkturn.proxy.LogLine
import app.vkturn.proxy.LogOrigin
import app.vkturn.proxy.RouteState
import app.vkturn.proxy.RouteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs a sing-box route through the privileged `vkturnd` helper instead
 * of as a local subprocess. Use this for any route that asks for TUN —
 * the local [app.vkturn.proxy.SingBoxRunner] remains the user-mode fallback.
 *
 * This runner mirrors the [app.vkturn.proxy.SingBoxRunner] API so the
 * supervisor can treat it interchangeably.
 */
class DaemonRouteRunner(
    private val client: DaemonClient,
    private val logs: LogBus,
    private val vkturndElevator: VkturndElevator? = null,
) {
    private val _status = MutableStateFlow(RouteStatus(routeId = "", routeLabel = ""))
    val status: StateFlow<RouteStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cancelLogs: (() -> Unit)? = null
    private var pollingJob: Job? = null

    /** vkturnd map key ([DaemonRequest.id]); differs from UI route id when using a companion sing-box tunnel. */
    private var daemonRouteId: String = ""

    /** Shown to the UI and log lines (matches the vk-turn profile route). */
    private var uiRouteId: String = ""
    private var uiRouteLabel: String = ""

    fun start(route: Route.SingBox, configJson: String) {
        startPrivilegedSingBox(
            daemonRouteId = route.id,
            uiRouteId = route.id,
            uiRouteLabel = route.label,
            configJson = configJson,
        )
    }

    /**
     * Start sing-box supervised by vkturnd. Use a unique [daemonRouteId] when the GUI route id already
     * names another daemon child (vk-turn-proxy) for the same logical profile.
     */
    fun startPrivilegedSingBox(
        daemonRouteId: String,
        uiRouteId: String,
        uiRouteLabel: String,
        configJson: String,
    ) {
        startPrivilegedRoute(daemonRouteId, uiRouteId, uiRouteLabel, "sing-box", configJson)
    }

    fun startPrivilegedWireguard(
        daemonRouteId: String,
        uiRouteId: String,
        uiRouteLabel: String,
        configText: String,
    ) {
        startPrivilegedRoute(daemonRouteId, uiRouteId, uiRouteLabel, "wireguard", configText)
    }

    private fun startPrivilegedRoute(
        daemonRouteId: String,
        uiRouteId: String,
        uiRouteLabel: String,
        kind: String,
        configPayload: String,
    ) {
        this.daemonRouteId = daemonRouteId
        this.uiRouteId = uiRouteId
        this.uiRouteLabel = uiRouteLabel

        _status.value = RouteStatus(
            routeId = uiRouteId,
            routeLabel = uiRouteLabel,
            state = RouteState.STARTING,
            message = "Соединение с демоном…",
        )

        var hello = client.connect()
        if (!hello.ok && vkturndElevator != null) {
            _status.value =
                _status.value.copy(
                    state = RouteState.STARTING,
                    message = "Ожидание Polkit для запуска vkturnd…",
                )
            vkturndElevator(logs)
            hello = client.connect()
        }
        if (!hello.ok) {
            failUi(
                "vkturnd недоступен (${hello.error}). Установите демон (daemon/install.sh) или запустите его через диалог прав (Polkit pkexec под Linux); сокет: /run/vkturn/control.sock.",
            )
            return
        }

        val up = client.request(
            DaemonRequest(
                op = "up",
                id = daemonRouteId,
                kind = kind,
                configJson = configPayload,
            ),
        )
        if (!up.ok) {
            failUi("Не удалось поднять $kind в vkturnd: ${up.error}")
            return
        }

        _status.update { it.copy(state = RouteState.CONNECTING, message = "") }
        logs.appendApp("vkturnd $kind → $uiRouteLabel (daemon id=$daemonRouteId)", LogLevel.INFO)

        val isWireguard = kind == "wireguard"
        val logOrigin = if (isWireguard) LogOrigin.WIREGUARD else LogOrigin.SINGBOX
        val logStream = if (isWireguard) "wg-daemon" else "daemon"

        cancelLogs =
            client.subscribeLogs(daemonRouteId) { raw ->
                logs.append(
                    LogLine(
                        epochMillis = System.currentTimeMillis(),
                        level = LogClassifier.classify(logStream, raw),
                        origin = logOrigin,
                        routeId = uiRouteId,
                        routeLabel = uiRouteLabel,
                        stream = logStream,
                        text = raw,
                    ),
                )
                val lower = raw.lowercase()
                if (lower.contains("started successfully") ||
                    lower.contains("sing-box started") ||
                    lower.contains("is up") ||
                    (isWireguard && lower.contains("wireguard"))
                ) {
                    _status.update { it.copy(state = RouteState.CONNECTED, message = "") }
                }
            }

        pollingJob =
            scope.launch {
                while (isActive) {
                    delay(2_000)
                    val resp = client.request(DaemonRequest(op = "status"))
                    val info = resp.routes?.get(daemonRouteId) ?: continue
                    _status.update { current ->
                        current.copy(
                            inBytes = info.inBytes,
                            outBytes = info.outBytes,
                            state =
                                when (info.state) {
                                    "running" ->
                                        if (current.state ==
                                            RouteState.STARTING
                                        ) {
                                            RouteState.CONNECTED
                                        } else {
                                            current.state
                                        }
                                    "error" -> RouteState.ERROR
                                    "stopped" -> RouteState.IDLE
                                    else -> current.state
                                },
                            message = if (info.state == "error") info.message else current.message,
                        )
                    }
                }
            }
    }

    fun stop() {
        val id = daemonRouteId
        if (id.isBlank()) return
        _status.update { it.copy(state = RouteState.STOPPING) }
        val resp = client.request(DaemonRequest(op = "down", id = id))
        if (!resp.ok) {
            logs.appendApp("daemon down failed: ${resp.error}", LogLevel.WARN)
        }
        cancelLogs?.invoke()
        cancelLogs = null
        pollingJob?.cancel()
        pollingJob = null
        _status.update { it.copy(state = RouteState.IDLE) }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun failUi(message: String) {
        _status.value =
            RouteStatus(
                routeId = uiRouteId,
                routeLabel = uiRouteLabel,
                state = RouteState.ERROR,
                message = message,
            )
        logs.appendApp(message, LogLevel.ERROR)
    }
}
