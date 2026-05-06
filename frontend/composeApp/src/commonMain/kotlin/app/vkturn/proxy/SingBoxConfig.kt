package app.vkturn.proxy

import app.vkturn.model.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Produces a sing-box 1.10+ configuration JSON from a [Route.SingBox]
 * model. The generated config:
 *
 *   - inbound: SOCKS5 (+ optional HTTP) listening on 127.0.0.1.
 *   - outbound: VLESS parsed from [Route.SingBox.vlessLink].
 *   - optional: chain through a local vk-turn-proxy TCP listen by adding
 *     a `socks` dialer around the VLESS outbound.
 *
 * TUN inbound is currently disabled at this layer; it will be emitted by
 * the privileged helper daemon once that's in place.
 */
object SingBoxConfig {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    data class BuildInput(
        val route: Route.SingBox,
        val chainedProxyListen: String? = null,
        /** Clash-API controller port for stats polling. 0 disables. */
        val clashApiPort: Int = 0,
    )

    fun render(input: BuildInput): String {
        val vless = parseVlessLink(input.route.vlessLink)
        val obj = buildJsonObject {
            put("log", buildJsonObject {
                put("level", input.route.logLevel)
                put("timestamp", true)
            })
            if (input.clashApiPort > 0) {
                put("experimental", buildJsonObject {
                    put("clash_api", buildJsonObject {
                        put("external_controller", "127.0.0.1:${input.clashApiPort}")
                        put("default_mode", "rule")
                    })
                })
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "socks")
                    put("tag", "socks-in")
                    put("listen", input.route.socksListenHost)
                    put("listen_port", input.route.socksListenPort)
                    put("sniff", true)
                    put("domain_strategy", "prefer_ipv4")
                }
                if (input.route.httpListenPort > 0) {
                    addJsonObject {
                        put("type", "http")
                        put("tag", "http-in")
                        put("listen", input.route.socksListenHost)
                        put("listen_port", input.route.httpListenPort)
                    }
                }
            }
            putJsonArray("outbounds") {
                add(buildVlessOutbound(vless, chainedProxyListen = input.chainedProxyListen))
                if (input.chainedProxyListen != null) {
                    add(buildSocksDialer(input.chainedProxyListen))
                }
                addJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                }
                addJsonObject {
                    put("type", "block")
                    put("tag", "block")
                }
            }
            put("route", buildJsonObject {
                put("final", "proxy")
                putJsonArray("rules") {
                    addJsonObject {
                        put("protocol", "dns")
                        put("outbound", "direct")
                    }
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildVlessOutbound(
        v: ParsedVless,
        chainedProxyListen: String?,
    ): JsonObject = buildJsonObject {
        put("type", "vless")
        put("tag", "proxy")
        put("server", v.host)
        put("server_port", v.port)
        put("uuid", v.uuid)
        if (v.flow.isNotBlank()) put("flow", v.flow)
        putJsonObject("tls") {
            put("enabled", v.security == "tls" || v.security == "reality")
            if (v.sni.isNotBlank()) put("server_name", v.sni)
            if (v.alpn.isNotEmpty()) putJsonArray("alpn") { v.alpn.forEach { add(it) } }
            if (v.security == "reality") {
                putJsonObject("reality") {
                    put("enabled", true)
                    if (v.publicKey.isNotBlank()) put("public_key", v.publicKey)
                    if (v.shortId.isNotBlank()) put("short_id", v.shortId)
                }
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", v.fingerprint.ifBlank { "chrome" })
                }
            }
        }
        putJsonObject("transport") {
            when (v.transport) {
                "ws" -> {
                    put("type", "ws")
                    put("path", v.path.ifBlank { "/" })
                    if (v.host.isNotBlank()) {
                        putJsonObject("headers") { put("Host", v.hostHeader.ifBlank { v.host }) }
                    }
                }
                "grpc" -> {
                    put("type", "grpc")
                    put("service_name", v.serviceName)
                }
                else -> { /* tcp - omit transport block */ }
            }
        }
        if (chainedProxyListen != null) put("detour", "vkturn-chain")
    }

    private fun buildSocksDialer(chainedProxyListen: String): JsonObject = buildJsonObject {
        put("type", "socks")
        put("tag", "vkturn-chain")
        val hostPort = chainedProxyListen.split(":")
        put("server", hostPort.getOrNull(0) ?: "127.0.0.1")
        put("server_port", hostPort.getOrNull(1)?.toIntOrNull() ?: 9000)
        put("version", "5")
    }

    /**
     * Very small tolerant parser for `vless://uuid@host:port?params#name`.
     * Only the params we care about are extracted — unknown params are
     * ignored rather than rejected so we're forward-compatible with the
     * sing-box / v2rayN extensions that pop up over time.
     */
    data class ParsedVless(
        val uuid: String,
        val host: String,
        val port: Int,
        val security: String,
        val sni: String,
        val alpn: List<String>,
        val flow: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val transport: String,
        val path: String,
        val hostHeader: String,
        val serviceName: String,
    )

    fun parseVlessLink(link: String): ParsedVless {
        val body = link.trim().removePrefix("vless://").substringBefore('#')
        val at = body.indexOf('@')
        val uuid = if (at > 0) body.substring(0, at) else ""
        val rest = if (at > 0) body.substring(at + 1) else body
        val qIndex = rest.indexOf('?')
        val hostPortPath = if (qIndex >= 0) rest.substring(0, qIndex) else rest
        val query = if (qIndex >= 0) rest.substring(qIndex + 1) else ""

        val hostPart = hostPortPath.substringBefore('/')
        val (host, port) = if (hostPart.contains(":")) {
            val idx = hostPart.lastIndexOf(':')
            hostPart.substring(0, idx) to (hostPart.substring(idx + 1).toIntOrNull() ?: 443)
        } else hostPart to 443

        val params = query.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else decode(it.substring(0, eq)) to decode(it.substring(eq + 1))
        }.toMap()

        return ParsedVless(
            uuid = uuid,
            host = host,
            port = port,
            security = params["security"].orEmpty(),
            sni = params["sni"].orEmpty(),
            alpn = params["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            flow = params["flow"].orEmpty(),
            publicKey = params["pbk"].orEmpty(),
            shortId = params["sid"].orEmpty(),
            fingerprint = params["fp"].orEmpty(),
            transport = params["type"].orEmpty(),
            path = params["path"].orEmpty(),
            hostHeader = params["host"].orEmpty(),
            serviceName = params["serviceName"].orEmpty(),
        )
    }

    private fun decode(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val n = hex.toIntOrNull(16)
                if (n != null) {
                    append(n.toChar()); i += 3; continue
                }
            }
            if (c == '+') append(' ') else append(c)
            i += 1
        }
    }
}
