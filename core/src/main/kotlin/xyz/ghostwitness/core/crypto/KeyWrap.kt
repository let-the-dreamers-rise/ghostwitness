package xyz.ghostwitness.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.hpke.HPKE

private val WRAP_INFO = "ghostwitness/v2/wrapped-key".toByteArray()
private const val X25519_ENC_BYTES = 32

class KeyUnwrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The recovery holder's X25519 key pair. Only the public half goes into the setup QR. */
class RecoveryKeyPair(publicKey: ByteArray, privateKey: ByteArray) {
    private val publicBytes = publicKey.copyOf()
    private val privateBytes = privateKey.copyOf()
    val publicKey: ByteArray get() = publicBytes.copyOf()
    val privateKey: ByteArray get() = privateBytes.copyOf()
}

/**
 * Wraps the session key K to the recovery holder's public key with HPKE base mode (RFC 9180:
 * DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM). This is the standard equivalent of the
 * libsodium sealed box named in the spec. The session id is the AAD, so a wrapped key can't be moved
 * to another session. Output: `enc(32) || ciphertext`.
 */
object KeyWrap {
    private fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

    fun generateRecoveryKey(): RecoveryKeyPair {
        val hpke = hpke()
        val pair = hpke.generatePrivateKey()
        return RecoveryKeyPair(hpke.serializePublicKey(pair.public), hpke.serializePrivateKey(pair.private))
    }

    fun wrap(key: ByteArray, recoveryPublicKey: ByteArray, sessionId: ByteArray): ByteArray {
        val hpke = hpke()
        val sealed = hpke.seal(hpke.deserializePublicKey(recoveryPublicKey), WRAP_INFO, sessionId, key, null, null, null)
        val ciphertext = sealed[0]
        val enc = sealed[1]
        check(enc.size == X25519_ENC_BYTES) { "unexpected HPKE enc size ${enc.size}" }
        return Bytes.concat(enc, ciphertext)
    }

    fun unwrap(wrapped: ByteArray, recovery: RecoveryKeyPair, sessionId: ByteArray): ByteArray {
        if (wrapped.size <= X25519_ENC_BYTES) throw KeyUnwrapException("wrapped key is too short")
        val hpke = hpke()
        val pair = hpke.deserializePrivateKey(recovery.privateKey, recovery.publicKey)
        val enc = wrapped.copyOfRange(0, X25519_ENC_BYTES)
        val ciphertext = wrapped.copyOfRange(X25519_ENC_BYTES, wrapped.size)
        try {
            return hpke.open(enc, pair, WRAP_INFO, sessionId, ciphertext, null, null, null)
        } catch (e: InvalidCipherTextException) {
            throw KeyUnwrapException("wrapped key does not open with this recovery key", e)
        }
    }
}
