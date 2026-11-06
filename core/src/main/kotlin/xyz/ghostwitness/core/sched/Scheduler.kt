package xyz.ghostwitness.core.sched

/**
 * Pure, immutable transfer scheduler (spec section 6.2). Every call returns a new state.
 *
 * - Oldest unsafe segment first.
 * - Pass 1 brings every segment to `dataShards` committed shards, lowest shard index (data) first.
 *   That makes a segment recoverable after 1.0x its size of egress instead of 1.67x.
 * - Pass 2 fills the remaining (parity) slots.
 * - Each shard goes to the fastest live peer with spare capacity that holds no other shard of the segment.
 * - Timed-out sends and sends to stale peers go back to PENDING and are reassigned.
 */
class Scheduler(private val config: SchedulerConfig = SchedulerConfig()) {
    fun addSegment(state: SchedulerState, index: Int): SchedulerState {
        require(state.segment(index) == null) { "segment $index already scheduled" }
        val slots = (0 until config.totalShards).map { ShardSlot(it) }
        return state.copy(segments = (state.segments + SegmentSlots(index, slots)).sortedBy { it.index })
    }

    fun upsertPeer(state: SchedulerState, peerId: String, goodputBytesPerSec: Double, nowMs: Long): SchedulerState {
        val existing = state.peers[peerId]
        val peer = existing?.copy(goodputBytesPerSec = goodputBytesPerSec, lastSeenMs = nowMs)
            ?: Peer(peerId, goodputBytesPerSec, nowMs)
        return state.withPeer(peer)
    }

    /** Forgets a disconnected peer. Its in-flight sends are released by the next [expire]; its ACKed shards still count. */
    fun removePeer(state: SchedulerState, peerId: String): SchedulerState = state.copy(peers = state.peers - peerId)

    fun expire(state: SchedulerState, nowMs: Long): SchedulerState =
        state.segments.fold(state) { acc, segment ->
            segment.slots.fold(acc) { inner, slot -> if (shouldExpire(inner, slot, nowMs)) release(inner, segment.index, slot) else inner }
        }

    fun onAck(state: SchedulerState, peerId: String, segmentIndex: Int, shardIndex: Int): SchedulerState {
        val slot = state.segment(segmentIndex)?.slots?.getOrNull(shardIndex) ?: return state
        if (slot.status == SlotStatus.ACKED) return state
        val acked = state.withSlot(segmentIndex, slot.copy(status = SlotStatus.ACKED, peerId = peerId))
        return if (slot.status == SlotStatus.SENT) acked.releasePeer(slot.peerId) else acked
    }

    fun plan(state: SchedulerState, nowMs: Long): Plan {
        val order = state.segments.map { it.index }
        val afterData = order.fold(Plan(emptyList(), state)) { plan, index ->
            val needed = config.dataShards - (plan.state.segment(index)?.committedCount ?: 0)
            assignRepeatedly(plan, index, nowMs, needed)
        }
        return order.fold(afterData) { plan, index -> assignRepeatedly(plan, index, nowMs, config.totalShards) }
    }

    private fun assignRepeatedly(plan: Plan, segmentIndex: Int, nowMs: Long, times: Int): Plan {
        var current = plan
        repeat(times.coerceAtLeast(0)) {
            val next = assignNext(current.state, segmentIndex, nowMs) ?: return current
            current = Plan(current.sends + next.first, next.second)
        }
        return current
    }

    private fun assignNext(state: SchedulerState, segmentIndex: Int, nowMs: Long): Pair<Send, SchedulerState>? {
        val segment = state.segment(segmentIndex) ?: return null
        val slot = segment.slots.firstOrNull { it.status == SlotStatus.PENDING } ?: return null
        val holders = segment.holders
        val peer = state.peers.values
            .filter { isLive(it, nowMs) && it.inFlight < config.maxInFlightPerPeer && it.id !in holders }
            .sortedWith(compareByDescending<Peer> { it.goodputBytesPerSec }.thenBy { it.id })
            .firstOrNull() ?: return null
        val next = state
            .withSlot(segmentIndex, slot.copy(status = SlotStatus.SENT, peerId = peer.id, sentAtMs = nowMs))
            .withPeer(peer.copy(inFlight = peer.inFlight + 1))
        return Send(peer.id, segmentIndex, slot.shardIndex) to next
    }

    private fun shouldExpire(state: SchedulerState, slot: ShardSlot, nowMs: Long): Boolean {
        if (slot.status != SlotStatus.SENT) return false
        val peer = slot.peerId?.let { state.peers[it] }
        val timedOut = nowMs - (slot.sentAtMs ?: nowMs) > config.ackTimeoutMs
        return timedOut || peer == null || !isLive(peer, nowMs)
    }

    private fun release(state: SchedulerState, segmentIndex: Int, slot: ShardSlot): SchedulerState =
        state.withSlot(segmentIndex, ShardSlot(slot.shardIndex)).releasePeer(slot.peerId)

    private fun isLive(peer: Peer, nowMs: Long): Boolean = nowMs - peer.lastSeenMs <= config.peerStaleMs
}
