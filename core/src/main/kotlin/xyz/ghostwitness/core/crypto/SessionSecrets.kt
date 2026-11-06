package xyz.ghostwitness.core.crypto

import java.security.SecureRandom

/**
 * Per-session secrets created on the recording phone before capture starts (spec section 4).
 * K must stay in memory only; call [wipe] when the session reaches STOPPED.
 */
class SessionSecrets private constructor(
    private val sessionIdBytes: ByteArray,
    private val keyBytes: ByteArray,
    private val saltBytes: ByteArray,
) {
    val sessionId: ByteArray get() = sessionIdBytes.copyOf()
    val key: ByteArray get() = keyBytes.copyOf()
    val salt: ByteArray get() = saltBytes.copyOf()

    fun cipher(): ChunkCipher = ChunkCipher(keyBytes, saltBytes)

    /** Overwrites K in place. This is the one deliberate mutation: secrets must not linger in memory. */
    fun wipe() {
        keyBytes.fill(0)
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): SessionSecrets {
            fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }
            return SessionSecrets(randomBytes(SESSION_ID_BYTES), randomBytes(KEY_BYTES), randomBytes(SALT_BYTES))
        }
    }
}
