package app.vkturn.wireguard

import app.vkturn.model.Identity
import app.vkturn.model.Route
import app.vkturn.model.WgFlavor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * sing-box JSON: TUN (system routing) → WireGuard outbound → local vk-turn UDP listen,
 * mirroring Android (wg peer Endpoint = localhost:listen).
 *
 * Requires [IdentityWg.flavor] [WgFlavor.WIREGUARD]; AmneziaWG fields are not expressible
 * in stock sing-box WireGuard outbound.
 */
object SingBoxWgThroughVkTurnConfig {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun renderOrNull(identity: Identity, route: Route.VkTurnProxy): String? {
        val w = identity.wg
        if (w.flavor != WgFlavor.WIREGUARD) return null
        if (w.privateKey.isBlank() || w.peerPublicKey.isBlank()) return null
        val addr = w.address.trim()
        if (addr.isBlank()) return null

        val allowedParts = w.allowedIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val allowedArr = allowedParts.takeIf { it.isNotEmpty() } ?: listOf("0.0.0.0/0", "::/0")

        val tunIf = ifaceName(route.id)

        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "info")
                put("timestamp", true)
            })

            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", tunIf)
                    putJsonArray("address") { add(addr) }
                    put("mtu", w.mtu.coerceIn(576, 9000))
                    put("auto_route", true)
                    put("strict_route", true)
                    put("endpoint_independent_nat", true)
                    put("sniff", true)
                    put("domain_strategy", "prefer_ipv4")
                    put("stack", "mixed")
                }
            }

            putJsonArray("outbounds") {
                addJsonObject {
                    put("type", "wireguard")
                    put("tag", "wg-out")
                    put("server", route.listenHost.trim().ifBlank { "127.0.0.1" })
                    put("server_port", route.listenPort)
                    putJsonArray("local_address") { add(addr) }
                    put("private_key", w.privateKey.trim())
                    put("peer_public_key", w.peerPublicKey.trim())
                    if (w.peerPresharedKey.isNotBlank()) {
                        put("pre_shared_key", w.peerPresharedKey.trim())
                    }
                    putJsonArray("allowed_ips") {
                        allowedArr.forEach { add(it) }
                    }
                    put("mtu", w.mtu.coerceIn(576, 9000))
                    if (w.persistentKeepalive > 0) {
                        put("persistent_keepalive_interval", w.persistentKeepalive)
                    }
                }
                addJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                }
            }

            put("route", buildJsonObject {
                put("auto_detect_interface", true)
                put("final", "wg-out")
            })
        }

        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** IFNAMSIZ on Linux/macOS ≤ 15 chars. */
    fun ifaceForRoute(routeId: String): String =
        ifaceName(routeId)

    private fun ifaceName(routeId: String): String {
        val slug = routeId.filter { it.isLetterOrDigit() }.take(12)
        val s = slug.ifEmpty { "vkturn" }.take(12)
        // "vk" + "-" + slug ≤ 15
        return "vk-$s".take(15)
    }
}
