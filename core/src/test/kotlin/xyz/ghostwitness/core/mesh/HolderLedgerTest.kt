package xyz.ghostwitness.core.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.manifest.SignedRoot

class HolderLedgerTest {
    private val recorded = RecordedSession(3)
    private val ledger = HolderLedger()

    private fun control(state: HolderState, bytes: ByteArray) = ledger.onControl(state, ControlCodec.decode(bytes))

    private fun joined(): HolderState = control(HolderState(), recorded.header.encode()).state

    private fun withEntry(state: HolderState, i: Int): HolderState =
        control(state, EntryFrame(recorded.header.sessionId, recorded.segments[i].segment.entry).encode()).state

    @Test
    fun `session header is stored once and a new session resets the ledger`() {
        val first = control(HolderState(), recorded.header.encode())
        assertIs<HolderAction.StoreSession>(first.actions.single())
        assertEquals(emptyList(), control(first.state, recorded.header.encode()).actions)
        val other = RecordedSession(1)
        val switched = control(withEntry(first.state, 0), other.header.encode())
        assertIs<HolderAction.StoreSession>(switched.actions.single())
        assertEquals(emptyMap(), switched.state.entries)
    }

    @Test
    fun `only newer roots signed by the session key are kept`() {
        val newest = control(joined(), recorded.segments[2].root.encode())
        assertIs<HolderAction.StoreRoot>(newest.actions.single())
        assertEquals(emptyList(), control(newest.state, recorded.segments[1].root.encode()).actions)
        val good = recorded.segments[2].root
        val forged = SignedRoot(good.sessionId, 5, good.root, good.signature)
        assertIs<HolderAction.Rejected>(control(joined(), forged.encode()).actions.single())
        assertIs<HolderAction.Rejected>(control(HolderState(), good.encode()).actions.single())
        assertIs<HolderAction.Rejected>(control(joined(), RecordedSession(1).segments[0].root.encode()).actions.single())
    }

    @Test
    fun `a shard is stored when header and body meet, in either order, and acked after the interval`() {
        val state = withEntry(joined(), 1)
        val shard = recorded.segments[1].segment.shards[4]
        val headerFirst = control(state, recorded.shardHeader(1, 4, 77L).encode())
        assertEquals(emptyList(), headerFirst.actions)
        val stored = ledger.onBody(headerFirst.state, 77L, shard)
        val action = assertIs<HolderAction.StoreShard>(stored.actions.single())
        assertContentEquals(shard, action.shard)
        val bodyFirst = ledger.onBody(state, 78L, shard)
        assertEquals(emptyList(), bodyFirst.actions)
        assertIs<HolderAction.StoreShard>(control(bodyFirst.state, recorded.shardHeader(1, 4, 78L).encode()).actions.single())

        val (early, none) = ledger.onTick(stored.state.copy(lastFlushMs = 1_000), 1_100)
        assertNull(none)
        val (flushed, frame) = ledger.onTick(early, 1_300)
        val ack = frame!!.entries.single()
        assertEquals(1 to 4, ack.segmentIndex to ack.shardIndex)
        assertContentEquals(Bytes.sha256(shard), ack.shardHash)
        assertEquals(emptyList(), flushed.pendingAcks)
        assertTrue((1 to 4) in flushed.held)
    }

    @Test
    fun `corrupted, resized or mislabelled shards are rejected and never acked`() {
        val state = withEntry(joined(), 0)
        val shards = recorded.segments[0].segment.shards
        val corrupted = shards[0].copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val withHeader = control(state, recorded.shardHeader(0, 0, 1L).encode()).state
        val bad = ledger.onBody(withHeader, 1L, corrupted)
        assertIs<HolderAction.Rejected>(bad.actions.single())
        assertEquals(emptyList(), bad.state.pendingAcks)
        val resized = ledger.onBody(control(state, recorded.shardHeader(0, 1, 2L).encode()).state, 2L, shards[1] + byteArrayOf(0))
        assertIs<HolderAction.Rejected>(resized.actions.single())
        val swappedHeader = recorded.shardHeader(0, 2, 3L)
        val mislabelled = xyz.ghostwitness.core.wire.ShardHeader(
            recorded.header.sessionId, 0, 3, 5, 3, swappedHeader.ctLen, swappedHeader.tStartMs, swappedHeader.durMs,
            swappedHeader.shardLen, swappedHeader.shardRoot, 3L,
        )
        val wrongSlot = ledger.onBody(ledger.onControl(state, ControlFrame.Shard(mislabelled)).state, 3L, shards[2])
        assertIs<HolderAction.Rejected>(wrongSlot.actions.single())
    }

    @Test
    fun `frames before the session header or for another session are rejected`() {
        val other = RecordedSession(1)
        assertIs<HolderAction.Rejected>(control(HolderState(), recorded.shardHeader(0, 0, 1L).encode()).actions.single())
        assertIs<HolderAction.Rejected>(control(joined(), other.shardHeader(0, 0, 1L).encode()).actions.single())
        val entry = EntryFrame(other.header.sessionId, other.segments[0].segment.entry).encode()
        assertIs<HolderAction.Rejected>(control(joined(), entry).actions.single())
        assertIs<HolderAction.Rejected>(control(HolderState(), entry).actions.single())
        assertEquals(emptyList(), control(withEntry(joined(), 0), EntryFrame(recorded.header.sessionId, recorded.segments[0].segment.entry).encode()).actions)
        assertEquals(emptyList(), control(joined(), recorded.ackFrame(0 to 0).encode()).actions)
        assertNull(ledger.onTick(HolderState(), 10_000).second)
    }
}
