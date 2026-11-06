package xyz.ghostwitness.core.manifest

import java.nio.ByteBuffer
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SESSION_ID_BYTES
import xyz.ghostwitness.core.crypto.SIGNATURE_BYTES
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.merkle.Merkle

private val ROOT_DOMAIN = "GWROOT".toByteArray()
private val ROOT_MAGIC = "GWR".toByteArray()
private const val ROOT_VERSION = 1

/**
 * The running root (spec section 4): after each segment the recording phone signs the Merkle root over
 * entries 0..[uptoIndex]. Holders keep the newest; any device can publish the highest one to Arkiv.
 */
class SignedRoot(sessionId: ByteArray, val uptoIndex: Int, root: ByteArray, signature: ByteArray) {
    private val sessionIdBytes = sessionId.copyOf()
    private val rootBytes = root.copyOf()
    private val signatureBytes = signature.copyOf()

    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
        require(uptoIndex >= 0) { "upto index must be non-negative" }
        require(root.size == Bytes.HASH_BYTES) { "root must be ${Bytes.HASH_BYTES} bytes" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be $SIGNATURE_BYTES bytes" }
    }

    val sessionId: ByteArray get() = sessionIdBytes.copyOf()
    val root: ByteArray get() = rootBytes.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()

    /** `"GWR" | ver | session_id(16) | u32 upto | root(32) | signature(64)`, 120 bytes. */
    fun encode(): ByteArray = Bytes.concat(
        ROOT_MAGIC, Bytes.u8(ROOT_VERSION), sessionIdBytes, Bytes.u32be(uptoIndex), rootBytes, signatureBytes,
    )

    fun verify(sessionPublicKey: ByteArray): Boolean =
        SessionSigner.verify(sessionPublicKey, signingMessage(sessionIdBytes, uptoIndex, rootBytes), signatureBytes)

    /** True when [entries] (indices 0..uptoIndex, in order) hash to this root. */
    fun covers(entries: List<ManifestEntry>): Boolean =
        entries.size == uptoIndex + 1 &&
            entries.withIndex().all { (position, entry) -> entry.index == position } &&
            Merkle.root(entries.map { it.leafHash() }).contentEquals(rootBytes)

    companion object {
        const val ENCODED_BYTES = 3 + 1 + SESSION_ID_BYTES + 4 + Bytes.HASH_BYTES + SIGNATURE_BYTES

        fun decode(bytes: ByteArray): SignedRoot {
            if (bytes.size != ENCODED_BYTES) throw ManifestFormatException("signed root must be $ENCODED_BYTES bytes")
            if (!bytes.copyOfRange(0, ROOT_MAGIC.size).contentEquals(ROOT_MAGIC)) throw ManifestFormatException("bad magic")
            val buffer = ByteBuffer.wrap(bytes, ROOT_MAGIC.size, bytes.size - ROOT_MAGIC.size)
            val version = buffer.get().toInt() and 0xFF
            if (version != ROOT_VERSION) throw ManifestFormatException("unsupported signed root version $version")
            val sessionId = ByteArray(SESSION_ID_BYTES).also { buffer.get(it) }
            val upto = buffer.int
            if (upto < 0) throw ManifestFormatException("negative upto index")
            val root = ByteArray(Bytes.HASH_BYTES).also { buffer.get(it) }
            val signature = ByteArray(SIGNATURE_BYTES).also { buffer.get(it) }
            return SignedRoot(sessionId, upto, root, signature)
        }

        fun signingMessage(sessionId: ByteArray, uptoIndex: Int, root: ByteArray): ByteArray =
            Bytes.concat(ROOT_DOMAIN, sessionId, Bytes.u32be(uptoIndex), root)

        fun create(sessionId: ByteArray, entries: List<ManifestEntry>, signer: SessionSigner): SignedRoot {
            require(entries.isNotEmpty()) { "need at least one entry" }
            require(entries.withIndex().all { (position, entry) -> entry.index == position }) {
                "entries must be contiguous from index 0"
            }
            val root = Merkle.root(entries.map { it.leafHash() })
            val upto = entries.size - 1
            return SignedRoot(sessionId, upto, root, signer.sign(signingMessage(sessionId, upto, root)))
        }
    }
}
