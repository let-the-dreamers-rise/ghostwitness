package xyz.ghostwitness.core.capture

private const val AUDIO_BPS = 32_000
private const val BITS_PER_BYTE = 8.0

/**
 * Capture tiers from spec section 5, best first. Resolution is fixed for the whole session, so moving
 * between video tiers is only a bitrate change and never restarts the encoder (no gap). Tier [A] keeps the
 * video encoder running but writes audio-only segments.
 */
enum class Tier(val videoBitrate: Int, val audioBitrate: Int) {
    V2(1_500_000, AUDIO_BPS),
    V1(800_000, AUDIO_BPS),
    V0(250_000, AUDIO_BPS),
    A(0, AUDIO_BPS),
    ;

    val hasVideo: Boolean get() = videoBitrate > 0

    /** Bytes per second of the data shards alone, which is 1.0x the segment size. */
    val dataBytesPerSecond: Double get() = (videoBitrate + audioBitrate) / BITS_PER_BYTE
}
