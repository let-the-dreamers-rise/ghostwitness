package xyz.ghostwitness.core.sched

/** Tunables for the transfer scheduler (spec section 6.2). */
data class SchedulerConfig(
    val totalShards: Int = 5,
    val dataShards: Int = 3,
    val maxInFlightPerPeer: Int = 2,
    val ackTimeoutMs: Long = 5_000,
    val peerStaleMs: Long = 5_000,
) {
    init {
        require(dataShards in 1..totalShards) { "data shards must be between 1 and total shards" }
        require(maxInFlightPerPeer > 0) { "each peer needs at least one in-flight slot" }
    }
}

enum class SlotStatus { PENDING, SENT, ACKED }

data class ShardSlot(
    val shardIndex: Int,
    val status: SlotStatus = SlotStatus.PENDING,
    val peerId: String? = null,
    val sentAtMs: Long? = null,
)

data class SegmentSlots(val index: Int, val slots: List<ShardSlot>) {
    val ackedCount: Int get() = slots.count { it.status == SlotStatus.ACKED }
    val committedCount: Int get() = slots.count { it.status != SlotStatus.PENDING }

    /** Peers that already hold or are receiving a shard of this segment. */
    val holders: Set<String> get() = slots.filter { it.status != SlotStatus.PENDING }.mapNotNull { it.peerId }.toSet()

    /** Shards each peer holds or is receiving for this segment. */
    val committedPerPeer: Map<String, Int>
        get() = slots.filter { it.status != SlotStatus.PENDING }.mapNotNull { it.peerId }.groupingBy { it }.eachCount()

    /** ACKed shards per peer, largest first. */
    val ackedPerPeer: List<Int>
        get() = slots.filter { it.status == SlotStatus.ACKED }.mapNotNull { it.peerId }
            .groupingBy { it }.eachCount().values.sortedDescending()
}

data class Peer(
    val id: String,
    val goodputBytesPerSec: Double,
    val lastSeenMs: Long,
    val inFlight: Int = 0,
)

data class SchedulerState(
    val segments: List<SegmentSlots> = emptyList(),
    val peers: Map<String, Peer> = emptyMap(),
) {
    fun segment(index: Int): SegmentSlots? = segments.firstOrNull { it.index == index }

    internal fun withSlot(segmentIndex: Int, slot: ShardSlot): SchedulerState = copy(
        segments = segments.map { segment ->
            if (segment.index != segmentIndex) {
                segment
            } else {
                segment.copy(slots = segment.slots.map { if (it.shardIndex == slot.shardIndex) slot else it })
            }
        },
    )

    internal fun withPeer(peer: Peer): SchedulerState = copy(peers = peers + (peer.id to peer))

    internal fun releasePeer(peerId: String?): SchedulerState {
        val peer = peerId?.let { peers[it] } ?: return this
        return withPeer(peer.copy(inFlight = maxOf(0, peer.inFlight - 1)))
    }
}

data class Send(val peerId: String, val segmentIndex: Int, val shardIndex: Int)

data class Plan(val sends: List<Send>, val state: SchedulerState)
