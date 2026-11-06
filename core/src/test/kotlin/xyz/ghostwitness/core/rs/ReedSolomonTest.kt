package xyz.ghostwitness.core.rs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReedSolomonTest {
    private val rs = ReedSolomon(3, 2)

    private fun subsets(items: List<Int>, k: Int): List<List<Int>> = when {
        k == 0 -> listOf(emptyList())
        items.size < k -> emptyList()
        else -> subsets(items.drop(1), k - 1).map { listOf(items.first()) + it } + subsets(items.drop(1), k)
    }

    @Test
    fun `data shards are the data itself`() {
        val data = List(3) { Random(it).nextBytes(64) }
        val shards = rs.encode(data)
        assertEquals(5, shards.size)
        data.forEachIndexed { i, d -> assertContentEquals(d, shards[i]) }
    }

    @Test
    fun `every 3 of 5 subset rebuilds the ciphertext`() {
        val allSubsets = subsets((0 until 5).toList(), 3)
        assertEquals(10, allSubsets.size)
        for (size in listOf(0, 1, 2, 3, 100, 1024, 4097, 375_016)) {
            val ciphertext = Random(size).nextBytes(size)
            val shards = ShardCodec.split(ciphertext, rs)
            allSubsets.forEach { subset ->
                val available = subset.associateWith { shards[it] }
                assertContentEquals(ciphertext, ShardCodec.join(available, ciphertext.size, rs), "size=$size subset=$subset")
            }
        }
    }

    @Test
    fun `reconstruct restores the parity shards too`() {
        val shards = rs.encode(List(3) { Random(10 + it).nextBytes(33) })
        val rebuilt = rs.reconstruct(mapOf(2 to shards[2], 3 to shards[3], 4 to shards[4]))
        shards.indices.forEach { assertContentEquals(shards[it], rebuilt[it]) }
        val fromFour = rs.reconstruct(mapOf(0 to shards[0], 1 to shards[1], 3 to shards[3], 4 to shards[4]))
        shards.indices.forEach { assertContentEquals(shards[it], fromFour[it]) }
    }

    @Test
    fun `two shards are not enough and unknown indices do not count`() {
        val shards = rs.encode(List(3) { ByteArray(8) { it.toByte() } })
        assertFailsWith<InsufficientShardsException> { rs.reconstruct(mapOf(0 to shards[0], 4 to shards[4])) }
        assertFailsWith<InsufficientShardsException> {
            rs.reconstruct(mapOf(0 to shards[0], 4 to shards[4], 7 to shards[1]))
        }
    }

    @Test
    fun `invalid shapes are rejected`() {
        assertFailsWith<IllegalArgumentException> { ReedSolomon(0, 2) }
        assertFailsWith<IllegalArgumentException> { ReedSolomon(3, -1) }
        assertFailsWith<IllegalArgumentException> { ReedSolomon(200, 100) }
        assertFailsWith<IllegalArgumentException> { rs.encode(List(2) { ByteArray(4) }) }
        assertFailsWith<IllegalArgumentException> { rs.encode(listOf(ByteArray(4), ByteArray(4), ByteArray(5))) }
        assertFailsWith<IllegalArgumentException> {
            rs.reconstruct(mapOf(0 to ByteArray(4), 1 to ByteArray(4), 2 to ByteArray(5)))
        }
    }

    @Test
    fun `shard size rounds up and is never zero`() {
        assertEquals(4, ShardCodec.shardSize(10, 3))
        assertEquals(3, ShardCodec.shardSize(9, 3))
        assertEquals(1, ShardCodec.shardSize(0, 3))
    }

    @Test
    fun `GF256 inverse is a true inverse`() {
        (1..255).forEach { assertEquals(1, Galois.mul(it, Galois.inverse(it)), "a=$it") }
        assertEquals(0, Galois.mul(0, 7))
        assertEquals(1, Galois.pow(0, 0))
        assertEquals(0, Galois.pow(0, 3))
        assertFailsWith<IllegalArgumentException> { Galois.inverse(0) }
    }

    @Test
    fun `singular and mismatched matrices are rejected`() {
        val vandermonde = GfMatrix.vandermonde(3, 2)
        assertFailsWith<IllegalArgumentException> { vandermonde.subRows(listOf(1, 1)).inverse() }
        assertFailsWith<IllegalArgumentException> { vandermonde.inverse() }
        assertFailsWith<IllegalArgumentException> { vandermonde.times(vandermonde) }
        assertEquals(1, vandermonde[0, 0])
    }
}
