package app.vkturn.wingsv

import app.vkturn.model.AppConfig
import app.vkturn.model.CaptchaMode
import app.vkturn.model.CaptchaSolver
import app.vkturn.model.Identity
import app.vkturn.model.IdentityWg
import app.vkturn.model.LinkKind
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.model.SessionMode
import app.vkturn.model.TransportMode
import app.vkturn.model.TurnTransport
import app.vkturn.model.WgFlavor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses a WingsV subscription token — either a `wingsv://…` URI pasted
 * verbatim, a plain base64url payload, or a URL that resolves to one —
 * and folds the resulting [WingsvConfig] into the current [AppConfig]
 * as a fresh Server / Identity / Route tree.
 *
 * Format (from `3x-ui/web/service/vk_turn_proxy_service.go`):
 *
 *   wingsv://BASE64URL( 0x12 | zlib(proto.Marshal(Config)) )
 *
 * Unknown proto fields are tolerated by the codec; we only consume the
 * subset we know how to map. Xray / VLESS profiles are intentionally
 * skipped for this drop — the mental model stays WG-first.
 */
object WingsvImporter {

    data class Result(
        val config: AppConfig,
        val serverId: String,
        val identityId: String,
        val routeId: String,
        val notes: List<String>,
    )

    sealed class Outcome {
        data class Ok(val result: Result) : Outcome()
        data class Fail(val message: String) : Outcome()
    }

    private const val WINGSV_PREFIX = "wingsv://"
    private const val FRAME_PROTO_DEFLATE: Byte = 0x12

    fun import(raw: String, into: AppConfig): Outcome = runCatching {
        val token = normalize(raw) ?: return Outcome.Fail("Не получилось найти wingsv:// токен")
        val bytes = decodeToken(token) ?: return Outcome.Fail("Не удалось декодировать токен")
        val config = decodeProto(bytes) ?: return Outcome.Fail("Неправильный формат: proto не разобрался")
        val notes = mutableListOf<String>()
        val (updated, serverId, identityId, routeId) = merge(into, config, notes)
        Outcome.Ok(Result(updated, serverId, identityId, routeId, notes))
    }.getOrElse { Outcome.Fail("Ошибка импорта: ${it.message ?: it::class.simpleName}") }

    // ---- pipeline -----------------------------------------------------

    /** Accepts `wingsv://...`, a bare base64url string, or an http(s) URL. */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trim('\'', '"')
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val fetched = fetchSubscription(trimmed)?.trim() ?: return null
            return normalize(fetched)
        }
        val body = trimmed.removePrefix(WINGSV_PREFIX)
        return body.ifBlank { null }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeToken(body: String): ByteArray? {
        val cleaned = body.replace("\n", "").replace("\r", "").replace(" ", "")
        // Go's base64.URLEncoding is standard-alphabet URL-safe with padding;
        // we accept both padded and unpadded variants to be forgiving.
        val decoded = runCatching { Base64.UrlSafe.decode(cleaned) }
            .recoverCatching { Base64.UrlSafe.decode(cleaned.trimEnd('=')) }
            .getOrNull() ?: return null

        if (decoded.isEmpty() || decoded[0] != FRAME_PROTO_DEFLATE) return null
        val deflated = decoded.copyOfRange(1, decoded.size)
        return runCatching { inflateZlib(deflated) }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeProto(bytes: ByteArray): WingsvConfig? {
        val codec = ProtoBuf { encodeDefaults = false }
        return runCatching { codec.decodeFromByteArray(WingsvConfig.serializer(), bytes) }.getOrNull()
    }

    // ---- mapping proto -> tree ----------------------------------------

    private data class Merged(
        val config: AppConfig,
        val serverId: String,
        val identityId: String,
        val routeId: String,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun merge(base: AppConfig, source: WingsvConfig, notes: MutableList<String>): Merged {
        val turn = source.turn ?: error("turn section missing")
        val wgProto = source.wg
        val awgProto = source.awg

        val peerEndpoint = turn.endpoint
        val peerHost = peerEndpoint?.host?.takeIf { it.isNotBlank() } ?: error("turn.endpoint.host missing")
        val peerPort = peerEndpoint.port.takeIf { it > 0 } ?: 56000

        val serverName = "wingsv ${peerHost}"
        val server = Server(name = serverName, host = peerHost, proxyPort = peerPort)

        val (wg, flavorNote) = renderIdentityWg(wgProto, awgProto, notes)
        flavorNote?.let { notes.add(it) }

        val sessionMode = when (turn.sessionMode) {
            Wingsv.TURN_SESSION_MAINLINE -> SessionMode.MAINLINE
            Wingsv.TURN_SESSION_MUX -> SessionMode.MU
            else -> SessionMode.AUTO
        }

        val primaryLinks = buildList {
            if (turn.link.isNotBlank()) add(turn.link)
            turn.links.filter { it.isNotBlank() && it != turn.link }.forEach { add(it) }
        }

        val listenHost = turn.localEndpoint?.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        val listenPort = turn.localEndpoint?.port?.takeIf { it > 0 } ?: 9000

        val route = Route.VkTurnProxy(
            label = "imported",
            linkKind = if (primaryLinks.any { it.contains("telemost.yandex.ru", true) }) LinkKind.YANDEX else LinkKind.VK,
            primaryLinks = primaryLinks,
            secondaryLink = turn.linkSecondary,
            listenHost = listenHost,
            listenPort = listenPort,
            streams = turn.threads ?: 0,
            transport = TransportMode.DATAGRAM,
            turnTransport = if (turn.useUdp == true) TurnTransport.UDP else TurnTransport.TCP,
            overrideTurnHost = turn.host,
            overrideTurnPort = turn.port?.toString().orEmpty(),
            captchaMode = CaptchaMode.AUTO_THEN_MANUAL,
            captchaSolver = CaptchaSolver.V2,
            sessionMode = sessionMode,
            credsGroupSize = turn.credsGroupSize ?: 12,
            directNoDtls = turn.noObfuscation == true,
        )

        val identity = Identity(
            name = "imported",
            wg = wg,
            routes = listOf(route),
            activeRouteId = route.id,
        )

        val populatedServer = server.copy(identities = listOf(identity))
        val nextConfig = base.copy(
            servers = base.servers + populatedServer,
            activeServerId = populatedServer.id,
            activeIdentityId = identity.id,
        )
        return Merged(nextConfig, populatedServer.id, identity.id, route.id)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun renderIdentityWg(
        wg: WingsvWireGuard?,
        awg: WingsvAmneziaWg?,
        notes: MutableList<String>,
    ): Pair<IdentityWg, String?> {
        // Plain WG branch is straightforward — just base64-encode the bytes
        // back the way wg-quick expects them.
        if (wg != null && wg.iface != null && wg.peer != null) {
            val iface = wg.iface
            val peer = wg.peer
            val addresses = iface.addrs.ifEmpty { listOf("10.8.0.2/24") }.joinToString(", ")
            val dns = iface.dns.joinToString(", ")
            val allowed = peer.allowedIps.joinToString(", ") { cidrToString(it) }
                .ifBlank { "0.0.0.0/0, ::/0" }

            return IdentityWg(
                flavor = WgFlavor.WIREGUARD,
                privateKey = b64(iface.privateKey),
                address = addresses,
                dns = dns,
                mtu = iface.mtu ?: 1280,
                peerPublicKey = b64(peer.publicKey),
                peerPresharedKey = if (peer.presharedKey.isNotEmpty()) b64(peer.presharedKey) else "",
                allowedIps = allowed,
                persistentKeepalive = 25,
            ) to null
        }

        // AmneziaWG arrives as a wg-quick config body — we do a small
        // key=value parse to lift the bits we need. Unsupported keys are
        // ignored; the user can still review the raw text later.
        if (awg != null && awg.awgQuickConfig.isNotBlank()) {
            return parseAwgQuick(awg.awgQuickConfig) to null
        }

        notes.add("Подписка не содержит ни WG, ни AmneziaWG-ключей — identity создан с пустыми полями.")
        return IdentityWg() to null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(bytes: ByteArray): String = Base64.encode(bytes)

    private fun cidrToString(cidr: WingsvCidr): String {
        val ip = when (cidr.addr.size) {
            4 -> cidr.addr.joinToString(".") { (it.toInt() and 0xff).toString() }
            16 -> cidr.addr.toHexColonsIPv6()
            else -> return ""
        }
        return "$ip/${cidr.prefix}"
    }

    private fun ByteArray.toHexColonsIPv6(): String {
        val parts = (0 until 16 step 2).map { i ->
            val hi = (this[i].toInt() and 0xff) shl 8
            val lo = this[i + 1].toInt() and 0xff
            (hi or lo).toString(16)
        }
        return parts.joinToString(":")
    }

    private fun parseAwgQuick(body: String): IdentityWg {
        val props = HashMap<String, MutableList<String>>()
        for (lineRaw in body.lineSequence()) {
            val line = lineRaw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("[")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            props.getOrPut(k) { mutableListOf() }.add(v)
        }
        fun single(k: String) = props[k]?.firstOrNull().orEmpty()
        fun intOr(k: String, default: Int) = single(k).toIntOrNull() ?: default
        fun longOr(k: String, default: Long) = single(k).toLongOrNull() ?: default
        return IdentityWg(
            flavor = WgFlavor.AMNEZIA,
            privateKey = single("PrivateKey"),
            address = single("Address").ifBlank { "10.8.0.2/24" },
            dns = single("DNS"),
            mtu = intOr("MTU", 1280),
            peerPublicKey = single("PublicKey"),
            peerPresharedKey = single("PresharedKey"),
            allowedIps = single("AllowedIPs").ifBlank { "0.0.0.0/0, ::/0" },
            persistentKeepalive = intOr("PersistentKeepalive", 25),
            awgJc = intOr("Jc", 4),
            awgJmin = intOr("Jmin", 40),
            awgJmax = intOr("Jmax", 70),
            awgS1 = intOr("S1", 0),
            awgS2 = intOr("S2", 0),
            awgH1 = longOr("H1", 1),
            awgH2 = longOr("H2", 2),
            awgH3 = longOr("H3", 3),
            awgH4 = longOr("H4", 4),
        )
    }
}
