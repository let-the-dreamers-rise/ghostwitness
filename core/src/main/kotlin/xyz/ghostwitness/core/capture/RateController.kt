package xyz.ghostwitness.core.capture

data class RateState(val tier: Tier, val cleanWindows: Int = 0)

/**
 * Picks the capture tier every 2 s window (spec section 6.4). Pure: each call returns a new state.
 *
 * - No bulk lane: drop to the floor tier (audio only) at once.
 * - Target = the best tier whose data-shard egress fits in [headroom] of goodput.
 * - Backlog over [maxBacklogSegments]: step down at once, at least one level.
 * - Target lower than now: go there at once.
 * - Target higher: step up one level only after [cleanWindowsToStepUp] windows in a row with no backlog.
 *
 * Goodput must be measured over the time the bulk lane was busy. Bytes over wall time would only measure
 * the current tier's own rate, and the controller could never step up.
 */
class RateController(
    private val ladder: List<Tier> = listOf(Tier.V1, Tier.V0, Tier.A),
    private val headroom: Double = 0.7,
    private val maxBacklogSegments: Int = 2,
    private val cleanWindowsToStepUp: Int = 3,
) {
    init {
        require(ladder.isNotEmpty() && ladder == ladder.distinct().sortedBy { it.ordinal }) { "ladder must be best-first" }
        require(headroom > 0.0 && headroom <= 1.0) { "headroom must be in (0, 1]" }
        require(maxBacklogSegments >= 0 && cleanWindowsToStepUp > 0) { "bad thresholds" }
    }

    val top: Tier get() = ladder.first()
    val floor: Tier get() = ladder.last()

    fun onWindow(state: RateState, goodputBytesPerSec: Double, backlogSegments: Int, bulkLaneUp: Boolean): RateState {
        if (!bulkLaneUp) return RateState(floor)
        val current = ladder.indexOf(clamp(state.tier))
        val target = ladder.indexOfFirst { it.dataBytesPerSecond <= headroom * goodputBytesPerSec }
            .let { if (it < 0) ladder.lastIndex else it }
        return when {
            backlogSegments > maxBacklogSegments -> RateState(ladder[maxOf(current + 1, target).coerceAtMost(ladder.lastIndex)])
            target > current -> RateState(ladder[target])
            target == current || backlogSegments > 0 -> RateState(ladder[current])
            state.cleanWindows + 1 >= cleanWindowsToStepUp -> RateState(ladder[current - 1])
            else -> RateState(ladder[current], state.cleanWindows + 1)
        }
    }

    /** Maps a tier outside the ladder to the best ladder tier that is not better than it. */
    fun clamp(tier: Tier): Tier = ladder.firstOrNull { it.ordinal >= tier.ordinal } ?: floor
}
