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
        assertEquals(listOf("a", "b", "c", "a", "b"), first.sends.map { it.peerId })
        val later = 6_000L
        val refreshed = listOf("b" to 8.0, "c" to 7.0, "d" to 1.0)
            .fold(first.state) { acc, (id, rate) -> scheduler.upsertPeer(acc, id, rate, later) }
        val acked = ackAll(refreshed, first.sends.filter { it.peerId != "a" })
        val expired = scheduler.expire(acked, later)
        assertEquals(SlotStatus.PENDING, expired.segment(0)!!.slots[0].status)
        assertEquals(SlotStatus.PENDING, expired.segment(0)!!.slots[3].status)
        assertEquals(0, expired.peers.getValue("a").inFlight)
        // d holds nothing yet, so it gets the first; then c (1 shard, faster than d) the second.
        assertEquals(listOf(Send("d", 0, 0), Send("c", 0, 3)), scheduler.plan(expired, later).sends)
    }

    @Test
    fun `an unacknowledged send times out even if the peer is live`() {
        val first = scheduler.plan(scheduler.addSegment(peers("a" to 1.0), 0), 0)
        assertEquals(listOf(Send("a", 0, 0), Send("a", 0, 1)), first.sends)
        assertSame(first.state, scheduler.expire(first.state, 100))
        val stillLive = scheduler.upsertPeer(first.state, "a", 1.0, 5_001)
        assertEquals(2, stillLive.peers.getValue("a").inFlight)
        val expired = scheduler.expire(stillLive, 5_001)
        assertEquals(listOf(Send("a", 0, 0), Send("a", 0, 1)), scheduler.plan(expired, 5_001).sends)
    }

    @Test
    fun `a peer never takes more than its in-flight limit`() {
        val base = peers("only" to 1.0)
        val state = listOf(0, 1, 2).fold(base) { acc, i -> scheduler.addSegment(acc, i) }
        val plan = scheduler.plan(state, 0)
        assertEquals(listOf(Send("only", 0, 0), Send("only", 0, 1)), plan.sends)
        val afterAck = scheduler.onAck(plan.state, "only", 0, 0)
        assertEquals(listOf(Send("only", 0, 2)), scheduler.plan(afterAck, 0).sends)
    }

    private fun spreadOver(vararg ids: String): SchedulerState {
        val roomy = Scheduler(SchedulerConfig(maxInFlightPerPeer = 5))
        val base = ids.withIndex().fold(SchedulerState()) { acc, (i, id) -> roomy.upsertPeer(acc, id, 100.0 - i, 0) }
        val plan = roomy.plan(roomy.addSegment(base, 0), 0)
        return plan.sends.fold(plan.state) { acc, s -> roomy.onAck(acc, s.peerId, s.segmentIndex, s.shardIndex) }
    }

    @Test
    fun `fewer holders than shards still place every shard, balanced, and the spare count drops`() {
        val expected = mapOf(1 to (listOf(5) to 0), 2 to (listOf(3, 2) to 0), 3 to (listOf(2, 2, 1) to 1), 4 to (listOf(2, 1, 1, 1) to 1), 5 to (listOf(1, 1, 1, 1, 1) to 2))
        expected.forEach { (holders, want) ->
            val segment = spreadOver(*Array(holders) { "h$it" }).segment(0)!!
            assertEquals(SegmentStatus.FULLY_SPREAD, progress.status(segment), "$holders holders")
            assertEquals(want.first, segment.ackedPerPeer, "$holders holders")
            assertEquals(want.second, progress.spare(segment), "$holders holders")
            assertEquals(holders, progress.holderCount(segment))
        }
    }

    @Test
    fun `with two holders the data shards still reach both before any doubles up`() {
        val roomy = Scheduler(SchedulerConfig(maxInFlightPerPeer = 5))
        val base = listOf("a" to 2.0, "b" to 1.0).fold(SchedulerState()) { acc, (id, r) -> roomy.upsertPeer(acc, id, r, 0) }
        val sends = roomy.plan(roomy.addSegment(base, 0), 0).sends
        assertEquals(listOf(Send("a", 0, 0), Send("b", 0, 1), Send("a", 0, 2), Send("b", 0, 3), Send("a", 0, 4)), sends)
    }

    @Test
    fun `a recoverable segment on one holder has no spare`() {
        val roomy = Scheduler(SchedulerConfig(maxInFlightPerPeer = 5))
        val plan = roomy.plan(roomy.addSegment(roomy.upsertPeer(SchedulerState(), "solo", 1.0, 0), 0), 0)
        val three = plan.sends.take(3).fold(plan.state) { acc, s -> roomy.onAck(acc, s.peerId, 0, s.shardIndex) }
        assertEquals(SegmentStatus.RECOVERABLE, progress.status(three.segment(0)!!))
        assertEquals(0, progress.spare(three.segment(0)!!))
        assertEquals(0, progress.spare(SegmentSlots(9, List(5) { ShardSlot(it) })))
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
        assertEquals(Peer("a", 42.0, 10, inFlight = 2), updated.peers.getValue("a"))
    }
}
