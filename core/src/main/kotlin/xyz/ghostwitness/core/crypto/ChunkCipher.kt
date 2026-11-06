package xyz.ghostwitness.core.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val KEY_BYTES = 32
const val SALT_BYTES = 8
const val SESSION_ID_BYTES = 16
const val GCM_TAG_BYTES = 16
private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * Binds a segment's ciphertext to its session, position, start time and the previous ciphertext.
 * Encoded as `session_id(16) || u32_be(i) || u64_be(t_start_ms) || prev_ct_hash(32)` (spec section 4).
 */
class SegmentAad(
    sessionId: ByteArray,
    val index: Int,
    val tStartMs: Long,
    prevCtHash: ByteArray,
) {
    private val sessionId: ByteArray = sessionId.copyOf()
    private val prevCtHash: ByteArray = prevCtHash.copyOf()

    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
        require(index >= 0) { "segment index must be non-negative" }
        require(prevCtHash.size == Bytes.HASH_BYTES) { "prev ct hash must be ${Bytes.HASH_BYTES} bytes" }
    }

    fun encode(): ByteArray = Bytes.concat(sessionId, Bytes.u32be(index), Bytes.u64be(tStartMs), prevCtHash)

    companion object {
        /** `prev_ct_hash` for segment 0. */
        fun genesisPrevHash(): ByteArray = Bytes.zeros(Bytes.HASH_BYTES)
    }
}

class SegmentAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-256-GCM with a deterministic nonce `salt(8) || u32_be(i)`. Safe because K is fresh per session and
 * each index is encrypted once.
 */
class ChunkCipher(key: ByteArray, salt: ByteArray) {
    private val key: ByteArray = key.copyOf()
    private val salt: ByteArray = salt.copyOf()

    @Volatile
    private var wiped = false

    init {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
    }

    fun nonceFor(index: Int): ByteArray = Bytes.concat(salt, Bytes.u32be(index))

    fun encrypt(plaintext: ByteArray, aad: SegmentAad): ByteArray = apply(Cipher.ENCRYPT_MODE, plaintext, aad)

    fun decrypt(ciphertext: ByteArray, aad: SegmentAad): ByteArray {
        try {
            return apply(Cipher.DECRYPT_MODE, ciphertext, aad)
        } catch (e: AEADBadTagException) {
            throw SegmentAuthException("segment ${aad.index} failed authentication", e)
        }
    }

    /** Overwrites K in place and refuses further use, so nothing is ever encrypted under a zero key. */
    fun wipe() {
        wiped = true
        key.fill(0)
    }

    private fun apply(mode: Int, input: ByteArray, aad: SegmentAad): ByteArray {
        check(!wiped) { "session key has been wiped" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonceFor(aad.index)))
        cipher.updateAAD(aad.encode())
        return cipher.doFinal(input)
    }
}
