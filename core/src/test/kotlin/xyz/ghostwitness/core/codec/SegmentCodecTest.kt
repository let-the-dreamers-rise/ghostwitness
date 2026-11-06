package xyz.ghostwitness.core.codec

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.GCM_TAG_BYTES
import xyz.ghostwitness.core.crypto.SegmentAad
import xyz.ghostwitness.core.crypto.SegmentAuthException
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.rs.InsufficientShardsException

class SegmentCodecTest {
    private val secrets = SessionSecrets.generate()
    private val codec = SegmentCodec(secrets.cipher(), secrets.sessionId)
    private val plaintext = Random(7).nextBytes(20_000)
    private val segment = codec.encode(0, 0L, 2_000, plaintext, SegmentAad.genesisPrevHash())

    private fun pick(vararg indices: Int): Map<Int, ByteArray> {
        val shards = segment.shards
        return indices.associateWith { shards[it] }
    }

    @Test
    fun `any three shards decode the segment`() {
        assertEquals(5, segment.shards.size)
        assertEquals(plaintext.size + GCM_TAG_BYTES, segment.entry.ctLen)
        listOf(intArrayOf(0, 1, 2), intArrayOf(2, 3, 4), intArrayOf(0, 3, 4), intArrayOf(1, 2, 4)).forEach { subset ->
            assertContentEquals(plaintext, codec.decode(segment.entry, pick(*subset)))
        }
    }

    @Test
    fun `shards that fail their root are dropped before decoding`() {
        val shards = segment.shards
        val corrupted = shards[0].copyOf().also { it[100] = (it[100].toInt() xor 0x55).toByte() }
        val withCorruption = pick(1, 2, 3, 4) + (0 to corrupted)
        assertContentEquals(plaintext, codec.decode(segment.entry, withCorruption))
        assertFailsWith<InsufficientShardsException> { codec.decode(segment.entry, pick(1, 2) + (0 to corrupted)) }
        val mislabelled = mapOf(0 to shards[0], 1 to shards[1], 4 to shards[3])
        assertFailsWith<InsufficientShardsException> { codec.decode(segment.entry, mislabelled) }
    }

    @Test
    fun `a tampered manifest entry fails authentication`() {
        val e = segment.entry
        val tampered = ManifestEntry(e.index, e.tStartMs + 1, e.durMs, e.ctLen, e.prevCtHash, e.shardRoots)
        assertFailsWith<SegmentAuthException> { codec.decode(tampered, pick(0, 1, 2)) }
    }

    @Test
    fun `entry and hash describe the ciphertext`() {
        assertContentEquals(Bytes.sha256(segment.ciphertext), segment.ciphertextHash)
        segment.shards.forEachIndexed { j, shard -> assertTrue(segment.entry.shardRootMatches(j, shard)) }
        segment.ciphertext.fill(0)
        assertContentEquals(Bytes.sha256(segment.ciphertext), segment.ciphertextHash)
    }
}
