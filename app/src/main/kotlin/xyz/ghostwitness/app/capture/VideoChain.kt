package xyz.ghostwitness.app.capture

import android.os.Handler
import xyz.ghostwitness.app.capture.gl.CameraRenderer
import xyz.ghostwitness.core.capture.MuxAction
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.capture.VideoProfile

/**
 * Owns the video encoder generations behind the GL scaler (spec section 5).
 *
 * - Same-size tier change: bitrate changes at once on the encoder the open segment uses; frame rate
 *   changes when the next segment opens.
 * - Size change: a new encoder starts next to the old one. The planner cuts over to it on its first
 *   keyframe after the 2 s mark, and every older encoder is then stopped.
 * - Tier A: the newest encoder keeps running at [VideoEncoder.MIN_BITRATE] and [AUDIO_ONLY_FPS] so video
 *   can come back on the next boundary.
 *
 * Capture thread only.
 */
class VideoChain(
    private val capture: Handler,
    private val renderer: CameraRenderer,
    private val listener: VideoEncoder.Listener,
    private val onError: (String) -> Unit,
) {
    private var encoders: Map<Int, VideoEncoder> = emptyMap()
    private var newest = -1
    private var openGeneration: Int? = null

    /** Starts the first encoder. Call after [CameraRenderer.start]. */
    fun begin(profile: VideoProfile, bitrate: Int) {
        startGeneration(profile, bitrate)
        check(encoders.isNotEmpty()) { "no video encoder could start at ${profile.width}x${profile.height}" }
    }

    fun onTier(tier: Tier) {
        val newestEncoder = encoders[newest] ?: return
        val profile = tier.video
        if (profile != null && !profile.sameSize(newestEncoder.profile)) {
            // A generation that is started but not yet recorded from stays alive until a newer segment
            // opens: the planner may still cut over to it if the newest one's format is not known yet.
            startGeneration(profile, tier.videoBitrate)
            return
        }
        openGeneration?.let { encoders[it] }?.setBitrate(tier.videoBitrate)
    }

    /** A segment opened (see [MuxAction.Open]). */
    fun onOpened(action: MuxAction.Open) {
        val generation = action.videoGeneration
        val profile = action.tier.video
        if (generation != null && profile != null) {
            encoders[generation]?.setBitrate(action.tier.videoBitrate)
            renderer.setFps(generation, profile.fps)
        } else {
            encoders[newest]?.setBitrate(VideoEncoder.MIN_BITRATE)
            renderer.setFps(newest, AUDIO_ONLY_FPS)
        }
        openGeneration = generation
        val keep = generation ?: newest
        encoders.keys.filter { it < keep }.forEach(::retire)
    }

    /** Sync frames are always asked of the newest encoder, which is the one the next segment starts on. */
    fun requestSyncFrame() {
        encoders[newest]?.requestSyncFrame()
    }

    /** Detaches and stops every encoder; [then] runs on the capture thread afterwards. */
    fun stop(then: () -> Unit) {
        val all = encoders
        encoders = emptyMap()
        if (all.isEmpty()) {
            then()
            return
        }
        var remaining = all.size
        all.forEach { (generation, encoder) ->
            renderer.removeTarget(generation) {
                capture.post {
                    encoder.stop()
                    remaining -= 1
                    if (remaining == 0) then()
                }
            }
        }
    }

    private fun startGeneration(profile: VideoProfile, bitrate: Int) {
        val generation = newest + 1
        val encoder = try {
            VideoEncoder(generation, profile, bitrate, capture, listener).also { it.start() }
        } catch (e: RuntimeException) {
            onError("could not start a ${profile.width}x${profile.height} encoder: ${e.message}")
            return
        }
        encoders = encoders + (generation to encoder)
        newest = generation
        renderer.addTarget(generation, encoder.inputSurface, profile)
    }

    private fun retire(generation: Int) {
        val encoder = encoders[generation] ?: return
        encoders = encoders - generation
        renderer.removeTarget(generation) { capture.post { encoder.stop() } }
    }

    private companion object {
        const val AUDIO_ONLY_FPS = 5
    }
}
