package xyz.ghostwitness.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FRAME_US = 33_333L
private const val AAC_FRAME_US = 23_220L

/** A sample plus what the planner did with it. */
private data class Event(val sample: Sample, val actions: List<MuxAction>)

/**
 * Drives the planner with a model encoder: 30 fps video, AAC audio, a natural keyframe every [gopUs], and
 * a forced keyframe on the next frame after RequestSync (unless [honoursSync] is false).
 */
private fun simulate(
    durationUs: Long,
    tierAt: (Long) -> Tier = { Tier.V1 },
    audioReadyAtUs: Long = 0,
    honoursSync: Boolean = true,
    gopUs: Long = 2_000_000,
    planner: SegmentPlanner = SegmentPlanner(),
): Pair<List<Event>, Step> {
    val video = (0 until durationUs step FRAME_US).map { Track.VIDEO to it }
    val audio = (0 until durationUs step AAC_FRAME_US).map { Track.AUDIO to it }
    val timeline = (video + audio).sortedWith(compareBy({ it.second }, { it.first.ordinal }))
    data class Sim(val state: PlannerState, val lastKeyUs: Long?, val forceNext: Boolean, val events: List<Event>)
    val initial = Sim(planner.onFormat(PlannerState(tierAt(0)), Track.VIDEO), null, false, emptyList())
    val end = timeline.fold(initial) { sim, (track, pts) ->
        val tiered = if (sim.state.tier != tierAt(pts)) planner.setTier(sim.state, tierAt(pts)) else sim.state
        val ready = if (pts >= audioReadyAtUs && Track.AUDIO !in tiered.ready) planner.onFormat(tiered, Track.AUDIO) else tiered
        val isKey = track == Track.VIDEO && (sim.forceNext || sim.lastKeyUs == null || pts - sim.lastKeyUs >= gopUs)
        val step = planner.onSample(ready, Sample(track, pts, track == Track.AUDIO || isKey))
        val requested = MuxAction.RequestSync in step.actions && honoursSync
        Sim(
            state = step.state,
            lastKeyUs = if (isKey) pts else sim.lastKeyUs,
            forceNext = if (track == Track.VIDEO) requested else sim.forceNext || requested,
            events = sim.events + Event(Sample(track, pts, track == Track.AUDIO || isKey), step.actions),
        )
    }
    return end.events to planner.finish(end.state)
}

private fun closes(events: List<Event>, finish: Step): List<MuxAction.Close> =
    events.flatMap { it.actions }.filterIsInstance<MuxAction.Close>() + finish.actions.filterIsInstance<MuxAction.Close>()

private fun opens(events: List<Event>): List<Pair<Sample, MuxAction.Open>> =
    events.flatMap { e -> e.actions.filterIsInstance<MuxAction.Open>().map { e.sample to it } }

class SegmentPlannerTest {
    @Test
    fun `a 60 s recording gives 30 segments of about 2 s with no gaps`() {
        val (events, finish) = simulate(59_990_000)
        val segments = closes(events, finish)
        assertEquals(30, segments.size)
        segments.dropLast(1).forEach { assertTrue(it.endPtsUs - it.startPtsUs in 1_950_000..2_050_000, "duration of $it") }
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endPtsUs, b.startPtsUs, "gap between $a and $b") }
        assertEquals(0L, segments.first().startPtsUs)
        assertTrue(opens(events).all { (sample, _) -> sample.track == Track.VIDEO && sample.isKeyFrame })
    }

    @Test
    fun `switching to audio only and back leaves no gap and keeps segments short`() {
        val tierAt = { pts: Long -> if (pts in 10_000_000 until 20_000_000) Tier.A else Tier.V1 }
        val (events, finish) = simulate(30_000_000, tierAt)
        val segments = closes(events, finish)
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endPtsUs, b.startPtsUs, "gap between $a and $b") }
        segments.dropLast(1).forEach { assertTrue(it.endPtsUs - it.startPtsUs <= 2_100_000, "too long: $it") }
        assertTrue(segments.any { !it.withVideo }, "expected audio-only segments")
        assertTrue(segments.last().withVideo, "video should come back")
        opens(events).filter { it.second.withVideo }.forEach { (sample, _) ->
            assertTrue(sample.track == Track.VIDEO && sample.isKeyFrame, "video segment must start on a keyframe")
        }
    }

    @Test
    fun `video samples are dropped from audio-only segments and audio is always written`() {
        val (events, _) = simulate(8_000_000, tierAt = { Tier.A })
        val afterOpen = events.dropWhile { e -> e.actions.none { it is MuxAction.Open } }.drop(1)
        assertTrue(afterOpen.filter { it.sample.track == Track.VIDEO }.all { MuxAction.Drop in it.actions })
        assertTrue(afterOpen.filter { it.sample.track == Track.AUDIO }.all { MuxAction.Write in it.actions })
        assertTrue(events.none { MuxAction.RequestSync in it.actions })
    }

    @Test
    fun `nothing opens until both formats are known, then one sync request starts it`() {
        val (events, _) = simulate(2_000_000, audioReadyAtUs = 500_000)
        val firstOpen = opens(events).first()
        assertTrue(firstOpen.first.ptsUs in 500_000..600_000, "opened at ${firstOpen.first.ptsUs}")
        val beforeOpen = events.takeWhile { e -> e.actions.none { it is MuxAction.Open } }
        assertEquals(1, beforeOpen.count { MuxAction.RequestSync in it.actions })
        assertTrue(beforeOpen.all { MuxAction.Drop in it.actions })
    }

    @Test
    fun `an encoder that ignores sync requests is asked again every 500 ms`() {
        val (events, finish) = simulate(4_000_000, honoursSync = false, gopUs = Long.MAX_VALUE / 4)
        val requests = events.filter { MuxAction.RequestSync in it.actions }.map { it.sample.ptsUs }
        assertTrue(requests.size >= 3, "requests at $requests")
        requests.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 500_000) }
        assertEquals(1, closes(events, finish).size)
    }

    @Test
    fun `finish closes the open segment at the last written sample`() {
        val planner = SegmentPlanner()
        assertEquals(emptyList(), planner.finish(PlannerState(Tier.V1)).actions)
        val ready = Track.entries.fold(PlannerState(Tier.V1)) { s, t -> planner.onFormat(s, t) }
        val opened = planner.onSample(ready, Sample(Track.VIDEO, 1_000, true)).state
        val written = planner.onSample(opened, Sample(Track.AUDIO, 5_000, true)).state
        val finish = planner.finish(written)
        assertEquals(listOf<MuxAction>(MuxAction.Close(0, true, 1_000, 5_000)), finish.actions)
        assertEquals(null, finish.state.open)
    }

    @Test
    fun `invalid timings are rejected`() {
        assertFailsWith<IllegalArgumentException> { SegmentPlanner(syncLeadUs = 100_000, toleranceUs = 50_000) }
        assertFailsWith<IllegalArgumentException> { SegmentPlanner(targetUs = 40_000) }
    }
}
