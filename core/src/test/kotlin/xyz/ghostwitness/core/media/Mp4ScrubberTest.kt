package xyz.ghostwitness.core.media

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun box(type: String, vararg body: ByteArray): ByteArray {
    val payload = body.fold(ByteArray(0)) { acc, b -> acc + b }
    return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
}

/** A full box with version 0 (u32 times) or 1 (u64 times), then [tail]. */
private fun timed(type: String, version: Int, creation: Long, modification: Long, tail: ByteArray = ByteArray(8) { 7 }): ByteArray {
    val times = if (version == 1) {
        ByteBuffer.allocate(16).putLong(creation).putLong(modification).array()
    } else {
        ByteBuffer.allocate(8).putInt(creation.toInt()).putInt(modification.toInt()).array()
    }
    return box(type, byteArrayOf(version.toByte(), 0, 0, 0), times, tail)
}

private fun ByteArray.has(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

class Mp4ScrubberTest {
    private val creation = 0x7A11_CE5EL
    private val udta = box("udta", box("©xyz", "+19.0760+072.8777/".toByteArray()))
    private val moovMeta = box("meta", ByteArray(4), box("keys", "com.android.version 14 Pixel 8".toByteArray()))
    private val mdat = box("mdat", ByteArray(64) { it.toByte() })
    private val file = box("ftyp", "isom".toByteArray()) + mdat + box(
        "moov",
        timed("mvhd", 0, creation, creation),
        box("trak", timed("tkhd", 1, creation, creation), box("mdia", timed("mdhd", 0, creation, creation), box("hdlr", ByteArray(12) { 5 }))),
        udta,
        moovMeta,
    )

    @Test
    fun `location, device strings and creation times are removed without moving any byte`() {
        val scrubbed = Mp4Scrubber.scrub(file)
        assertEquals(file.size, scrubbed.size)
        assertTrue(file.has("+19.0760") && file.has("Pixel 8"))
        assertFalse(scrubbed.has("+19.0760"))
        assertFalse(scrubbed.has("Pixel 8"))
        assertFalse(scrubbed.has("udta"))
        assertFalse(scrubbed.has("meta"))
        val stamp = String(byteArrayOf(0x7A, 0x11, 0xCE.toByte(), 0x5E), Charsets.ISO_8859_1)
        assertTrue(file.has(stamp))
        assertFalse(scrubbed.has(stamp), "creation time bytes left behind")
        val mdatAt = 12
        assertContentEquals(mdat, scrubbed.copyOfRange(mdatAt, mdatAt + mdat.size))
        assertTrue(scrubbed.has("hdlr") && scrubbed.has("free"))
    }

    @Test
    fun `the input is left alone and a clean file comes back unchanged`() {
        val copy = file.copyOf()
        Mp4Scrubber.scrub(file)
        assertContentEquals(copy, file)
        val clean = box("ftyp", "isom".toByteArray()) + mdat
        assertContentEquals(clean, Mp4Scrubber.scrub(clean))
    }

    @Test
    fun `large and to-end box sizes are understood`() {
        val large = ByteBuffer.allocate(16 + 4).putInt(1).put("udta".toByteArray()).putLong(20).put("GPS!".toByteArray()).array()
        assertFalse(Mp4Scrubber.scrub(large).has("GPS!"))
        val toEnd = ByteBuffer.allocate(8 + 4).putInt(0).put("meta".toByteArray()).put("GPS!".toByteArray()).array()
        assertFalse(Mp4Scrubber.scrub(toEnd).has("GPS!"))
    }

    @Test
    fun `malformed boxes are rejected`() {
        assertFailsWith<Mp4FormatException> { Mp4Scrubber.scrub(ByteArray(5)) }
        assertFailsWith<Mp4FormatException> { Mp4Scrubber.scrub(box("moov").copyOf(12).also { it[3] = 99 }) }
        assertFailsWith<Mp4FormatException> { Mp4Scrubber.scrub(ByteBuffer.allocate(8).putInt(4).put("free".toByteArray()).array()) }
        assertFailsWith<Mp4FormatException> { Mp4Scrubber.scrub(ByteBuffer.allocate(12).putInt(1).put("free".toByteArray()).putInt(0).array()) }
        assertFailsWith<Mp4FormatException> { Mp4Scrubber.scrub(box("mvhd", ByteArray(2))) }
        assertFailsWith<Mp4FormatException> { Mp4Scrubber.scrub(box("mvhd", byteArrayOf(1, 0, 0, 0), ByteArray(8))) }
    }
}
