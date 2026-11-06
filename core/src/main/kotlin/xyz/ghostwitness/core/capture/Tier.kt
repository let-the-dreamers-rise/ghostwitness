package xyz.ghostwitness.core.capture

private const val AUDIO_BPS = 32_000
private const val BITS_PER_BYTE = 8.0

/**
 * Encoded picture size and frame rate. Sizes are multiples of 16 (H.264 macroblocks), so 480p is 848 wide
 * rather than 854 and 240p is 432 rather than 426; both stay within 1% of 16:9.
 */
data class VideoProfile(val width: Int, val height: Int, val fps: Int) {
    init {
        require(width > 0 && height > 0 && fps > 0) { "video profile must be positive" }
    }

    fun sameSize(other: VideoProfile?): Boolean = other != null && other.width == width && other.height == height
}

private val P720 = VideoProfile(1280, 720, 30)
private val P480 = VideoProfile(848, 480, 30)
private val P240 = VideoProfile(432, 240, 24)

/**
 * Capture tiers from spec section 5, best first. Each sets resolution, frame rate and bitrate, because
 * bitrate alone would leave a phone encoding 1080p blocks at 1.5 Mbps. Bitrate changes take effect at
 * once; frame rate and size change only at a segment boundary, on a fresh keyframe. Tier [A] keeps the
 * video encoder running but writes audio-only segments.
 */
enum class Tier(val video: VideoProfile?, val videoBitrate: Int, val audioBitrate: Int = AUDIO_BPS) {
    V2(P720, 1_500_000),
    V1(P480, 800_000),
    V1_24(P480.copy(fps = 24), 600_000),
    V0(P240, 250_000),
    V0_15(P240.copy(fps = 15), 200_000),
    V0_10(P240.copy(fps = 10), 150_000),
    A(null, 0),
    ;

    val hasVideo: Boolean get() = video != null

    /** Bytes per second of the data shards alone, which is 1.0x the segment size. */
    val dataBytesPerSecond: Double get() = (videoBitrate + audioBitrate) / BITS_PER_BYTE

    companion object {
        /** Every tier whose picture fits in [maxWidth] x [maxHeight], best first. Tier [A] always fits. */
        fun fitting(maxWidth: Int, maxHeight: Int): List<Tier> =
            entries.filter { tier -> tier.video.let { it == null || (it.width <= maxWidth && it.height <= maxHeight) } }
    }
}

/**
 * The second input to rate control (spec section 6.4): the best tier the number of connected holders
 * allows. Fewer holders means each must take more shards per segment, so segments must shrink to keep
 * up. Index = holder count; counts past the end use the last entry.
 */
class HolderCaps(private val caps: List<Tier> = DEFAULT) {
    init {
        require(caps.isNotEmpty()) { "need at least one cap" }
        require(caps.zipWithNext().all { (fewer, more) -> more.ordinal <= fewer.ordinal }) {
            "more holders must never lower the cap"
        }
    }

    fun capFor(holders: Int): Tier = caps[holders.coerceIn(0, caps.lastIndex)]

    companion object {
        /** 0 holders: audio only (LOCAL_ONLY); 1: 240p10; 2: 240p15; 3: 480p24; 4: 480p30; 5+: 720p30. */
        val DEFAULT = listOf(Tier.A, Tier.V0_10, Tier.V0_15, Tier.V1_24, Tier.V1, Tier.V2)
    }
}
