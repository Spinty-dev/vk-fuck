package app.vkturn.wireguard

import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * JDK 11+ ships X25519 inside SunEC. A WireGuard keypair is just a clamped
 * 32-byte random scalar plus `priv * G` as the public key; we compute the
 * latter by running an XDH agreement against the basepoint `u = 9`, which
 * yields byte-for-byte the same output as `wg pubkey`.
 */
actual object WgKeyGen {
    private val spec = NamedParameterSpec.X25519
    private val basepointU: BigInteger = BigInteger.valueOf(9)

    actual fun generate(): WgKeyPair {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        clamp(bytes)
        val privB64 = Base64.getEncoder().encodeToString(bytes)
        return WgKeyPair(privB64, derivePublic(privB64))
    }

    actual fun derivePublic(privateKeyBase64: String): String {
        val priv = Base64.getDecoder().decode(privateKeyBase64)
        require(priv.size == 32) { "WireGuard private key must be 32 bytes" }
        val pub = scalarMultBase(priv)
        return Base64.getEncoder().encodeToString(pub)
    }

    private fun scalarMultBase(privateClamped: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val privKey = kf.generatePrivate(XECPrivateKeySpec(spec, privateClamped))
        val basePub = kf.generatePublic(XECPublicKeySpec(spec, basepointU))

        val ka = KeyAgreement.getInstance("XDH")
        ka.init(privKey)
        ka.doPhase(basePub, true)
        return ka.generateSecret()
    }

    private fun clamp(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
    }
}
