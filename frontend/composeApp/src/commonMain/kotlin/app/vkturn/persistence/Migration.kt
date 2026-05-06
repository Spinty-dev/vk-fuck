package app.vkturn.persistence

import app.vkturn.model.AppConfig
import app.vkturn.model.AppSettingsV0
import app.vkturn.model.CaptchaMode
import app.vkturn.model.CaptchaSolver
import app.vkturn.model.Identity
import app.vkturn.model.IdentityWg
import app.vkturn.model.LinkKind
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.model.TransportMode
import app.vkturn.model.TurnTransport
import app.vkturn.model.WgSettingsV0
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deserializes persisted JSON into the current [AppConfig] shape,
 * upgrading from earlier schemas on the fly.
 *
 *   schema v0 — flat `AppSettings { linkKind, vkLink, ..., wg: WgSettings }`
 *   schema v1 — tree `AppConfig { servers: [Server { identities: [Identity { routes: [Route] }] }] }`
 */
object Migration {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun load(raw: String): AppConfig {
        if (raw.isBlank()) return AppConfig()
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { return AppConfig() }

        val version = root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        return when (version) {
            AppConfig.CURRENT_SCHEMA_VERSION -> decodeCurrent(root)
            else -> if (looksLikeV0(root)) upgradeV0(root) else decodeCurrent(root)
        }
    }

    fun save(config: AppConfig): String =
        json.encodeToString(AppConfig.serializer(), config)

    private fun decodeCurrent(root: JsonObject): AppConfig =
        runCatching { json.decodeFromJsonElement(AppConfig.serializer(), root) }
            .getOrElse { AppConfig() }

    private fun looksLikeV0(root: JsonObject): Boolean =
        root.containsKey("linkKind") || root.containsKey("peerHost") || root.containsKey("wg")

    private fun upgradeV0(root: JsonObject): AppConfig {
        val v0 = runCatching { json.decodeFromJsonElement(AppSettingsV0.serializer(), root) }
            .getOrElse { return AppConfig() }

        val wg = v0.wg
        val identity = Identity(
            name = "default",
            wg = IdentityWg(
                flavor = wg.flavor,
                privateKey = wg.clientPrivateKey,
                address = wg.clientAddress,
                dns = wg.clientDns,
                mtu = wg.mtu,
                peerPublicKey = wg.serverPublicKey,
                peerPresharedKey = wg.serverPresharedKey,
                allowedIps = wg.allowedIps,
                persistentKeepalive = wg.persistentKeepalive,
                awgJc = wg.awgJc,
                awgJmin = wg.awgJmin,
                awgJmax = wg.awgJmax,
                awgS1 = wg.awgS1,
                awgS2 = wg.awgS2,
                awgH1 = wg.awgH1,
                awgH2 = wg.awgH2,
                awgH3 = wg.awgH3,
                awgH4 = wg.awgH4,
            ),
            routes = listOf(
                Route.VkTurnProxy(
                    label = "imported",
                    linkKind = v0.linkKind,
                    primaryLinks = listOf(
                        when (v0.linkKind) {
                            LinkKind.VK -> v0.vkLink
                            LinkKind.YANDEX -> v0.yandexLink
                        },
                    ).filter { it.isNotBlank() },
                    listenHost = v0.listenHost,
                    listenPort = v0.listenPort,
                    streams = v0.streams,
                    transport = if (v0.vlessMode) TransportMode.TCP else TransportMode.DATAGRAM,
                    turnTransport = v0.transport,
                    overrideTurnHost = v0.overrideTurnHost,
                    overrideTurnPort = v0.overrideTurnPort,
                    captchaMode = v0.captchaMode,
                    captchaSolver = CaptchaSolver.V2,
                    directNoDtls = v0.directNoDtls,
                    debug = v0.debug,
                ),
            ),
        )
        val server = Server(
            name = "default",
            host = v0.peerHost,
            proxyPort = v0.peerPort,
            identities = listOf(identity.copy(activeRouteId = identity.routes.first().id)),
        )
        return AppConfig(
            schemaVersion = AppConfig.CURRENT_SCHEMA_VERSION,
            servers = listOf(server),
            activeServerId = server.id,
            activeIdentityId = server.identities.first().id,
            proxyBinaryOverride = v0.binaryPath,
        )
    }
}
