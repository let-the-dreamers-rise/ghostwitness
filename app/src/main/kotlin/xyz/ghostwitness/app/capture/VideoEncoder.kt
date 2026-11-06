package xyz.ghostwitness.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 encoder fed by a camera surface (spec section 5). Callbacks run on the handler passed in, which is
 * also the muxer's thread, so samples never need copying.
 */
class VideoEncoder(size: Size, initialBitrate: Int, handler: Handler, private val listener: Listener) {
    interface Listener {
        fun onVideoFormat(format: MediaFormat)

        fun onVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)

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
                    listener.onVideoSample(buffer, info)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (running) listener.onVideoFormat(format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (running) listener.onVideoError("video encoder: ${e.diagnosticInfo}")
        }
    }

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, encoderBitrate(initialBitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        }
        codec.setCallback(callback, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
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

    /** Call on the handler thread, after the camera has stopped writing to [inputSurface]. */
    fun stop() {
        val wasRunning = running
        running = false
        if (wasRunning) runCatching { codec.stop() }
        codec.release()
        inputSurface.release()
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_S = 2

        /** Tier A has no video bitrate, but the encoder keeps running at a low rate so it can switch back instantly. */
        private const val MIN_BITRATE = 100_000

        fun encoderBitrate(requested: Int): Int = maxOf(requested, MIN_BITRATE)
    }
}
