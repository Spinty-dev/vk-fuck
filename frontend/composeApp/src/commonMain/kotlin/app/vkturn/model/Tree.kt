package app.vkturn.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Top-level configuration tree persisted to disk. Schema version is
 * explicit so [app.vkturn.persistence.Migration] can rebuild older
 * shapes into the current one.
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val servers: List<Server> = emptyList(),
    val activeServerId: String? = null,
    val activeIdentityId: String? = null,
    val singBoxBinaryOverride: String = "",
    val proxyBinaryOverride: String = "",
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }

    fun server(id: String?): Server? = id?.let { servers.firstOrNull { s -> s.id == it } }
    fun identity(serverId: String?, identityId: String?): Identity? =
        server(serverId)?.identities?.firstOrNull { it.id == identityId }

    val activeServer: Server? get() = server(activeServerId) ?: servers.firstOrNull()
    val activeIdentity: Identity?
        get() = activeServer?.let { s ->
            s.identities.firstOrNull { it.id == activeIdentityId } ?: s.identities.firstOrNull()
        }
}

/**
 * A server is the "inbound" in the user's mental model: one VPS running
 * `vk-turn-proxy server`, exposing a WG/AWG peer and accepting TURN traffic.
 * Everything below it shares the same remote endpoint.
 */
@Serializable
data class Server(
    val id: String = randomId("srv"),
    val name: String = "Server",
    val host: String = "",
    val proxyPort: Int = 56000,
    val notes: String = "",
    val identities: List<Identity> = emptyList(),
)

/**
 * A WireGuard or AmneziaWG keypair that the user owns on that server. Each
 * identity can have multiple routes (ways to reach the server's TURN
 * listener). Only one route per identity is considered active at a time.
 */
@Serializable
data class Identity(
    val id: String = randomId("id"),
    val name: String = "identity",
    val wg: IdentityWg = IdentityWg(),
    val routes: List<Route> = listOf(Route.defaultVkTurn()),
    val activeRouteId: String? = null,
) {
    val activeRoute: Route
        get() = routes.firstOrNull { it.id == activeRouteId } ?: routes.first()
}

/**
 * WG/AWG material for a single identity. The peer's public key is shared
 * across all identities on the same [Server] (it belongs to the VPS), so
 * it is carried here for convenience on export but the source of truth for
 * the peer key still lives at the server level in practice.
 */
@Serializable
data class IdentityWg(
    val flavor: WgFlavor = WgFlavor.WIREGUARD,
    val privateKey: String = "",
    val address: String = "10.8.0.2/24",
    val dns: String = "1.1.1.1, 8.8.8.8",
    val mtu: Int = 1280,

    val peerPublicKey: String = "",
    val peerPresharedKey: String = "",
    val allowedIps: String = "0.0.0.0/0, ::/0",
    val persistentKeepalive: Int = 25,

    val awgJc: Int = 4,
    val awgJmin: Int = 40,
    val awgJmax: Int = 70,
    val awgS1: Int = 0,
    val awgS2: Int = 0,
    val awgH1: Long = 1,
    val awgH2: Long = 2,
    val awgH3: Long = 3,
    val awgH4: Long = 4,
)

private fun randomId(prefix: String): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val suffix = buildString {
        repeat(10) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
    return "$prefix-$suffix"
}
