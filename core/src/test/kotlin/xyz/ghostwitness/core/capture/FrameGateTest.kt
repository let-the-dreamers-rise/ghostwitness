package xyz.ghostwitness.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameGateTest {
    /** Frames from a 30 fps camera with +-2 ms jitter, run through a gate at [fps] for [seconds]. */
    private fun passed(fps: Int, seconds: Int = 10): List<Long> {
        val frames = (0 until 30 * seconds).map { i -> i * 33_333_333L + if (i % 2 == 0) 2_000_000L else -2_000_000L }
        return frames.fold(FrameGate(fps) to emptyList<Long>()) { (gate, kept), ts ->
            val (next, take) = gate.offer(ts)
            next to if (take) kept + ts else kept
        }.second
    }

    @Test
    fun `a 30 fps camera thins to each tier frame rate`() {
        mapOf(30 to 300, 24 to 240, 15 to 150, 10 to 100).forEach { (fps, expected) ->
            val count = passed(fps).size
            assertTrue(count in expected - 3..expected + 1, "$fps fps kept $count of 300")
        }
    }

    @Test
    fun `kept frames are evenly spaced, never closer than the slack allows`() {
        val gaps = passed(15).zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it in 60_000_000L..72_000_000L }, "gaps $gaps")
    }

    @Test
    fun `a camera stall restarts the schedule instead of bursting`() {
        val gate = FrameGate(10)
        val (afterFirst, first) = gate.offer(0)
        val (afterStall, stalled) = afterFirst.offer(1_000_000_000L)
        val (_, burst) = afterStall.offer(1_033_000_000L)
        assertTrue(first && stalled)
        assertEquals(false, burst)
        assertEquals(1_100_000_000L, afterStall.nextDueNs)
    }

    @Test
    fun `changing the rate keeps the schedule and bad settings are rejected`() {
        val gate = FrameGate(30).offer(0).first.withFps(10)
        assertEquals(10, gate.fps)
        assertEquals(33_333_333L, gate.nextDueNs)
        assertEquals(100_000_000L, gate.intervalNs)
        assertFailsWith<IllegalArgumentException> { FrameGate(0) }
        assertFailsWith<IllegalArgumentException> { FrameGate(10, slackNs = -1) }
    }
}
