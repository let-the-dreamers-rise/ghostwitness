package xyz.ghostwitness.core.merkle

import xyz.ghostwitness.core.crypto.Bytes

/**
 * RFC 9162 (Certificate Transparency v2) Merkle tree: `leaf = H(0x00 || d)`, `node = H(0x01 || l || r)`,
 * split at the largest power of two below n. Domain separation makes leaves and nodes impossible to
 * confuse, unlike a tree that promotes odd nodes unhashed.
 */
object Merkle {
    const val BLOCK_BYTES = 1024
    private val LEAF_PREFIX = byteArrayOf(0x00)
    private val NODE_PREFIX = byteArrayOf(0x01)

    fun leafHash(data: ByteArray): ByteArray = Bytes.sha256(LEAF_PREFIX, data)

    fun nodeHash(left: ByteArray, right: ByteArray): ByteArray = Bytes.sha256(NODE_PREFIX, left, right)

    fun root(leafHashes: List<ByteArray>): ByteArray = when (leafHashes.size) {
        0 -> Bytes.sha256()
        1 -> leafHashes.first().copyOf()
        else -> {
            val split = largestPowerOfTwoBelow(leafHashes.size)
            nodeHash(root(leafHashes.subList(0, split)), root(leafHashes.subList(split, leafHashes.size)))
        }
    }

    /** Inclusion proof (audit path) for leaf [index], ordered from the leaf upwards. */
    fun proof(index: Int, leafHashes: List<ByteArray>): List<ByteArray> {
        require(index in leafHashes.indices) { "leaf index out of range" }
        if (leafHashes.size == 1) return emptyList()
        val split = largestPowerOfTwoBelow(leafHashes.size)
        return if (index < split) {
            proof(index, leafHashes.subList(0, split)) + listOf(root(leafHashes.subList(split, leafHashes.size)))
        } else {
            proof(index - split, leafHashes.subList(split, leafHashes.size)) + listOf(root(leafHashes.subList(0, split)))
        }
    }

    /** RFC 9162 section 2.1.3.2 inclusion verification. */
    fun verify(leafHash: ByteArray, index: Int, treeSize: Int, proof: List<ByteArray>, root: ByteArray): Boolean {
        if (index < 0 || index >= treeSize) return false
        var fn = index.toLong()
        var sn = treeSize.toLong() - 1
        var r = leafHash
        for (p in proof) {
            if (sn == 0L) return false
            if ((fn and 1L) == 1L || fn == sn) {
                r = nodeHash(p, r)
                while ((fn and 1L) == 0L && fn != 0L) {
                    fn = fn shr 1
                    sn = sn shr 1
                }
            } else {
                r = nodeHash(r, p)
            }
            fn = fn shr 1
            sn = sn shr 1
        }
        return sn == 0L && r.contentEquals(root)
    }

    /** Leaf hashes of the fixed-size blocks of [data] (the last block may be shorter). */
    fun blockLeaves(data: ByteArray, blockBytes: Int = BLOCK_BYTES): List<ByteArray> {
        require(blockBytes > 0) { "block size must be positive" }
        if (data.isEmpty()) return listOf(leafHash(data))
        return (data.indices step blockBytes).map { from ->
            leafHash(data.copyOfRange(from, minOf(from + blockBytes, data.size)))
        }
    }

    /** The shard commitment from spec section 4: Merkle root over 1 KiB blocks. */
    fun blockRoot(data: ByteArray, blockBytes: Int = BLOCK_BYTES): ByteArray = root(blockLeaves(data, blockBytes))

    private fun largestPowerOfTwoBelow(n: Int): Int {
        var k = 1
        while (k shl 1 < n) k = k shl 1
        return k
    }
}
