package xyz.ghostwitness.core.sched

/**
 * Pure, immutable transfer scheduler (spec section 6.2). Every call returns a new state.
 *
 * - Oldest unsafe segment first.
 * - Pass 1 brings every segment to `dataShards` committed shards, lowest shard index (data) first.
 *   That makes a segment recoverable after 1.0x its size of egress instead of 1.67x.
 * - Pass 2 fills the remaining (parity) slots.
 * - Each shard goes to the live peer with spare capacity that holds the fewest shards of the segment,
 *   fastest first. With at least `totalShards` live peers that means one shard per peer. With fewer, a
 *   peer may take up to `ceil(totalShards / livePeers)` shards of a segment, so 1 or 2 holders still
 *   reach RECOVERABLE (the lower fault tolerance shows in [Progress.spare]).
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
        val live = state.peers.values.filter { isLive(it, nowMs) }
        if (live.isEmpty()) return null
        val perPeerLimit = (config.totalShards + live.size - 1) / live.size
        val held = segment.committedPerPeer
        val peer = live
            .filter { it.inFlight < config.maxInFlightPerPeer && (held[it.id] ?: 0) < perPeerLimit }
            .sortedWith(compareBy<Peer> { held[it.id] ?: 0 }.thenByDescending { it.goodputBytesPerSec }.thenBy { it.id })
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
