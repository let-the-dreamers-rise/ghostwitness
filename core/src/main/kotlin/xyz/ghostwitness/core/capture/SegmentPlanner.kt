package xyz.ghostwitness.core.capture

enum class Track { VIDEO, AUDIO }

/**
 * One encoded sample as it leaves an encoder. [generation] numbers video encoders: a size change starts a
 * new encoder (a new generation) next to the old one, and segments switch over at a boundary.
 */
data class Sample(val track: Track, val ptsUs: Long, val isKeyFrame: Boolean, val generation: Int = 0)

/** What the muxer must do with the current sample. Actions are applied in list order. */
sealed interface MuxAction {
    /**
     * Start a new file. [tier] is the tier this segment is recorded at, so the caller applies its frame
     * rate and bitrate now. [videoGeneration] is the encoder whose samples this file takes, or null when
     * audio only; every older encoder can be released.
     */
    data class Open(
        val fileIndex: Int,
        val withVideo: Boolean,
        val startPtsUs: Long,
        val tier: Tier = Tier.A,
        val videoGeneration: Int? = null,
    ) : MuxAction

    data class Close(val fileIndex: Int, val withVideo: Boolean, val startPtsUs: Long, val endPtsUs: Long) : MuxAction

    data object Write : MuxAction

    data object Drop : MuxAction

    data object RequestSync : MuxAction
}

data class OpenSegment(
    val fileIndex: Int,
    val withVideo: Boolean,
    val startPtsUs: Long,
    val lastPtsUs: Long,
    val videoGeneration: Int? = null,
)

data class PlannerState(
    /** Tier for the next segment; a change takes effect at the next boundary. */
    val tier: Tier,
    val ready: Set<Track> = emptySet(),
    /** Newest video encoder whose format is known; new video segments start on its keyframes only. */
    val newestVideo: Int? = null,
    val open: OpenSegment? = null,
    val nextFileIndex: Int = 0,
    val syncRequestedAtUs: Long? = null,
)

data class Step(val state: PlannerState, val actions: List<MuxAction>)

/**
 * Decides where 2 s segments start and end (spec section 5). Pure: no Android types, every call returns a
 * new state.
 *
 * - A segment with video must start on a video keyframe, so close to the target length the planner asks
 *   the encoder for a sync frame. It asks only on a video sample, so the keyframe that follows always has
 *   a later timestamp and lands inside the tolerance window.
 * - An audio-only segment (tier A) starts on any audio sample once the target length is reached.
 * - Each segment ends exactly where the next one starts, so a tier switch never leaves a gap.
 * - A size change runs two encoder generations side by side for up to one segment: the open segment keeps
 *   taking samples from its own generation, and the next segment starts on a keyframe of the newest one.
 */
class SegmentPlanner(
    private val targetUs: Long = 2_000_000,
    private val syncLeadUs: Long = 50_000,
    private val toleranceUs: Long = 50_000,
    private val resyncAfterUs: Long = 500_000,
) {
    init {
        require(syncLeadUs <= toleranceUs) { "the sync lead must not exceed the tolerance, or keyframes land early" }
        require(targetUs > toleranceUs) { "target must exceed tolerance" }
    }

    fun onFormat(state: PlannerState, track: Track, generation: Int = 0): PlannerState = when (track) {
        Track.AUDIO -> state.copy(ready = state.ready + track)
        Track.VIDEO -> state.copy(
            ready = state.ready + track,
            newestVideo = maxOf(generation, state.newestVideo ?: generation),
        )
    }

    fun setTier(state: PlannerState, tier: Tier): PlannerState = state.copy(tier = tier)

    fun onSample(state: PlannerState, sample: Sample): Step {
        val open = state.open ?: return startIfPossible(state, sample)
        val elapsed = sample.ptsUs - open.startPtsUs
        val threshold = if (state.tier.hasVideo) targetUs - toleranceUs else targetUs
        if (elapsed >= threshold && canStartWith(state, sample)) {
            val close = MuxAction.Close(open.fileIndex, open.withVideo, open.startPtsUs, sample.ptsUs)
            val started = openWith(state, sample)
            return Step(started.state, listOf(close) + started.actions)
        }
        val sync = wantsSync(state, sample, elapsed >= targetUs - syncLeadUs)
        val write = sample.track == Track.AUDIO || (open.withVideo && sample.generation == open.videoGeneration)
        val nextOpen = if (write) open.copy(lastPtsUs = maxOf(open.lastPtsUs, sample.ptsUs)) else open
        val actions = listOfNotNull(MuxAction.RequestSync.takeIf { sync }, if (write) MuxAction.Write else MuxAction.Drop)
        val requestedAt = if (sync) sample.ptsUs else state.syncRequestedAtUs
        return Step(state.copy(open = nextOpen, syncRequestedAtUs = requestedAt), actions)
    }

    /** Closes the open segment, if any, at the last sample written to it. */
    fun finish(state: PlannerState): Step {
        val open = state.open ?: return Step(state, emptyList())
        val close = MuxAction.Close(open.fileIndex, open.withVideo, open.startPtsUs, open.lastPtsUs)
        return Step(state.copy(open = null, syncRequestedAtUs = null), listOf(close))
    }

    private fun startIfPossible(state: PlannerState, sample: Sample): Step {
        if (canStartWith(state, sample)) return openWith(state, sample)
        if (sample.track == Track.VIDEO && sample.isKeyFrame) {
            return Step(state.copy(syncRequestedAtUs = null), listOf(MuxAction.Drop))
        }
        val sync = state.ready.containsAll(Track.entries) && wantsSync(state, sample, due = true)
        return if (sync) {
            Step(state.copy(syncRequestedAtUs = sample.ptsUs), listOf(MuxAction.RequestSync, MuxAction.Drop))
        } else {
            Step(state, listOf(MuxAction.Drop))
        }
    }

    private fun wantsSync(state: PlannerState, sample: Sample, due: Boolean): Boolean {
        if (!due || !state.tier.hasVideo || sample.track != Track.VIDEO) return false
        val last = state.syncRequestedAtUs ?: return true
        return sample.ptsUs - last >= resyncAfterUs
    }

    private fun canStartWith(state: PlannerState, sample: Sample): Boolean = if (state.tier.hasVideo) {
        sample.track == Track.VIDEO && sample.isKeyFrame && sample.generation == state.newestVideo &&
            state.ready.containsAll(Track.entries)
    } else {
        sample.track == Track.AUDIO && Track.AUDIO in state.ready
    }

    private fun openWith(state: PlannerState, sample: Sample): Step {
        val withVideo = state.tier.hasVideo
        val generation = if (withVideo) sample.generation else null
        val segment = OpenSegment(state.nextFileIndex, withVideo, sample.ptsUs, sample.ptsUs, generation)
        val next = state.copy(open = segment, nextFileIndex = state.nextFileIndex + 1, syncRequestedAtUs = null)
        val open = MuxAction.Open(segment.fileIndex, withVideo, sample.ptsUs, state.tier, generation)
        return Step(next, listOf(open, MuxAction.Write))
    }
}
