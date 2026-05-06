package app.vkturn.wireguard

/** Base64-encoded WireGuard keypair (32-byte X25519 keys). */
data class WgKeyPair(val privateKey: String, val publicKey: String)

/**
 * Platform-backed X25519 keygen. Delegating to the OS/JDK keeps the crypto
 * story auditable — JDK 11+ implements X25519 in SunEC, Android goes
 * through the reference WireGuard keypair class.
 */
expect object WgKeyGen {
    fun generate(): WgKeyPair
    fun derivePublic(privateKeyBase64: String): String
}
