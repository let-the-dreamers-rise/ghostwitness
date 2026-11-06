package xyz.ghostwitness.core.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import xyz.ghostwitness.core.wire.FrameFormatException

class ControlCodecTest {
    private val recorded = RecordedSession(1)

    @Test
    fun `every frame type decodes by its magic`() {
        val entry = recorded.segments[0].segment.entry
        assertIs<ControlFrame.Session>(ControlCodec.decode(recorded.header.encode()))
        assertIs<ControlFrame.Root>(ControlCodec.decode(recorded.segments[0].root.encode()))
        assertIs<ControlFrame.Shard>(ControlCodec.decode(recorded.shardHeader(0, 1, 9L).encode()))
        assertIs<ControlFrame.Ack>(ControlCodec.decode(recorded.ackFrame(0 to 1).encode()))
        val decoded = assertIs<ControlFrame.Entry>(ControlCodec.decode(EntryFrame(recorded.header.sessionId, entry).encode()))
        assertContentEquals(entry.encode(), decoded.frame.entry.encode())
        assertContentEquals(recorded.header.sessionId, decoded.frame.sessionId)
    }

    @Test
    fun `unknown, short or malformed frames are rejected`() {
        val entryFrame = EntryFrame(recorded.header.sessionId, recorded.segments[0].segment.entry).encode()
        val malformed = listOf(
            byteArrayOf(1, 2),
            "XYZ123".toByteArray(),
            recorded.header.encode().copyOf(30),
            recorded.segments[0].root.encode().copyOf(50),
            entryFrame.copyOf(25),
            entryFrame.copyOf(entryFrame.size - 1),
            entryFrame.copyOf().also { it[3] = 7 },
        )
        malformed.forEach { assertFailsWith<FrameFormatException> { ControlCodec.decode(it) } }
        assertFailsWith<FrameFormatException> { EntryFrame.decode("GWX".toByteArray() + ByteArray(40)) }
        assertFailsWith<IllegalArgumentException> { EntryFrame(ByteArray(15), recorded.segments[0].segment.entry) }
    }
}
