package xyz.ghostwitness.core.manifest

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.merkle.Merkle

class ManifestEntryTest {
    private fun entry(roots: List<ByteArray> = List(5) { r -> ByteArray(Bytes.HASH_BYTES) { r.toByte() } }) =
        ManifestEntry(2, 4_000L, 2_000, 375_016, ByteArray(Bytes.HASH_BYTES) { 3 }, roots)

    @Test
    fun `encoding is fixed width and round trips`() {
        val original = entry()
        val bytes = original.encode()
        assertEquals(4 + 8 + 2 + 4 + 32 + 1 + 5 * 32, bytes.size)
        val decoded = ManifestEntry.decode(bytes)
        assertContentEquals(bytes, decoded.encode())
        assertEquals(2, decoded.index)
        assertEquals(4_000L, decoded.tStartMs)
        assertEquals(2_000, decoded.durMs)
        assertEquals(375_016, decoded.ctLen)
        assertEquals(5, decoded.shardCount)
        assertContentEquals(original.prevCtHash, decoded.prevCtHash)
    }

    @Test
    fun `decode rejects malformed input`() {
        val bytes = entry().encode()
        assertFailsWith<ManifestFormatException> { ManifestEntry.decode(bytes.copyOf(20)) }
        assertFailsWith<ManifestFormatException> { ManifestEntry.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<ManifestFormatException> { ManifestEntry.decode(bytes + byteArrayOf(0)) }
        val negativeIndex = bytes.copyOf().also { it[0] = 0x80.toByte() }
        assertFailsWith<ManifestFormatException> { ManifestEntry.decode(negativeIndex) }
    }

    @Test
    fun `leaf hash is the RFC 6962 leaf of the encoding`() {
        val e = entry()
        assertContentEquals(Merkle.leafHash(e.encode()), e.leafHash())
    }

    @Test
    fun `shard root check uses the block Merkle root`() {
        val shard = ByteArray(3000) { it.toByte() }
        val e = entry(listOf(Merkle.blockRoot(shard), ByteArray(Bytes.HASH_BYTES)))
        assertTrue(e.shardRootMatches(0, shard))
        assertFalse(e.shardRootMatches(1, shard))
        assertFalse(e.shardRootMatches(2, shard))
    }

    @Test
    fun `constructor validates and getters return copies`() {
        val prev = ByteArray(Bytes.HASH_BYTES)
        val roots = listOf(ByteArray(Bytes.HASH_BYTES))
        assertFailsWith<IllegalArgumentException> { ManifestEntry(-1, 0, 0, 0, prev, roots) }
        assertFailsWith<IllegalArgumentException> { ManifestEntry(0, 0, 0, 0, ByteArray(31), roots) }
        assertFailsWith<IllegalArgumentException> { ManifestEntry(0, 0, 0, 0, prev, emptyList()) }
        val e = entry()
        e.shardRoots[0].fill(0x7F)
        assertFalse(e.shardRoots[0].all { it == 0x7F.toByte() })
    }
}
