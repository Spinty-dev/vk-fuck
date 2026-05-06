package app.vkturn.proxy

/** Aggregate lifecycle stage of a single route. */
enum class RouteState {
    IDLE,
    STARTING,
    CONNECTING,
    CAPTCHA,
    CONNECTED,
    LOCKOUT,
    ERROR,
    STOPPING,
}

/** Per-route status — what the UI renders. */
data class RouteStatus(
    val routeId: String,
    val routeLabel: String,
    val state: RouteState = RouteState.IDLE,
    val message: String = "",
    val connectedStreams: Int = 0,
    val activeStreams: Int = 0,
    val totalStreams: Int = 0,
    val sessionMode: String = "",
    val captchaUrl: String? = null,
    val captchaUserAgent: String? = null,
    val lockoutSecondsRemaining: Int = 0,
    val streams: List<StreamTelemetry> = emptyList(),
    val capsVersion: Int = 0,
    val capabilities: List<String> = emptyList(),
    val outBytes: Long = 0,
    val inBytes: Long = 0,
) {
    val isRunning: Boolean get() = state != RouteState.IDLE && state != RouteState.ERROR
}
