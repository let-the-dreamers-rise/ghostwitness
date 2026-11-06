package xyz.ghostwitness.app.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder

/**
 * AAC-LC 32 kbps mono from the microphone on its own thread. Timestamps come from sample counts on the
 * same clock as the camera frames, so the muxer can compare audio and video times directly.
 */
class AudioEncoder(private val clockNanos: () -> Long, private val listener: Listener) {
    interface Listener {
        fun onAudioFormat(format: MediaFormat)

        fun onAudioSample(bytes: ByteArray, ptsUs: Long)

        fun onAudioError(message: String)
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ captureLoop() }, "gw-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    // The activity checks RECORD_AUDIO before it builds a pipeline.
    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val mic = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuffer, READ_BYTES) * 4)
            record = mic
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "microphone is not available" }
            val encoder = MediaCodec.createEncoderByType(MIME)
            codec = encoder
            encoder.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            mic.startRecording()
            encodeLoop(mic, encoder)
        } catch (e: Exception) {
            if (running) listener.onAudioError("audio: ${e.message}")
        } finally {
            record?.let {
                runCatching { it.stop() }
                it.release()
            }
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    private fun encodeLoop(record: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var firstPtsUs = -1L
        var samplesRead = 0L
        while (running) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buffer = checkNotNull(codec.getInputBuffer(inIndex)) { "no input buffer" }
                buffer.clear()
                val read = record.read(buffer, minOf(buffer.capacity(), READ_BYTES))
                if (read < 0) {
                    listener.onAudioError("microphone read failed: $read")
                    return
                }
                if (firstPtsUs < 0) firstPtsUs = clockNanos() / NANOS_PER_US - samplesToUs(read / BYTES_PER_SAMPLE.toLong())
                codec.queueInputBuffer(inIndex, 0, read, firstPtsUs + samplesToUs(samplesRead), 0)
                samplesRead += read / BYTES_PER_SAMPLE
            }
            drain(codec, info)
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> listener.onAudioFormat(codec.outputFormat)
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        listener.onAudioSample(bytes, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                else -> return
            }
        }
    }

    private fun format(): MediaFormat = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_BYTES)
    }

    private fun samplesToUs(samples: Long): Long = samples * US_PER_SECOND / SAMPLE_RATE

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val SAMPLE_RATE = 44_100
        const val BITRATE = 32_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val READ_BYTES = 2_048
        const val TIMEOUT_US = 10_000L
        const val JOIN_TIMEOUT_MS = 1_000L
        const val NANOS_PER_US = 1_000L
        const val US_PER_SECOND = 1_000_000L
    }
}
