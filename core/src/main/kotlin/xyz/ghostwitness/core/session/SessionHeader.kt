package xyz.ghostwitness.core.session

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SALT_BYTES
import xyz.ghostwitness.core.crypto.SESSION_ID_BYTES

class SessionFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val HEADER_MAGIC = "GWS".toByteArray()
private const val HEADER_VERSION = 1

/**
 * Everything a holder or the recovery device needs about a session besides the shards: the salt for the
 * nonces, the session public key that signs roots, and K wrapped to the recovery key. None of it is secret.
 * Encoding: `"GWS" | ver | session_id(16) | u64 start_wall_ms | u8 n | u8 k | salt(8) | session_pub(65) |
 * u16 wrapped_len | wrapped_key`.
 */
class SessionHeader(
    sessionId: ByteArray,
    val startWallMs: Long,
    val totalShards: Int,
    val dataShards: Int,
    salt: ByteArray,
    sessionPublicKey: ByteArray,
    wrappedKey: ByteArray,
) {
    private val sessionIdBytes = sessionId.copyOf()
    private val saltBytes = salt.copyOf()
    private val publicKeyBytes = sessionPublicKey.copyOf()
    private val wrappedKeyBytes = wrappedKey.copyOf()

    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(sessionPublicKey.size == PUBLIC_KEY_BYTES) { "session public key must be $PUBLIC_KEY_BYTES bytes" }
        require(wrappedKey.size in 1..MAX_WRAPPED_BYTES) { "wrapped key size out of range" }
        require(totalShards in 1..MAX_SHARDS && dataShards in 1..totalShards) { "bad shard counts" }
    }

    val sessionId: ByteArray get() = sessionIdBytes.copyOf()
    val salt: ByteArray get() = saltBytes.copyOf()
    val sessionPublicKey: ByteArray get() = publicKeyBytes.copyOf()
    val wrappedKey: ByteArray get() = wrappedKeyBytes.copyOf()

    fun encode(): ByteArray = Bytes.concat(
        HEADER_MAGIC, Bytes.u8(HEADER_VERSION), sessionIdBytes, Bytes.u64be(startWallMs),
        Bytes.u8(totalShards), Bytes.u8(dataShards), saltBytes, publicKeyBytes,
        Bytes.u16be(wrappedKeyBytes.size), wrappedKeyBytes,
    )

    companion object {
        const val PUBLIC_KEY_BYTES = 65
        private const val MAX_WRAPPED_BYTES = 0xFFFF
        private const val MAX_SHARDS = 255

        fun decode(bytes: ByteArray): SessionHeader {
            try {
                val buffer = ByteBuffer.wrap(bytes)
                val magic = ByteArray(HEADER_MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(HEADER_MAGIC)) throw SessionFormatException("bad magic")
                val version = buffer.get().toInt() and 0xFF
                if (version != HEADER_VERSION) throw SessionFormatException("unsupported header version $version")
                val sessionId = ByteArray(SESSION_ID_BYTES).also { buffer.get(it) }
                val startWallMs = buffer.long
                val totalShards = buffer.get().toInt() and 0xFF
                val dataShards = buffer.get().toInt() and 0xFF
                val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
                val publicKey = ByteArray(PUBLIC_KEY_BYTES).also { buffer.get(it) }
                val wrappedLen = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() != wrappedLen) throw SessionFormatException("wrapped key length mismatch")
                val wrapped = ByteArray(wrappedLen).also { buffer.get(it) }
                return SessionHeader(sessionId, startWallMs, totalShards, dataShards, salt, publicKey, wrapped)
            } catch (e: BufferUnderflowException) {
                throw SessionFormatException("header truncated", e)
            } catch (e: IllegalArgumentException) {
                throw SessionFormatException(e.message ?: "invalid header", e)
            }
        }
    }
}
