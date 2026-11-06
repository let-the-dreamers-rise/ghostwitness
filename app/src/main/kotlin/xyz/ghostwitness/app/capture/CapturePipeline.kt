package xyz.ghostwitness.app.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import xyz.ghostwitness.app.RecoveryKeyChoice
import xyz.ghostwitness.app.store.SessionStore
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.session.RecordingSession
import xyz.ghostwitness.core.session.SegmentAppended

/** What the record screen shows per sealed segment. */
class SegmentReport(val index: Int, val durMs: Int, val ciphertextBytes: Int, val withVideo: Boolean, val selfCheck: String?)

/**
 * Camera + mic -> 2 s MP4 segments -> encrypt, RS(5,3), commit, sign -> local shard store (spec section 5).
 * Single use: build, [start], [stop]. Threads: "gw-capture" runs camera callbacks, the video encoder and the
 * muxer; "gw-audio" reads the mic; "gw-seal" owns the session state and does all crypto. Listener calls
 * arrive on the main thread.
 *
 * [debugDir], when set, rebuilds every segment from shards 1, 3 and 4 and saves the result there as a
 * playable MP4. That is plaintext on disk, so it exists only to verify the pipeline on a test phone.
 */
class CapturePipeline(
    context: Context,
    private val spec: CameraSpec,
    private val previewSurface: Surface?,
    initialTier: Tier,
    recovery: RecoveryKeyChoice,
    private val debugDir: File?,
    private val listener: Listener,
) {
    interface Listener {
        fun onSegmentSealed(report: SegmentReport)

        fun onPipelineError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("gw-capture").apply { start() }
    private val capture = Handler(captureThread.looper)
    private val sealer: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "gw-seal") }
    private val clockNanos: () -> Long = if (spec.realtimeTimestamps) SystemClock::elapsedRealtimeNanos else System::nanoTime
    private val startWallMs = System.currentTimeMillis()
    private val ptsOriginUs = clockNanos() / NANOS_PER_US

    /** Touched only on the sealer thread once [start] has been called. */
    private var session: RecordingSession = newSession(recovery.publicKey)
    private val store = SessionStore(context.applicationContext.filesDir, session.header)
    val sessionIdHex: String = Bytes.toHex(session.header.sessionId)

    private val videoListener: VideoEncoder.Listener = object : VideoEncoder.Listener {
        override fun onVideoFormat(format: MediaFormat) = muxer.onVideoFormat(format)

        override fun onVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = muxer.onVideoSample(buffer, info)

        override fun onVideoError(message: String) = reportError(message)
    }

    private val audioListener: AudioEncoder.Listener = object : AudioEncoder.Listener {
        override fun onAudioFormat(format: MediaFormat) {
            capture.post { muxer.onAudioFormat(format) }
        }

        override fun onAudioSample(bytes: ByteArray, ptsUs: Long) {
            capture.post { muxer.onAudioSample(bytes, ptsUs) }
        }

        override fun onAudioError(message: String) = reportError(message)
    }

    private val camera: CameraSource = CameraSource(context.applicationContext, capture, ::reportError)
    private val muxer: SegmentMuxer = SegmentMuxer(
        cacheDir = context.cacheDir,
        orientationHint = spec.sensorOrientation,
        initialTier = initialTier,
        requestSync = { video.requestSyncFrame() },
        onSegment = { plain -> sealer.execute { seal(plain) } },
        onError = ::reportError,
    )
    private val video: VideoEncoder = VideoEncoder(spec.size, initialTier.videoBitrate, capture, videoListener)
    private val audio: AudioEncoder = AudioEncoder(clockNanos, audioListener)

    fun start() {
        capture.post {
            try {
                video.start()
                camera.open(spec, listOfNotNull(video.inputSurface, previewSurface))
                audio.start()
            } catch (e: IllegalStateException) {
                reportError("could not start capture: ${e.message}")
            }
        }
    }

    /** Takes effect for video bitrate at once, and for segment layout at the next boundary. */
    fun setTier(tier: Tier) {
        capture.post {
            muxer.setTier(tier)
            video.setBitrate(tier.videoBitrate)
        }
    }

    fun stop() {
        capture.post {
            camera.close()
            audio.stop()
            muxer.finish()
            video.stop()
            sealer.execute { session.close() }
            sealer.shutdown()
            captureThread.quitSafely()
        }
    }

    private fun newSession(recoveryPublicKey: ByteArray): RecordingSession {
        val secrets = SessionSecrets.generate()
        try {
            return RecordingSession.start(secrets, SessionSigner.generate(), recoveryPublicKey, startWallMs)
        } finally {
            secrets.wipe()
        }
    }

    private fun seal(plain: PlainSegment) {
        try {
            val durMs = ((plain.endPtsUs - plain.startPtsUs) / US_PER_MS).coerceIn(0L, MAX_DUR_MS).toInt()
            val tStartMs = startWallMs + (plain.startPtsUs - ptsOriginUs) / US_PER_MS
            val appended = session.append(tStartMs, durMs, plain.bytes)
            session = appended.session
            store.writeSegment(appended.segment, appended.root)
            val check = debugDir?.let { selfCheck(appended, plain.bytes, it) }
            val entry = appended.segment.entry
            val report = SegmentReport(entry.index, durMs, entry.ctLen, plain.withVideo, check)
            main.post { listener.onSegmentSealed(report) }
        } catch (e: Exception) {
            reportError("sealing failed: ${e.message}")
        } finally {
            plain.bytes.fill(0)
        }
    }

    private fun selfCheck(appended: SegmentAppended, plaintext: ByteArray, dir: File): String {
        val shards = appended.segment.shards
        val rebuilt = appended.session.open(appended.segment.entry, mapOf(1 to shards[1], 3 to shards[3], 4 to shards[4]))
        if (!rebuilt.contentEquals(plaintext)) return "MISMATCH at segment ${appended.segment.entry.index}"
        val name = "seg-${appended.segment.entry.index.toString().padStart(5, '0')}.mp4"
        File(dir, name).writeBytes(rebuilt)
        return "ok"
    }

    private fun reportError(message: String) {
        main.post { listener.onPipelineError(message) }
    }

    private companion object {
        const val NANOS_PER_US = 1_000L
        const val US_PER_MS = 1_000L
        const val MAX_DUR_MS = 65_535L
    }
}
