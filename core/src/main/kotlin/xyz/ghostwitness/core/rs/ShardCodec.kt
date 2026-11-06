package xyz.ghostwitness.core.rs

/** Splits a ciphertext into equal, zero-padded data shards and encodes them; joins them back. */
object ShardCodec {
    fun shardSize(ctLen: Int, dataShards: Int): Int = maxOf(1, (ctLen + dataShards - 1) / dataShards)

    fun split(ciphertext: ByteArray, rs: ReedSolomon): List<ByteArray> {
        val size = shardSize(ciphertext.size, rs.dataShards)
        val data = (0 until rs.dataShards).map { i ->
            val from = minOf(i * size, ciphertext.size)
            val to = minOf(from + size, ciphertext.size)
            ciphertext.copyOfRange(from, to).copyOf(size)
        }
        return rs.encode(data)
    }

    fun join(available: Map<Int, ByteArray>, ctLen: Int, rs: ReedSolomon): ByteArray {
        val shards = rs.reconstruct(available)
        val joined = ByteArray(shardSize(ctLen, rs.dataShards) * rs.dataShards)
        shards.take(rs.dataShards).forEachIndexed { i, shard -> shard.copyInto(joined, i * shard.size) }
        return joined.copyOf(ctLen)
    }
}
