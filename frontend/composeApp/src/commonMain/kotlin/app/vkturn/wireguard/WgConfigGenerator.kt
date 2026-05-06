package app.vkturn.wireguard

import app.vkturn.model.IdentityWg
import app.vkturn.model.WgFlavor

/**
 * Produces a wg-quick compatible config for a single identity. For
 * [WgFlavor.AMNEZIA] the Amnezia obfuscation parameters are appended to
 * the `[Interface]` block — that's what AmneziaVPN/AmneziaWG userspace
 * understands directly.
 *
 * @param localEndpoint `host:port` of the local tunnel endpoint when the
 *        WG client is expected to talk to a local vk-turn-proxy listen,
 *        e.g. `"127.0.0.1:9000"`. For a direct route pass the remote
 *        VPS endpoint instead.
 */
object WgConfigGenerator {
    fun render(w: IdentityWg, localEndpoint: String): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${w.privateKey.trim()}")
        appendLine("Address = ${w.address.filterIpv4Only()}")
        // if (w.dns.isNotBlank()) appendLine("DNS = ${w.dns.trim()}")
        appendLine("MTU = ${w.mtu}")
        if (w.flavor == WgFlavor.AMNEZIA) {
            appendLine("Jc = ${w.awgJc}")
            appendLine("Jmin = ${w.awgJmin}")
            appendLine("Jmax = ${w.awgJmax}")
            appendLine("S1 = ${w.awgS1}")
            appendLine("S2 = ${w.awgS2}")
            appendLine("H1 = ${w.awgH1}")
            appendLine("H2 = ${w.awgH2}")
            appendLine("H3 = ${w.awgH3}")
            appendLine("H4 = ${w.awgH4}")
        }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${w.peerPublicKey.trim()}")
        if (w.peerPresharedKey.isNotBlank()) {
            appendLine("PresharedKey = ${w.peerPresharedKey.trim()}")
        }
        appendLine("AllowedIPs = ${w.allowedIps.filterIpv4Only()}")
        appendLine("Endpoint = ${localEndpoint.trim()}")
        appendLine("PersistentKeepalive = ${w.persistentKeepalive}")
    }
    
    private fun String.filterIpv4Only(): String =
        this.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains(":") }
            .joinToString(", ")
}
