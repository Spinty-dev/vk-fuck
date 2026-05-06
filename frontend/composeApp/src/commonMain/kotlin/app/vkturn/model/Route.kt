package app.vkturn.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A route is "how this identity reaches the server's TURN listener".
 *
 * Three shapes:
 *   - [VkTurnProxy]: spawn the Go `vk-turn-proxy` client, UDP/TCP transport,
 *     with all captcha / session / flavor controls.
 *   - [SingBox]: spawn sing-box with a VLESS outbound (optionally chained
 *     through a vk-turn-proxy tcp transport) and expose a local socks/http
 *     endpoint or TUN.
 *   - [Direct]: no proxy, WG client talks to the VPS directly.
 */
@Serializable
sealed class Route {
    abstract val id: String
    abstract val label: String
    abstract val enabled: Boolean

    @Serializable
    data class VkTurnProxy(
        override val id: String = randomRouteId("vkp"),
        override val label: String = "vk-turn-proxy",
        override val enabled: Boolean = true,
        val linkKind: LinkKind = LinkKind.VK,
        val primaryLinks: List<String> = emptyList(),
        val secondaryLink: String = "",

        val listenHost: String = "127.0.0.1",
        val listenPort: Int = 9000,

        val streams: Int = 0,
        val transport: TransportMode = TransportMode.DATAGRAM,
        val turnTransport: TurnTransport = TurnTransport.TCP,
        val tcpFlavor: TcpFlavor = TcpFlavor.AUTO,

        val overrideTurnHost: String = "",
        val overrideTurnPort: String = "",

        val captchaMode: CaptchaMode = CaptchaMode.AUTO_THEN_MANUAL,
        val captchaSolver: CaptchaSolver = CaptchaSolver.V2,

        val sessionMode: SessionMode = SessionMode.AUTO,
        val sessionId: String = "",
        val credsGroupSize: Int = 12,

        val adaptivePoolMin: Int = 1,
        val adaptivePoolMax: Int = 0,
        val adaptivePoolStreamsPerIdentity: Int = 0,

        val directNoDtls: Boolean = false,
        val debug: Boolean = false,

        val wbStream: WbStream = WbStream(),
    ) : Route() {
        val effectiveStreams: Int
            get() = when {
                streams > 0 -> streams
                linkKind == LinkKind.VK -> 10
                else -> 1
            }
    }

    @Serializable
    data class SingBox(
        override val id: String = randomRouteId("sb"),
        override val label: String = "sing-box",
        override val enabled: Boolean = true,

        /** VLESS "vless://..." URI, or an already-parsed profile. */
        val vlessLink: String = "",
        val socksListenHost: String = "127.0.0.1",
        val socksListenPort: Int = 10808,
        val httpListenPort: Int = 0,

        val chainedThroughProxyRouteId: String? = null,
        val tunEnabled: Boolean = false,
        val tunMtu: Int = 1500,

        val logLevel: String = "warn",
    ) : Route()

    @Serializable
    data class Direct(
        override val id: String = randomRouteId("dir"),
        override val label: String = "direct",
        override val enabled: Boolean = true,
        val endpoint: String = "",
    ) : Route()

    companion object {
        fun defaultVkTurn(): VkTurnProxy = VkTurnProxy()
        fun defaultSingBox(): SingBox = SingBox()
        fun defaultDirect(): Direct = Direct()
    }
}

@Serializable
data class WbStream(
    val enabled: Boolean = false,
    val roomId: String = "",
    val displayName: String = "vkturn-client",
    val e2eEnabled: Boolean = false,
    val e2eSecretB64: String = "",
    /** If true, deliver the room metadata through VK TURN handshake first. */
    val exchangeViaVkTurn: Boolean = true,
)

@Serializable
enum class TransportMode(val flag: String, val label: String) {
    DATAGRAM("datagram", "Datagram (UDP)"),
    TCP("tcp", "TCP (KCP+smux)"),
}

@Serializable
enum class TcpFlavor(val flag: String, val label: String) {
    AUTO("auto", "Auto"),
    DIRECT("direct", "Direct (smux over DTLS)"),
    LEGACY("legacy", "Legacy (KCP+smux)"),
}

@Serializable
enum class CaptchaSolver(val flag: String, val label: String) {
    V2("v2", "v2 (улучшенный)"),
    V1("v1", "v1 (legacy)"),
}

@Serializable
enum class SessionMode(val flag: String, val label: String) {
    AUTO("auto", "Auto"),
    MAINLINE("mainline", "Mainline"),
    MU("mu", "Multi-user (mu)"),
}

private fun randomRouteId(prefix: String): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val suffix = buildString { repeat(10) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    return "$prefix-$suffix"
}
