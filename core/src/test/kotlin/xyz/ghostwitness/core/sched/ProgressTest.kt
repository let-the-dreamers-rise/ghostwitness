package xyz.ghostwitness.core.sched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTest {
    private val progress = Progress()

    private fun segment(index: Int, acked: Int, sent: Int = 0) = SegmentSlots(
        index,
        List(5) { j ->
            when {
                j < acked -> ShardSlot(j, SlotStatus.ACKED, "p$j", 0)
                j < acked + sent -> ShardSlot(j, SlotStatus.SENT, "p$j", 0)
                else -> ShardSlot(j)
            }
        },
    )

    @Test
    fun `recoverable prefix stops at the first segment still sending`() {
        val state = SchedulerState(listOf(segment(0, 3), segment(1, 5), segment(2, 2), segment(3, 4)))
        assertEquals(1, progress.recoverableUpTo(state))
        assertFalse(progress.okToLosePhone(state))
        assertEquals(0, progress.minimumSpare(state))
    }

    @Test
    fun `an empty session is not safe yet`() {
        val empty = SchedulerState()
        assertEquals(-1, progress.recoverableUpTo(empty))
        assertFalse(progress.okToLosePhone(empty))
        assertEquals(0, progress.minimumSpare(empty))
    }

    @Test
    fun `all segments recoverable means the phone can be lost`() {
        val state = SchedulerState(listOf(segment(0, 4), segment(1, 5)))
        assertTrue(progress.okToLosePhone(state))
        assertEquals(1, progress.recoverableUpTo(state))
        assertEquals(1, progress.minimumSpare(state))
    }

    @Test
    fun `holders and counts include in-flight shards`() {
        val slots = segment(0, acked = 1, sent = 2)
        assertEquals(1, slots.ackedCount)
        assertEquals(3, slots.committedCount)
        assertEquals(setOf("p0", "p1", "p2"), slots.holders)
    }

    @Test
    fun `invalid configs are rejected`() {
        assertFailsWith<IllegalArgumentException> { SchedulerConfig(totalShards = 2, dataShards = 3) }
        assertFailsWith<IllegalArgumentException> { SchedulerConfig(maxInFlightPerPeer = 0) }
    }
}
