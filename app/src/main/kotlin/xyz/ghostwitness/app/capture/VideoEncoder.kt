package xyz.ghostwitness.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import xyz.ghostwitness.core.capture.VideoProfile

/**
 * One H.264 encoder generation fed by the GL scaler (spec section 5): Baseline profile, constant bitrate
 * where the encoder supports it, a keyframe every 2 s, at [profile]'s size and frame rate. A size change
 * makes a new generation. Callbacks run on [handler], which is also the muxer's thread, so samples never
 * need copying.
 */
class VideoEncoder(
    val generation: Int,
    val profile: VideoProfile,
    initialBitrate: Int,
    handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onVideoFormat(generation: Int, format: MediaFormat)

        fun onVideoSample(generation: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo)

        fun onVideoError(message: String)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private var running = false

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!running) return
            try {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val buffer = codec.getOutputBuffer(index)
                if (!isConfig && info.size > 0 && buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    listener.onVideoSample(generation, buffer, info)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (running) listener.onVideoFormat(generation, format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (running) listener.onVideoError("video encoder $generation: ${e.diagnosticInfo}")
        }
    }

    val inputSurface: Surface

    init {
        try {
            codec.setCallback(callback, handler)
            codec.configure(format(initialBitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
        } catch (e: RuntimeException) {
            codec.release()
            throw e
        }
    }

    /** Call on the handler thread. */
    fun start() {
        running = true
        codec.start()
    }

    fun setBitrate(bitsPerSecond: Int) {
        if (running) codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, encoderBitrate(bitsPerSecond)) })
    }

    fun requestSyncFrame() {
        if (running) codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    /** Call on the handler thread, after the GL scaler has stopped drawing into [inputSurface]. */
    fun stop() {
        val wasRunning = running
        running = false
        if (wasRunning) runCatching { codec.stop() }
        codec.release()
        inputSurface.release()
    }

    private fun format(bitrate: Int): MediaFormat {
        val capabilities = codec.codecInfo.getCapabilitiesForType(MIME)
        return MediaFormat.createVideoFormat(MIME, profile.width, profile.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, encoderBitrate(bitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PRIORITY, REALTIME_PRIORITY)
            // Baseline: no B-frames, so every frame is ready to mux the moment it is encoded.
            capabilities.profileLevels
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                .maxOfOrNull { it.level }
                ?.let { level ->
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, level)
                }
            if (capabilities.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL_S = 2
        private const val REALTIME_PRIORITY = 0

        /** Tier A has no video bitrate, but the encoder keeps running at a low rate so it can switch back instantly. */
        const val MIN_BITRATE = 100_000

        fun encoderBitrate(requested: Int): Int = maxOf(requested, MIN_BITRATE)

        /** True when some H.264 encoder on this phone takes [profile]'s size at its frame rate. */
        fun supports(profile: VideoProfile): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && MIME in it.supportedTypes }
                .any { info ->
                    runCatching {
                        info.getCapabilitiesForType(MIME).videoCapabilities
                            .areSizeAndRateSupported(profile.width, profile.height, profile.fps.toDouble())
                    }.getOrDefault(false)
                }
    }
}
