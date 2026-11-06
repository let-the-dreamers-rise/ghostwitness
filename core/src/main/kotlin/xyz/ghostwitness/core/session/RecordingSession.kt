package xyz.ghostwitness.core.session

import xyz.ghostwitness.core.codec.EncodedSegment
import xyz.ghostwitness.core.codec.SegmentCodec
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.crypto.SegmentAad
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.manifest.SignedRoot
import xyz.ghostwitness.core.rs.ReedSolomon

/** Result of sealing one segment: the next session state, the shards to spread and the new signed root. */
class SegmentAppended(val session: RecordingSession, val segment: EncodedSegment, val root: SignedRoot)

/**
 * The recording phone's view of a session (spec section 4). Immutable: [append] returns a new session
 * with the entry added and the hash chain advanced. All sessions derived from one [start] share the same
 * key, and [close] wipes it for all of them.
 */
class RecordingSession private constructor(
    val header: SessionHeader,
    private val codec: SegmentCodec,
    private val signer: SessionSigner,
    val entries: List<ManifestEntry>,
    private val lastCiphertextHash: ByteArray,
) {
    val nextIndex: Int get() = entries.size

    fun append(tStartMs: Long, durMs: Int, plaintext: ByteArray): SegmentAppended {
        val segment = codec.encode(nextIndex, tStartMs, durMs, plaintext, lastCiphertextHash)
        val nextEntries = entries + segment.entry
        val root = SignedRoot.create(header.sessionId, nextEntries, signer)
        return SegmentAppended(RecordingSession(header, codec, signer, nextEntries, segment.ciphertextHash), segment, root)
    }

    /** Rebuilds one of this session's segments from shards while K is still in memory (self-check). */
    fun open(entry: ManifestEntry, shards: Map<Int, ByteArray>): ByteArray = codec.decode(entry, shards)

    fun close() = codec.wipe()

    companion object {
        fun start(
            secrets: SessionSecrets,
            signer: SessionSigner,
            recoveryPublicKey: ByteArray,
            startWallMs: Long,
            totalShards: Int = 5,
            dataShards: Int = 3,
        ): RecordingSession {
            val sessionId = secrets.sessionId
            val key = secrets.key
            val wrapped = try {
                KeyWrap.wrap(key, recoveryPublicKey, sessionId)
            } finally {
                key.fill(0)
            }
            val header = SessionHeader(sessionId, startWallMs, totalShards, dataShards, secrets.salt, signer.publicKey, wrapped)
            val codec = SegmentCodec(secrets.cipher(), sessionId, ReedSolomon(dataShards, totalShards - dataShards))
            return RecordingSession(header, codec, signer, emptyList(), SegmentAad.genesisPrevHash())
        }
    }
}
