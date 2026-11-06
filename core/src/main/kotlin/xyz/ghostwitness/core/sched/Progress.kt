package xyz.ghostwitness.core.sched

enum class SegmentStatus { SENDING, RECOVERABLE, FULLY_SPREAD }

/** Derives what the record screen shows from scheduler state (spec section 6.2). */
class Progress(private val config: SchedulerConfig = SchedulerConfig()) {
    fun status(segment: SegmentSlots): SegmentStatus = when {
        segment.ackedCount >= config.totalShards -> SegmentStatus.FULLY_SPREAD
        segment.ackedCount >= config.dataShards -> SegmentStatus.RECOVERABLE
        else -> SegmentStatus.SENDING
    }

    /**
     * How many holders of this segment can be lost, worst case, while it stays recoverable. Counts holders,
     * not shards: if one holder has 2 of 3 ACKed shards, losing it loses both. 0 when not yet recoverable.
     */
    fun spare(segment: SegmentSlots): Int {
        val perHolder = segment.ackedPerPeer
        var remaining = perHolder.sum()
        if (remaining < config.dataShards) return 0
        var lost = 0
        for (count in perHolder) {
            if (remaining - count < config.dataShards) break
            remaining -= count
            lost++
        }
        return lost
    }

    /** Distinct holders with at least one ACKed shard of this segment. */
    fun holderCount(segment: SegmentSlots): Int = segment.ackedPerPeer.size

    /** Highest segment index such that it and every earlier segment are recoverable; -1 if none. */
    fun recoverableUpTo(state: SchedulerState): Int =
        state.segments.sortedBy { it.index }
            .takeWhile { status(it) != SegmentStatus.SENDING }
            .lastOrNull()?.index ?: -1

    /** "OK TO LOSE PHONE": every finished segment is recoverable without the recording phone. */
    fun okToLosePhone(state: SchedulerState): Boolean =
        state.segments.isNotEmpty() && state.segments.all { status(it) != SegmentStatus.SENDING }

    /** Smallest spare count across all segments, shown next to the banner. */
    fun minimumSpare(state: SchedulerState): Int = state.segments.minOfOrNull { spare(it) } ?: 0
}
