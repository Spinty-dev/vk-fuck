package app.vkturn.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lifecycle of an Android VPN session as observed by [VkturnVpnService].
 *
 * The UI should render [CONNECTED] **only** when we have seen both:
 *   1. `vk-turn-proxy` emit `PROXY_STATUS: dtls_ready` (at least once),
 *   2. [WgBackendBridge] accept the WG `setState(UP)` transition, and
 *   3. A successful public-IP probe via the VPN [android.net.Network] that
 *      differs from [TunnelStatus.baselineIp].
 *
 * Until then states are [STARTING] / [PROXY_STARTING] / [PROXY_LISTEN] /
 * [DTLS_READY] / [WG_UP] / [VERIFYING_IP]. Any fatal step transitions to
 * [ERROR] with a message. [IDLE] is the pre-start and post-stop baseline.
 */
enum class TunnelPhase {
    IDLE,
    STARTING,
    PROXY_STARTING,
    PROXY_LISTEN,
    DTLS_READY,
    WG_UP,
    VERIFYING_IP,
    CONNECTED,
    ERROR,
}

data class TunnelStatus(
    val phase: TunnelPhase = TunnelPhase.IDLE,
    val message: String = "",
    val routeId: String = "",
    val routeLabel: String = "",
    val baselineIp: String? = null,
    val tunnelIp: String? = null,
) {
    val isUserConnected: Boolean get() = phase == TunnelPhase.CONNECTED
}

/** Cross-process-local status broadcaster for the VPN session. */
object TunnelStatusBus {
    private val _state = MutableStateFlow(TunnelStatus())
    val state: StateFlow<TunnelStatus> = _state.asStateFlow()

    fun beginSession(routeId: String, routeLabel: String, baselineIp: String?) {
        _state.value = TunnelStatus(
            phase = TunnelPhase.STARTING,
            routeId = routeId,
            routeLabel = routeLabel,
            baselineIp = baselineIp,
        )
    }

    fun phase(phase: TunnelPhase, message: String = "") {
        _state.update { it.copy(phase = phase, message = message) }
    }

    fun setTunnelIp(ip: String?) {
        _state.update { it.copy(tunnelIp = ip) }
    }

    fun error(message: String) {
        _state.update { it.copy(phase = TunnelPhase.ERROR, message = message) }
    }

    fun reset() {
        _state.value = TunnelStatus()
    }
}
