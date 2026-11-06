package xyz.ghostwitness.core.capture

data class RateState(val tier: Tier, val cleanWindows: Int = 0)

/** One 2 s window of measurements for [RateController.onWindow]. */
data class RateInput(
    val goodputBytesPerSec: Double,
    val backlogSegments: Int,
    val bulkLaneUp: Boolean,
    /** Connected holders right now. */
    val holders: Int,
)

/**
 * Picks the capture tier every 2 s window (spec section 6.4). Pure: each call returns a new state.
 *
 * `tier = min(goodput tier, holder-count tier)`:
 * - Goodput tier: the best tier whose data-shard egress fits in [headroom] of goodput; the floor (audio
 *   only) when the bulk lane is down.
 * - Holder-count tier: [HolderCaps]; 0 holders is audio only.
 * - Backlog over [maxBacklogSegments]: step down at once, at least one level.
 * - Target lower than now: go there at once.
 * - Target higher: go there only after [cleanWindowsToStepUp] windows in a row with no backlog.
 *
 * Goodput must be measured over the time the bulk lane was busy. Bytes over wall time would only measure
 * the current tier's own rate, and the controller could never step up.
 */
class RateController(
    private val ladder: List<Tier> = Tier.entries,
    private val headroom: Double = 0.7,
    private val maxBacklogSegments: Int = 2,
    private val cleanWindowsToStepUp: Int = 3,
    private val caps: HolderCaps = HolderCaps(),
) {
    init {
        require(ladder.isNotEmpty() && ladder == ladder.distinct().sortedBy { it.ordinal }) { "ladder must be best-first" }
        require(headroom > 0.0 && headroom <= 1.0) { "headroom must be in (0, 1]" }
        require(maxBacklogSegments >= 0 && cleanWindowsToStepUp > 0) { "bad thresholds" }
    }

    val top: Tier get() = ladder.first()
    val floor: Tier get() = ladder.last()

    fun onWindow(state: RateState, input: RateInput): RateState {
        val current = ladder.indexOf(clamp(state.tier))
        val target = maxOf(goodputTarget(input), ladder.indexOf(clamp(caps.capFor(input.holders))))
        return when {
            input.backlogSegments > maxBacklogSegments ->
                RateState(ladder[maxOf(current + 1, target).coerceAtMost(ladder.lastIndex)])
            target > current -> RateState(ladder[target])
            target == current || input.backlogSegments > 0 -> RateState(ladder[current])
            state.cleanWindows + 1 >= cleanWindowsToStepUp -> RateState(ladder[target])
            else -> RateState(ladder[current], state.cleanWindows + 1)
        }
    }

    /** Maps a tier outside the ladder to the best ladder tier that is not better than it. */
    fun clamp(tier: Tier): Tier = ladder.firstOrNull { it.ordinal >= tier.ordinal } ?: floor

    private fun goodputTarget(input: RateInput): Int {
        if (!input.bulkLaneUp) return ladder.lastIndex
        val fits = ladder.indexOfFirst { it.dataBytesPerSecond <= headroom * input.goodputBytesPerSec }
        return if (fits < 0) ladder.lastIndex else fits
    }
}
