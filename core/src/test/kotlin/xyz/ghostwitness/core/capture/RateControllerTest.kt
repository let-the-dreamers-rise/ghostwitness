package xyz.ghostwitness.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RateControllerTest {
    private val controller = RateController()
    private val plenty = 1_000_000.0

    private fun input(goodput: Double, backlog: Int = 0, bulk: Boolean = true, holders: Int = 5) =
        RateInput(goodput, backlog, bulk, holders)

    private fun run(start: RateState, windows: Int, goodput: Double, holders: Int = 5): RateState =
        (0 until windows).fold(start) { s, _ -> controller.onWindow(s, input(goodput, holders = holders)) }

    @Test
    fun `tiers carry size, frame rate and the data rates of the spec table`() {
        assertEquals(191_500.0, Tier.V2.dataBytesPerSecond)
        assertEquals(104_000.0, Tier.V1.dataBytesPerSecond)
        assertEquals(35_250.0, Tier.V0.dataBytesPerSecond)
        assertEquals(4_000.0, Tier.A.dataBytesPerSecond)
        assertEquals(VideoProfile(1280, 720, 30), Tier.V2.video)
        assertEquals(VideoProfile(848, 480, 24), Tier.V1_24.video)
        assertEquals(10, Tier.V0_10.video?.fps)
        assertEquals(false, Tier.A.hasVideo)
        assertEquals(true, Tier.V1.video!!.sameSize(Tier.V1_24.video))
        assertEquals(false, Tier.V1.video!!.sameSize(Tier.V0.video))
        assertEquals(false, Tier.V1.video!!.sameSize(null))
        assertFailsWith<IllegalArgumentException> { VideoProfile(0, 480, 30) }
    }

    @Test
    fun `a camera without 720p loses only the tiers it cannot fill`() {
        assertEquals(Tier.entries, Tier.fitting(1920, 1080))
        assertEquals(Tier.entries - Tier.V2, Tier.fitting(1280, 719))
        assertEquals(listOf(Tier.V0, Tier.V0_15, Tier.V0_10, Tier.A), Tier.fitting(640, 480))
        assertEquals(listOf(Tier.A), Tier.fitting(100, 100))
    }

    @Test
    fun `stepping up waits for three clean windows, then goes straight to the target`() {
        val start = RateState(Tier.A)
        assertEquals(Tier.A, run(start, 2, plenty).tier)
        assertEquals(Tier.V2, run(start, 3, plenty).tier)
        assertEquals(Tier.V2, run(start, 20, plenty).tier)
    }

    @Test
    fun `low goodput drops straight to the tier that fits`() {
        assertEquals(Tier.V0, controller.onWindow(RateState(Tier.V1), input(60_000.0)).tier)
        assertEquals(Tier.V0_10, controller.onWindow(RateState(Tier.V1), input(40_000.0)).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.V1), input(10_000.0)).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.V1), input(0.0)).tier)
    }

    @Test
    fun `a backlog over two segments steps down even when goodput looks fine`() {
        assertEquals(Tier.V1_24, controller.onWindow(RateState(Tier.V1), input(plenty, backlog = 3)).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.A), input(plenty, backlog = 3)).tier)
    }

    @Test
    fun `any backlog resets the clean window count`() {
        val two = run(RateState(Tier.V0), 2, plenty)
        assertEquals(2, two.cleanWindows)
        val dirty = controller.onWindow(two, input(plenty, backlog = 1))
        assertEquals(RateState(Tier.V0), dirty)
        assertEquals(Tier.V0, run(dirty, 2, plenty).tier)
    }

    @Test
    fun `losing the bulk lane drops to audio only`() {
        assertEquals(RateState(Tier.A), controller.onWindow(RateState(Tier.V1, 2), input(plenty, bulk = false)))
    }

    @Test
    fun `the holder count caps the tier whatever the goodput`() {
        assertEquals(Tier.V1, controller.onWindow(RateState(Tier.V2), input(plenty, holders = 4)).tier)
        assertEquals(Tier.V1_24, controller.onWindow(RateState(Tier.V2), input(plenty, holders = 3)).tier)
        assertEquals(Tier.V0_15, controller.onWindow(RateState(Tier.V2), input(plenty, holders = 2)).tier)
        assertEquals(Tier.V0_10, controller.onWindow(RateState(Tier.V2), input(plenty, holders = 1)).tier)
        assertEquals(Tier.A, controller.onWindow(RateState(Tier.V2), input(plenty, holders = 0)).tier)
        assertEquals(Tier.V2, controller.onWindow(RateState(Tier.V2), input(plenty, holders = 9)).tier)
        assertEquals(Tier.V1_24, run(RateState(Tier.A), 3, plenty, holders = 3).tier)
        assertEquals(Tier.V0_10, controller.onWindow(RateState(Tier.V2), input(40_000.0, holders = 2)).tier)
    }

    @Test
    fun `holder caps must not fall as holders rise`() {
        assertEquals(Tier.V2, HolderCaps().capFor(5))
        assertEquals(Tier.A, HolderCaps().capFor(-1))
        assertFailsWith<IllegalArgumentException> { HolderCaps(emptyList()) }
        assertFailsWith<IllegalArgumentException> { HolderCaps(listOf(Tier.V1, Tier.A)) }
    }

    @Test
    fun `tiers outside the ladder are clamped`() {
        val small = RateController(listOf(Tier.V1, Tier.V0, Tier.A))
        assertEquals(Tier.V1, small.clamp(Tier.V2))
        assertEquals(Tier.V1, small.onWindow(RateState(Tier.V2), input(plenty)).tier)
        assertEquals(Tier.V1, small.top)
        assertEquals(Tier.A, small.floor)
        assertEquals(Tier.V0, RateController(listOf(Tier.V2, Tier.V0)).clamp(Tier.A))
        // A cap between ladder steps rounds down: 240p15 is not on this ladder, so audio only.
        assertEquals(Tier.A, small.onWindow(RateState(Tier.V1), input(plenty, holders = 2)).tier)
        assertEquals(Tier.V0, small.onWindow(RateState(Tier.V1), input(plenty, holders = 9).copy(goodputBytesPerSec = 60_000.0)).tier)
    }

    @Test
    fun `invalid settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { RateController(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RateController(listOf(Tier.A, Tier.V1)) }
        assertFailsWith<IllegalArgumentException> { RateController(headroom = 0.0) }
        assertFailsWith<IllegalArgumentException> { RateController(cleanWindowsToStepUp = 0) }
    }
}
