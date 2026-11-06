package xyz.ghostwitness.core.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FramesTest {
    private val sessionId = ByteArray(16) { (it + 1).toByte() }

    private fun header() = ShardHeader(
        sessionId = sessionId,
        segmentIndex = 42,
        shardIndex = 3,
        totalShards = 5,
        dataShards = 3,
        ctLen = 375_016,
        tStartMs = 84_000L,
        durMs = 2_000,
        shardLen = 125_006,
        shardRoot = ByteArray(32) { 9 },
        payloadId = 0x1234_5678_9ABCL,
    )

    @Test
    fun `shard header round trips at a fixed size`() {
        val bytes = header().encode()
        assertEquals(85, ShardHeader.ENCODED_BYTES)
        assertEquals(ShardHeader.ENCODED_BYTES, bytes.size)
        val decoded = ShardHeader.decode(bytes)
        assertContentEquals(sessionId, decoded.sessionId)
        assertEquals(42, decoded.segmentIndex)
        assertEquals(3, decoded.shardIndex)
        assertEquals(5, decoded.totalShards)
        assertEquals(3, decoded.dataShards)
        assertEquals(375_016, decoded.ctLen)
        assertEquals(84_000L, decoded.tStartMs)
        assertEquals(2_000, decoded.durMs)
        assertEquals(125_006, decoded.shardLen)
        assertContentEquals(ByteArray(32) { 9 }, decoded.shardRoot)
        assertEquals(0x1234_5678_9ABCL, decoded.payloadId)
        assertContentEquals(bytes, decoded.encode())
    }

    @Test
    fun `bad shard headers are rejected`() {
        val bytes = header().encode()
        val badMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        val badVersion = bytes.copyOf().also { it[3] = 2 }
        val badShardIndex = bytes.copyOf().also { it[24] = 5 }
        listOf(badMagic, badVersion, badShardIndex, bytes.copyOf(84), ByteArray(2)).forEach { frame ->
            assertFailsWith<FrameFormatException> { ShardHeader.decode(frame) }
        }
    }

    @Test
    fun `ack frame round trips`() {
        val entries = listOf(
            AckEntry(1, 0, ByteArray(32) { 1 }),
            AckEntry(1, 1, ByteArray(32) { 2 }),
            AckEntry(2, 4, ByteArray(32) { 3 }),
        )
        val bytes = AckFrame(sessionId, entries).encode()
        assertEquals(3 + 1 + 16 + 2 + 3 * 37, bytes.size)
        val decoded = AckFrame.decode(bytes)
        assertContentEquals(sessionId, decoded.sessionId)
        assertEquals(listOf(1 to 0, 1 to 1, 2 to 4), decoded.entries.map { it.segmentIndex to it.shardIndex })
        assertContentEquals(ByteArray(32) { 3 }, decoded.entries[2].shardHash)
        assertEquals(0, AckFrame.decode(AckFrame(sessionId, emptyList()).encode()).entries.size)
    }

    @Test
    fun `bad ack frames are rejected`() {
        val bytes = AckFrame(sessionId, listOf(AckEntry(1, 0, ByteArray(32)))).encode()
        assertFailsWith<FrameFormatException> { AckFrame.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<FrameFormatException> { AckFrame.decode(header().encode()) }
        assertFailsWith<FrameFormatException> { AckFrame.decode(ByteArray(2)) }
    }

    @Test
    fun `constructors validate their fields`() {
        assertFailsWith<IllegalArgumentException> { AckEntry(0, 0, ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { AckFrame(ByteArray(15), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            ShardHeader(sessionId, 0, 0, 5, 3, 0, 0, 0, 0, ByteArray(31), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ShardHeader(sessionId, 0, 0, 5, 6, 0, 0, 0, 0, ByteArray(32), 0)
        }
    }
}
