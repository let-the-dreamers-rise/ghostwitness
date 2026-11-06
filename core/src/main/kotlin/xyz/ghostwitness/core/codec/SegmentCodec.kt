package xyz.ghostwitness.core.codec

import xyz.ghostwitness.core.crypto.ChunkCipher
import xyz.ghostwitness.core.crypto.SegmentAad
import xyz.ghostwitness.core.manifest.ChainVerifier
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.merkle.Merkle
import xyz.ghostwitness.core.rs.InsufficientShardsException
import xyz.ghostwitness.core.rs.ReedSolomon
import xyz.ghostwitness.core.rs.ShardCodec

/** Everything produced for one segment: the ciphertext, its n shards and its manifest entry. */
class EncodedSegment(ciphertext: ByteArray, shards: List<ByteArray>, val entry: ManifestEntry) {
    private val ciphertextBytes = ciphertext.copyOf()
    private val shardBytes = shards.map { it.copyOf() }

    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()
    val shards: List<ByteArray> get() = shardBytes.map { it.copyOf() }
    val ciphertextHash: ByteArray get() = ChainVerifier.ciphertextHash(ciphertextBytes)
}

/**
 * The segment pipeline from spec section 5.1: encrypt with the chained AAD, split with RS(5,3), commit
 * each shard with a block Merkle root. Decoding verifies every shard against its root before using it.
 */
class SegmentCodec(
    private val cipher: ChunkCipher,
    sessionId: ByteArray,
    private val rs: ReedSolomon = ReedSolomon(DEFAULT_DATA_SHARDS, DEFAULT_PARITY_SHARDS),
) {
    private val sessionId: ByteArray = sessionId.copyOf()

    fun encode(index: Int, tStartMs: Long, durMs: Int, plaintext: ByteArray, prevCtHash: ByteArray): EncodedSegment {
        val ciphertext = cipher.encrypt(plaintext, SegmentAad(sessionId, index, tStartMs, prevCtHash))
        val shards = ShardCodec.split(ciphertext, rs)
        val entry = ManifestEntry(index, tStartMs, durMs, ciphertext.size, prevCtHash, shards.map { Merkle.blockRoot(it) })
        return EncodedSegment(ciphertext, shards, entry)
    }

    /** Rebuilds and decrypts a segment from whatever shards arrived; shards that fail their root are dropped. */
    fun decode(entry: ManifestEntry, available: Map<Int, ByteArray>): ByteArray {
        val verified = available.filter { (shardIndex, shard) -> entry.shardRootMatches(shardIndex, shard) }
        if (verified.size < rs.dataShards) {
            throw InsufficientShardsException("segment ${entry.index}: ${verified.size} valid shards, need ${rs.dataShards}")
        }
        val ciphertext = ShardCodec.join(verified, entry.ctLen, rs)
        return cipher.decrypt(ciphertext, SegmentAad(sessionId, entry.index, entry.tStartMs, entry.prevCtHash))
    }

    /** Wipes the session key held by the cipher. */
    fun wipe() = cipher.wipe()

    private companion object {
        const val DEFAULT_DATA_SHARDS = 3
        const val DEFAULT_PARITY_SHARDS = 2
    }
}
