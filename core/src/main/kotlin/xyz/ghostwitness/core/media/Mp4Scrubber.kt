package xyz.ghostwitness.core.media

import java.nio.ByteBuffer

class Mp4FormatException(message: String) : Exception(message)

/**
 * Removes identifying metadata from an MP4 before it is encrypted (spec section 5). The recovered video
 * must not undo the pseudonyms in the index by carrying a location, a device or OS string, or wall-clock
 * creation times.
 *
 * - `udta` and `meta` boxes (the location atom, `com.android.version`, capture fps keys) become `free` boxes
 *   of the same size with zeroed contents.
 * - Creation and modification times in `mvhd`, `tkhd` and `mdhd` become 0.
 *
 * Box sizes never change, so chunk offsets (`stco`/`co64`) stay valid whether `moov` comes before or
 * after `mdat`. Pure: returns a scrubbed copy and leaves the input alone.
 */
object Mp4Scrubber {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf")
    private val NEUTRALIZE = setOf("udta", "meta")
    private val TIMED = setOf("mvhd", "tkhd", "mdhd")
    private const val HEADER_BYTES = 8
    private const val LARGE_HEADER_BYTES = 16
    private const val VERSION_FLAGS_BYTES = 4
    private const val FREE = "free"

    fun scrub(mp4: ByteArray): ByteArray {
        val out = mp4.copyOf()
        walk(ByteBuffer.wrap(out), 0, out.size)
        return out
    }

    private fun walk(buffer: ByteBuffer, from: Int, to: Int) {
        var offset = from
        while (offset < to) {
            if (to - offset < HEADER_BYTES) throw Mp4FormatException("truncated box header at $offset")
            val box = readBox(buffer, offset, to)
            when (box.type) {
                in NEUTRALIZE -> neutralize(buffer, box)
                in TIMED -> zeroTimes(buffer, box)
                in CONTAINERS -> walk(buffer, box.bodyStart, box.end)
            }
            offset = box.end
        }
    }

    private class Box(val type: String, val start: Int, val bodyStart: Int, val end: Int)

    private fun readBox(buffer: ByteBuffer, offset: Int, limit: Int): Box {
        val size32 = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
        val type = String(ByteArray(4) { buffer.get(offset + 4 + it) }, Charsets.ISO_8859_1)
        val (size, header) = when (size32) {
            0L -> (limit - offset).toLong() to HEADER_BYTES
            1L -> {
                if (limit - offset < LARGE_HEADER_BYTES) throw Mp4FormatException("truncated large box at $offset")
                buffer.getLong(offset + HEADER_BYTES) to LARGE_HEADER_BYTES
            }
            else -> size32 to HEADER_BYTES
        }
        if (size < header || size > (limit - offset).toLong()) throw Mp4FormatException("box '$type' at $offset has bad size $size")
        return Box(type, offset, offset + header, offset + size.toInt())
    }

    private fun neutralize(buffer: ByteBuffer, box: Box) {
        FREE.toByteArray(Charsets.ISO_8859_1).forEachIndexed { i, b -> buffer.put(box.start + 4 + i, b) }
        for (i in box.bodyStart until box.end) buffer.put(i, 0)
    }

    private fun zeroTimes(buffer: ByteBuffer, box: Box) {
        if (box.end - box.bodyStart < VERSION_FLAGS_BYTES) throw Mp4FormatException("'${box.type}' too short")
        val version = buffer.get(box.bodyStart).toInt() and 0xFF
        val fieldBytes = if (version == 1) 8 else 4
        val timesStart = box.bodyStart + VERSION_FLAGS_BYTES
        if (timesStart + 2 * fieldBytes > box.end) throw Mp4FormatException("'${box.type}' too short for its times")
        for (i in timesStart until timesStart + 2 * fieldBytes) buffer.put(i, 0)
    }
}
