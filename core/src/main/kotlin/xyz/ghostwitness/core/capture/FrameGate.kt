package xyz.ghostwitness.core.capture

private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * Thins a camera frame stream to a lower frame rate (spec section 5: 30, 24, 15 or 10 fps from a ~30 fps
 * camera). Pure: [offer] returns the next gate and whether to encode this frame.
 *
 * Frames are due every `1 / fps`; a frame up to [slackNs] early still counts, so camera jitter does not
 * skip a due frame. If the camera stalls for more than one interval, the schedule restarts from the next
 * frame instead of bursting to catch up.
 */
data class FrameGate(val fps: Int, val slackNs: Long = DEFAULT_SLACK_NS, val nextDueNs: Long? = null) {
    init {
        require(fps > 0) { "fps must be positive" }
        require(slackNs >= 0) { "slack must not be negative" }
    }

    val intervalNs: Long get() = NANOS_PER_SECOND / fps

    fun offer(timestampNs: Long): Pair<FrameGate, Boolean> {
        val due = nextDueNs ?: return copy(nextDueNs = timestampNs + intervalNs) to true
        if (timestampNs < due - slackNs) return this to false
        val next = if (timestampNs - due > intervalNs) timestampNs + intervalNs else due + intervalNs
        return copy(nextDueNs = next) to true
    }

    /** Same schedule, new rate; takes effect from the next due frame. */
    fun withFps(newFps: Int): FrameGate = copy(fps = newFps)

    companion object {
        const val DEFAULT_SLACK_NS = 8_000_000L
    }
}
