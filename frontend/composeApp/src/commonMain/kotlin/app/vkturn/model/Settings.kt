package app.vkturn.model

import kotlinx.serialization.Serializable

/** What kind of invite link is used by a [Route.VkTurnProxy]. */
@Serializable
enum class LinkKind { VK, YANDEX }

/**
 * Captcha solving strategy — maps to a pair of CLI flags on the Go client:
 * `-manual-captcha` controls the "skip auto entirely" case and
 * `-captcha-solver` (v1|v2) switches the automatic solver implementation.
 */
@Serializable
enum class CaptchaMode(val label: String, val description: String) {
    AUTO_THEN_MANUAL(
        label = "Авто → слайдер → вручную",
        description = "По умолчанию: auto-попытка, затем POC-слайдер, и только потом ручной режим.",
    ),
    AUTO_ONLY(
        label = "Только auto",
        description = "Только автопопытка без POC-слайдера. В случае неудачи падает в ручной.",
    ),
    MANUAL_ONLY(
        label = "Только вручную",
        description = "Сразу ручной режим: `-manual-captcha`. Открывается браузер на localhost:8765.",
    ),
}

@Serializable
enum class TurnTransport(val label: String) {
    TCP("TCP"),
    UDP("UDP"),
}

/**
 * Legacy flat settings shape from schema v0. Kept only so that
 * [app.vkturn.persistence.Migration] can read it and fold it into
 * the new [AppConfig] tree.
 */
@Serializable
data class AppSettingsV0(
    val linkKind: LinkKind = LinkKind.VK,
    val vkLink: String = "",
    val yandexLink: String = "",
    val peerHost: String = "",
    val peerPort: Int = 56000,
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 9000,
    val streams: Int = 0,
    val transport: TurnTransport = TurnTransport.TCP,
    val overrideTurnHost: String = "",
    val overrideTurnPort: String = "",
    val captchaMode: CaptchaMode = CaptchaMode.AUTO_THEN_MANUAL,
    val vlessMode: Boolean = false,
    val directNoDtls: Boolean = false,
    val debug: Boolean = false,
    val binaryPath: String = "",
    val wg: WgSettingsV0 = WgSettingsV0(),
)

@Serializable
data class WgSettingsV0(
    val flavor: WgFlavor = WgFlavor.WIREGUARD,
    val clientPrivateKey: String = "",
    val clientAddress: String = "10.8.0.2/24",
    val clientDns: String = "1.1.1.1, 8.8.8.8",
    val mtu: Int = 1280,
    val serverPublicKey: String = "",
    val serverPresharedKey: String = "",
    val endpoint: String = "127.0.0.1:9000",
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
