package app.vkturn.proxy

import app.vkturn.model.CaptchaMode
import app.vkturn.model.LinkKind
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.model.TransportMode
import app.vkturn.model.TurnTransport

/**
 * Builds argv for the Go `vk-turn-proxy client` binary. Single source of
 * truth for flag wiring — UI never assembles flag strings by hand.
 *
 * The flag surface here mirrors `vk-turn-proxy/client/options.go` from
 * the checked-in server ref: -listen, -peer, -vk-link, -vk-link-secondary,
 * -yandex-link, -turn, -port, -n, -transport, -udp, -no-dtls,
 * -manual-captcha, -captcha-solver, -tcp-flavor, -creds-group-size,
 * -session-mode, -session-id, -adaptive-pool-min, -adaptive-pool-max,
 * -adaptive-pool-streams-per-id, -protect-sock, and the wb-stream /
 * room-exchange family.
 */
object ProxyArgs {
    fun build(
        server: Server,
        route: Route.VkTurnProxy,
        /** When running under a desktop/Android TUN daemon we point the
         *  proxy at its protect socket so TURN sockets don't loop back
         *  through our own TUN. */
        protectSocketPath: String? = null,
    ): List<String> = buildList {
        add("-listen"); add("${route.listenHost}:${route.listenPort}")
        add("-peer"); add("${server.host}:${server.proxyPort}")

        when (route.linkKind) {
            LinkKind.VK -> {
                val primary = route.primaryLinks.map { it.trim() }.filter { it.isNotEmpty() }
                if (primary.isNotEmpty()) {
                    add("-vk-link")
                    add(primary.joinToString(","))
                }
                if (route.secondaryLink.isNotBlank()) {
                    add("-vk-link-secondary"); add(route.secondaryLink.trim())
                }
            }
            LinkKind.YANDEX -> {
                // Yandex accepts exactly one link; we take the first primary.
                val first = route.primaryLinks.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (first.isNotEmpty()) {
                    add("-yandex-link"); add(first)
                }
            }
        }

        if (route.overrideTurnHost.isNotBlank()) {
            add("-turn"); add(route.overrideTurnHost.trim())
        }
        if (route.overrideTurnPort.isNotBlank()) {
            add("-port"); add(route.overrideTurnPort.trim())
        }
        if (route.streams > 0) {
            add("-n"); add(route.streams.toString())
        }

        add("-transport"); add(route.transport.flag)
        if (route.transport == TransportMode.TCP) {
            add("-tcp-flavor"); add(route.tcpFlavor.flag)
        }
        if (route.turnTransport == TurnTransport.UDP) add("-udp")
        if (route.directNoDtls) add("-no-dtls")
        if (route.debug) add("-debug")

        if (route.captchaMode == CaptchaMode.MANUAL_ONLY) add("-manual-captcha")
        add("-captcha-solver"); add(route.captchaSolver.flag)

        add("-session-mode"); add(route.sessionMode.flag)
        if (route.sessionId.isNotBlank()) {
            add("-session-id"); add(route.sessionId.trim())
        }
        if (route.credsGroupSize > 0) {
            add("-creds-group-size"); add(route.credsGroupSize.toString())
        }
        if (route.adaptivePoolMin > 0) {
            add("-adaptive-pool-min"); add(route.adaptivePoolMin.toString())
        }
        if (route.adaptivePoolMax > 0) {
            add("-adaptive-pool-max"); add(route.adaptivePoolMax.toString())
        }
        if (route.adaptivePoolStreamsPerIdentity > 0) {
            add("-adaptive-pool-streams-per-id")
            add(route.adaptivePoolStreamsPerIdentity.toString())
        }

        if (route.wbStream.enabled && route.wbStream.roomId.isNotBlank()) {
            add("-wb-stream-room-id"); add(route.wbStream.roomId.trim())
            if (route.wbStream.displayName.isNotBlank()) {
                add("-wb-stream-display-name"); add(route.wbStream.displayName.trim())
            }
            if (route.wbStream.e2eSecretB64.isNotBlank()) {
                add("-wb-stream-e2e-secret"); add(route.wbStream.e2eSecretB64.trim())
            }
        }

        if (protectSocketPath != null && protectSocketPath.isNotBlank()) {
            add("-protect-sock"); add(protectSocketPath)
        }
    }
}
