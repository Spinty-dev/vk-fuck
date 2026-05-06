package app.vkturn.tunnel

import android.net.Network
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal public-IP fetcher used to prove the VPN actually carries traffic.
 *
 * Why several endpoints: any single provider can be rate-limited, blocked in
 * Russia, or return HTML error pages. We try them in order and accept the
 * first plaintext body that parses as an IPv4/IPv6 literal.
 *
 * When [network] is non-null the connection is explicitly bound via
 * [Network.openConnection]; that bypasses the `addDisallowedApplication`
 * exclusion that wireguard-android adds for our own UID, so a VPN-scoped
 * request actually rides the tunnel and reveals the exit address.
 */
object PublicIpProbe {
    private const val TAG = "vkturn/ip-probe"

    private val ENDPOINTS = listOf(
        "https://api.ipify.org/?format=text",
        "https://ifconfig.me/ip",
        "https://icanhazip.com/",
        "https://ipv4.icanhazip.com/",
    )

    fun fetch(network: Network? = null, timeoutMs: Int = 5000): String? {
        for (ep in ENDPOINTS) {
            val result = runCatching { fetchOnce(network, ep, timeoutMs) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun fetchOnce(network: Network?, urlString: String, timeoutMs: Int): String? {
        val url = URL(urlString)
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "vkturn/ipprobe")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "$urlString returned HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            return validate(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun validate(raw: String): String? {
        val first = raw.lineSequence().firstOrNull()?.trim() ?: return null
        if (first.length > 64) return null
        // Accept IPv4 (1.2.3.4) and unbracketed IPv6.
        val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        val ipv6 = Regex("^[0-9a-fA-F:]{2,45}$")
        return if (ipv4.matches(first) || ipv6.matches(first)) first else null
    }
}
