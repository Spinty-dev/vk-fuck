package app.vkturn.wireguard

import com.wireguard.crypto.KeyPair as WgLibKeyPair
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyFormatException

/**
 * Android uses the reference WireGuard keypair implementation from
 * `com.wireguard.android:tunnel`, which is already a dependency.
 */
actual object WgKeyGen {
    actual fun generate(): WgKeyPair {
        val kp = WgLibKeyPair()
        return WgKeyPair(
            privateKey = kp.privateKey.toBase64(),
            publicKey = kp.publicKey.toBase64(),
        )
    }

    actual fun derivePublic(privateKeyBase64: String): String {
        return try {
            val kp = WgLibKeyPair(Key.fromBase64(privateKeyBase64))
            kp.publicKey.toBase64()
        } catch (e: KeyFormatException) {
            throw IllegalArgumentException("Invalid WireGuard private key", e)
        }
    }
}
