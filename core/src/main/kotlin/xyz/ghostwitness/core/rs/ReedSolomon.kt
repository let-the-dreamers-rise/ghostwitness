package xyz.ghostwitness.core.rs

class InsufficientShardsException(message: String) : Exception(message)

/**
 * Systematic Reed-Solomon over GF(256): shards 0 until [dataShards] are the data itself, the rest are
 * parity. Any [dataShards] distinct shards rebuild everything. Built from a Vandermonde matrix
 * normalised so its top square is the identity (the same construction as Backblaze JavaReedSolomon).
 */
class ReedSolomon(val dataShards: Int, val parityShards: Int) {
    val totalShards: Int = dataShards + parityShards
    private val matrix: GfMatrix

    init {
        require(dataShards > 0) { "need at least one data shard" }
        require(parityShards >= 0) { "parity shards must be non-negative" }
        require(totalShards <= MAX_SHARDS) { "at most $MAX_SHARDS shards in GF(256)" }
        val vandermonde = GfMatrix.vandermonde(totalShards, dataShards)
        matrix = vandermonde.times(vandermonde.subRows((0 until dataShards).toList()).inverse())
    }

    /** Returns all [totalShards] shards: copies of the data shards followed by parity. */
    fun encode(data: List<ByteArray>): List<ByteArray> {
        require(data.size == dataShards) { "expected $dataShards data shards, got ${data.size}" }
        val size = data.first().size
        require(data.all { it.size == size }) { "data shards must be the same size" }
        val parity = (dataShards until totalShards).map { rowIndex -> combine(matrix.row(rowIndex), data, size) }
        return data.map { it.copyOf() } + parity
    }

    /** Rebuilds all shards from any [dataShards] of them, keyed by shard index. */
    fun reconstruct(available: Map<Int, ByteArray>): List<ByteArray> {
        val usable = available.filterKeys { it in 0 until totalShards }
        if (usable.size < dataShards) {
            throw InsufficientShardsException("need $dataShards shards, have ${usable.size}")
        }
        val chosen = usable.keys.sorted().take(dataShards)
        val size = usable.getValue(chosen.first()).size
        require(chosen.all { usable.getValue(it).size == size }) { "shards must be the same size" }
        val decode = matrix.subRows(chosen).inverse()
        val inputs = chosen.map { usable.getValue(it) }
        val data = (0 until dataShards).map { combine(decode.row(it), inputs, size) }
        return encode(data)
    }

    private fun combine(coefficients: IntArray, inputs: List<ByteArray>, size: Int): ByteArray {
        val out = ByteArray(size)
        coefficients.forEachIndexed { i, coefficient ->
            if (coefficient != 0) {
                val table = Galois.MUL[coefficient]
                val input = inputs[i]
                for (b in 0 until size) {
                    out[b] = (out[b].toInt() xor table[input[b].toInt() and BYTE_MASK].toInt()).toByte()
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_SHARDS = 256
        const val BYTE_MASK = 0xFF
    }
}
