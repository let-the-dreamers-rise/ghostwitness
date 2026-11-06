package xyz.ghostwitness.core.manifest

import java.nio.ByteBuffer
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.merkle.Merkle

class ManifestFormatException(message: String) : Exception(message)

/**
 * One manifest entry per segment (spec section 4). Fixed-width big-endian encoding, so Kotlin and any
 * other verifier hash identical bytes:
 * `u32 i | u64 t_start_ms | u16 dur_ms | u32 ct_len | prev_ct_hash(32) | u8 n | shard_root(32) x n`.
 */
class ManifestEntry(
    val index: Int,
    val tStartMs: Long,
    val durMs: Int,
    val ctLen: Int,
    prevCtHash: ByteArray,
    shardRoots: List<ByteArray>,
) {
    private val prevHash: ByteArray = prevCtHash.copyOf()
    private val roots: List<ByteArray> = shardRoots.map { it.copyOf() }

    init {
        require(index >= 0 && ctLen >= 0) { "index and ct_len must be non-negative" }
        require(prevCtHash.size == Bytes.HASH_BYTES) { "prev ct hash must be ${Bytes.HASH_BYTES} bytes" }
        require(shardRoots.isNotEmpty() && shardRoots.all { it.size == Bytes.HASH_BYTES }) { "bad shard roots" }
    }

    val prevCtHash: ByteArray get() = prevHash.copyOf()
    val shardRoots: List<ByteArray> get() = roots.map { it.copyOf() }
    val shardCount: Int get() = roots.size

    fun shardRootMatches(shardIndex: Int, shard: ByteArray): Boolean =
        shardIndex in roots.indices && Merkle.blockRoot(shard).contentEquals(roots[shardIndex])

    fun encode(): ByteArray = Bytes.concat(
        Bytes.u32be(index),
        Bytes.u64be(tStartMs),
        Bytes.u16be(durMs),
        Bytes.u32be(ctLen),
        prevHash,
        Bytes.u8(roots.size),
        *roots.toTypedArray(),
    )

    fun leafHash(): ByteArray = Merkle.leafHash(encode())

    companion object {
        private const val FIXED_BYTES = 4 + 8 + 2 + 4 + Bytes.HASH_BYTES + 1

        fun decode(bytes: ByteArray): ManifestEntry {
            if (bytes.size < FIXED_BYTES) throw ManifestFormatException("entry too short")
            val buffer = ByteBuffer.wrap(bytes)
            val index = buffer.int
            val tStart = buffer.long
            val dur = buffer.short.toInt() and 0xFFFF
            val ctLen = buffer.int
            val prev = ByteArray(Bytes.HASH_BYTES).also { buffer.get(it) }
            val count = buffer.get().toInt() and 0xFF
            if (buffer.remaining() != count * Bytes.HASH_BYTES) throw ManifestFormatException("bad shard root count")
            val roots = List(count) { ByteArray(Bytes.HASH_BYTES).also { buffer.get(it) } }
            if (index < 0 || ctLen < 0) throw ManifestFormatException("negative field")
            return ManifestEntry(index, tStart, dur, ctLen, prev, roots)
        }
    }
}
