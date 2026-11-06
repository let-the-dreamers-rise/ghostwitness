package xyz.ghostwitness.core.sched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchedulerTest {
    private val scheduler = Scheduler()
    private val progress = Progress()

    private fun peers(vararg rates: Pair<String, Double>, now: Long = 0L, on: Scheduler = scheduler): SchedulerState =
        rates.fold(SchedulerState()) { acc, (id, rate) -> on.upsertPeer(acc, id, rate, now) }

    private fun ackAll(state: SchedulerState, sends: List<Send>): SchedulerState =
        sends.fold(state) { acc, s -> scheduler.onAck(acc, s.peerId, s.segmentIndex, s.shardIndex) }

    private val fivePeers = arrayOf("slow" to 10.0, "a" to 300.0, "b" to 200.0, "c" to 100.0, "d" to 50.0)

    @Test
    fun `data shards go to the three fastest peers first`() {
        val plan = scheduler.plan(scheduler.addSegment(peers(*fivePeers), 0), 0)
        assertEquals(listOf(Send("a", 0, 0), Send("b", 0, 1), Send("c", 0, 2)), plan.sends.take(3))
        assertEquals(5, plan.sends.size)
        assertEquals(5, plan.sends.map { it.peerId }.toSet().size)
    }

    @Test
    fun `data shards of every segment are placed before any parity`() {
        val base = peers("a" to 5.0, "b" to 4.0, "c" to 3.0, "d" to 2.0, "e" to 1.0)
        val plan = scheduler.plan(scheduler.addSegment(scheduler.addSegment(base, 0), 1), 0)
        assertEquals(10, plan.sends.size)
        assertTrue(plan.sends.take(6).all { it.shardIndex < 3 })
        assertTrue(plan.sends.drop(6).all { it.shardIndex >= 3 })
    }

    @Test
    fun `oldest segment is served first when capacity is short`() {
        val tight = Scheduler(SchedulerConfig(maxInFlightPerPeer = 1))
        val base = peers("a" to 3.0, "b" to 2.0, "c" to 1.0, on = tight)
        val plan = tight.plan(tight.addSegment(tight.addSegment(base, 5), 4), 0)
        assertEquals(3, plan.sends.size)
        assertTrue(plan.sends.all { it.segmentIndex == 4 })
    }

    @Test
    fun `three acks make a segment recoverable and five make it fully spread`() {
        val plan = scheduler.plan(scheduler.addSegment(peers(*fivePeers), 0), 0)
        val afterTwo = ackAll(plan.state, plan.sends.take(2))
        assertEquals(SegmentStatus.SENDING, progress.status(afterTwo.segment(0)!!))
        assertFalse(progress.okToLosePhone(afterTwo))
        val afterThree = ackAll(plan.state, plan.sends.take(3))
        assertEquals(SegmentStatus.RECOVERABLE, progress.status(afterThree.segment(0)!!))
        assertEquals(0, progress.spare(afterThree.segment(0)!!))
        assertTrue(progress.okToLosePhone(afterThree))
        val afterFive = ackAll(plan.state, plan.sends)
        assertEquals(SegmentStatus.FULLY_SPREAD, progress.status(afterFive.segment(0)!!))
        assertEquals(2, progress.spare(afterFive.segment(0)!!))
        assertTrue(afterFive.peers.values.all { it.inFlight == 0 })
    }

    @Test
    fun `a stale peer's shard is reassigned to a live peer`() {
        val first = scheduler.plan(scheduler.addSegment(peers("a" to 9.0, "b" to 8.0, "c" to 7.0), 0), 0)
        assertEquals(3, first.sends.size)
        val later = 6_000L
        val refreshed = listOf("b" to 8.0, "c" to 7.0, "d" to 1.0)
            .fold(first.state) { acc, (id, rate) -> scheduler.upsertPeer(acc, id, rate, later) }
        val acked = ackAll(refreshed, first.sends.filter { it.peerId != "a" })
        val expired = scheduler.expire(acked, later)
        assertEquals(SlotStatus.PENDING, expired.segment(0)!!.slots[0].status)
        assertEquals(0, expired.peers.getValue("a").inFlight)
        assertEquals(listOf(Send("d", 0, 0)), scheduler.plan(expired, later).sends)
    }

    @Test
    fun `an unacknowledged send times out even if the peer is live`() {
        val first = scheduler.plan(scheduler.addSegment(peers("a" to 1.0), 0), 0)
        assertEquals(listOf(Send("a", 0, 0)), first.sends)
        assertSame(first.state, scheduler.expire(first.state, 100))
        val stillLive = scheduler.upsertPeer(first.state, "a", 1.0, 5_001)
        assertEquals(1, stillLive.peers.getValue("a").inFlight)
        val expired = scheduler.expire(stillLive, 5_001)
        assertEquals(listOf(Send("a", 0, 0)), scheduler.plan(expired, 5_001).sends)
    }

    @Test
    fun `a peer never takes more than its in-flight limit`() {
        val base = peers("only" to 1.0)
        val state = listOf(0, 1, 2).fold(base) { acc, i -> scheduler.addSegment(acc, i) }
        val plan = scheduler.plan(state, 0)
        assertEquals(listOf(Send("only", 0, 0), Send("only", 1, 0)), plan.sends)
        val afterAck = scheduler.onAck(plan.state, "only", 0, 0)
        assertEquals(listOf(Send("only", 2, 0)), scheduler.plan(afterAck, 0).sends)
    }

    @Test
    fun `duplicate, unknown and late acks are handled`() {
        val plan = scheduler.plan(scheduler.addSegment(peers("a" to 1.0, "b" to 1.0), 0), 0)
        val acked = scheduler.onAck(plan.state, plan.sends[0].peerId, 0, plan.sends[0].shardIndex)
        assertSame(acked, scheduler.onAck(acked, plan.sends[0].peerId, 0, plan.sends[0].shardIndex))
        assertSame(acked, scheduler.onAck(acked, "a", 9, 0))
        assertSame(acked, scheduler.onAck(acked, "a", 0, 9))
        val expired = scheduler.expire(acked, 10_000)
        val late = scheduler.onAck(expired, plan.sends[1].peerId, 0, plan.sends[1].shardIndex)
        assertEquals(2, late.segment(0)!!.ackedCount)
        assertTrue(late.peers.values.all { it.inFlight == 0 })
    }

    @Test
    fun `planning returns new state and leaves the input alone`() {
        val state = scheduler.addSegment(peers(*fivePeers), 0)
        val snapshot = state.copy()
        scheduler.plan(state, 0)
        assertEquals(snapshot, state)
        assertFailsWith<IllegalArgumentException> { scheduler.addSegment(state, 0) }
    }

    @Test
    fun `upserting a known peer keeps its in-flight count`() {
        val plan = scheduler.plan(scheduler.addSegment(peers("a" to 1.0), 0), 0)
        val updated = scheduler.upsertPeer(plan.state, "a", 42.0, 10)
        assertEquals(Peer("a", 42.0, 10, inFlight = 1), updated.peers.getValue("a"))
    }
}
