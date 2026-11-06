package xyz.ghostwitness.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RateControllerTest {
    private val controller = RateController()
    private val plenty = 1_000_000.0

    private fun run(start: RateState, windows: Int, goodput: Double, backlog: Int = 0): RateState =
        (0 until windows).fold(start) { s, _ -> controller.onWindow(s, goodput, backlog, bulkLaneUp = true) }

    @Test
    fun `tier data rates follow the spec table`() {
        assertEquals(104_000.0, Tier.V1.dataBytesPerSecond)
        assertEquals(35_250.0, Tier.V0.dataBytesPerSecond)
        assertEquals(4_000.0, Tier.A.dataBytesPerSecond)
        assertEquals(false, Tier.A.hasVideo)
    }

    @Test
    fun `stepping up needs three clean windows and stops at the top of the ladder`() {
        val start = RateState(Tier.A)
        assertEquals(Tier.A, run(start, 2, plenty).tier)
        assertEquals(Tier.V0, run(start, 3, plenty).tier)
        assertEquals(Tier.V1, run(start, 6, plenty).tier)
        assertEquals(Tier.V1, run(start, 20, plenty).tier)
    }

    @Test
    fun `low goodput drops straight to the tier that fits`() {
        assertEquals(Tier.V0, controller.onWindow(RateState(Tier.V1), 60_000.0, 0, true).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.V1), 10_000.0, 0, true).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.V1), 0.0, 0, true).tier)
    }

    @Test
    fun `a backlog over two segments steps down even when goodput looks fine`() {
        assertEquals(Tier.V0, controller.onWindow(RateState(Tier.V1), plenty, 3, true).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.A), plenty, 3, true).tier)
    }

    @Test
    fun `any backlog resets the clean window count`() {
        val two = run(RateState(Tier.V0), 2, plenty)
        assertEquals(2, two.cleanWindows)
        val dirty = controller.onWindow(two, plenty, 1, true)
        assertEquals(RateState(Tier.V0), dirty)
        assertEquals(Tier.V0, run(dirty, 2, plenty).tier)
    }

    @Test
    fun `losing the bulk lane drops to audio only`() {
        assertEquals(RateState(Tier.A), controller.onWindow(RateState(Tier.V1, 2), plenty, 0, bulkLaneUp = false))
    }

    @Test
    fun `tiers outside the ladder are clamped`() {
        assertEquals(Tier.V1, controller.clamp(Tier.V2))
        assertEquals(Tier.V1, controller.onWindow(RateState(Tier.V2), plenty, 0, true).tier)
        assertEquals(Tier.V1, controller.top)
        assertEquals(Tier.A, controller.floor)
        assertEquals(Tier.V0, RateController(listOf(Tier.V2, Tier.V0)).clamp(Tier.A))
    }

    @Test
    fun `invalid settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { RateController(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RateController(listOf(Tier.A, Tier.V1)) }
        assertFailsWith<IllegalArgumentException> { RateController(headroom = 0.0) }
        assertFailsWith<IllegalArgumentException> { RateController(cleanWindowsToStepUp = 0) }
    }
}
