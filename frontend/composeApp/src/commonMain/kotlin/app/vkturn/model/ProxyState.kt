package app.vkturn.model

/** High-level connection state mirrored by [app.vkturn.proxy.ProxyController]. */
enum class ConnectionState {
    DISCONNECTED,
    STARTING,
    CONNECTING,
    CAPTCHA,
    CONNECTED,
    ERROR,
}

data class ProxyStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedStreams: Int = 0,
    val totalStreams: Int = 0,
    val message: String = "",
    /** When non-null the UI should offer to open the captcha URL. */
    val captchaUrl: String? = null,
)
