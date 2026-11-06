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
import xyz.ghostwitness.app.capture.gl.CameraRenderer
import xyz.ghostwitness.app.mesh.MeshStatus
import xyz.ghostwitness.app.mesh.WitnessMeshDriver
import xyz.ghostwitness.app.store.SessionStore
import xyz.ghostwitness.app.online.OnlineSettings
import xyz.ghostwitness.core.recovery.RecoveryRecord
import xyz.ghostwitness.core.merkle.Merkle
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.media.Mp4FormatException
import xyz.ghostwitness.core.media.Mp4Scrubber
import xyz.ghostwitness.core.session.RecordingSession
import xyz.ghostwitness.core.session.SegmentAppended

/** What the record screen shows per sealed segment. */
class SegmentReport(val index: Int, val durMs: Int, val ciphertextBytes: Int, val withVideo: Boolean, val selfCheck: String?)

/**
 * Camera -> GL scaler -> H.264 at the tier's size and frame rate, + mic -> 2 s MP4 segments -> metadata
 * scrub -> encrypt, RS(5,3), commit, sign -> local shard store (spec section 5). Sealed segments go
 * straight to the mesh ([WitnessMeshDriver]), which keeps spreading after [stopCapture] until [shutdown].
 * Single use. Threads: "gw-capture" runs camera callbacks, the video encoders and the muxer; "gw-gl" draws
 * camera frames into the encoders; "gw-audio" reads the mic; "gw-seal" owns the session state and does
 * all crypto. Listener calls arrive on the main thread.
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

        fun onMeshStatus(status: MeshStatus)

        /** The capture tier changed; [automatic] is true when the rate controller chose it. */
        fun onTierApplied(tier: Tier, automatic: Boolean)
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
    private val owner = OnlineSettings(context).owner.takeIf { it.isNotBlank() }?.let { Bytes.fromHex(it.removePrefix("0x")) }
    val sessionIdHex: String = Bytes.toHex(session.header.sessionId)
    val sessionCode: String = sessionIdHex + ":" + Bytes.toHex(Bytes.sha256(session.header.sessionPublicKey))

    private val videoListener: VideoEncoder.Listener = object : VideoEncoder.Listener {
        override fun onVideoFormat(generation: Int, format: MediaFormat) = muxer.onVideoFormat(generation, format)

        override fun onVideoSample(generation: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
            muxer.onVideoSample(generation, buffer, info)

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

    private val startTier: Tier = spec.tiers.firstOrNull { it.ordinal >= initialTier.ordinal } ?: Tier.A
    private val camera: CameraSource = CameraSource(context.applicationContext, capture, ::reportError)
    private val renderer = CameraRenderer(spec.streamSize, ::reportError)
    private val muxer: SegmentMuxer = SegmentMuxer(
        cacheDir = context.cacheDir,
        orientationHint = spec.sensorOrientation,
        initialTier = startTier,
        requestSync = { video.requestSyncFrame() },
        onOpened = { video.onOpened(it) },
        onSegment = { plain -> sealer.execute { seal(plain) } },
        onError = ::reportError,
    )
    private val video = VideoChain(capture, renderer, videoListener, ::reportError)
    private val audio: AudioEncoder = AudioEncoder(clockNanos, audioListener)

    /** Main thread only. False once the user picks a tier by hand. */
    private var autoTier = true
    /** Written on the main thread, read on the capture thread too. */
    @Volatile
    private var captureStopped = false

    private val meshListener: WitnessMeshDriver.Listener = object : WitnessMeshDriver.Listener {
        override fun onMeshStatus(status: MeshStatus) = listener.onMeshStatus(status)

        override fun onTierDecision(tier: Tier) {
            if (!autoTier || captureStopped) return
            applyTier(tier)
            listener.onTierApplied(tier, automatic = true)
        }
    }
    private val meshDriver: WitnessMeshDriver =
        WitnessMeshDriver(context, session.header, store, startTier, spec.tiers, meshListener, session::authorizeUpload)

    fun start() {
        meshDriver.start()
        capture.post {
            try {
                renderer.start { cameraSurface ->
                    capture.post { if (!captureStopped) camera.open(spec, listOfNotNull(cameraSurface, previewSurface)) }
                }
                val first = startTier.video ?: spec.tiers.lastOrNull { it.hasVideo }?.video
                    ?: throw IllegalStateException("this phone offers no video tier")
                video.begin(first, startTier.videoBitrate)
                audio.start()
            } catch (e: IllegalStateException) {
                reportError("could not start capture: ${e.message}")
            }
        }
    }

    /** Main thread. Pins the tier; the rate controller's choices are ignored until [setAutoTier]. */
    fun setManualTier(tier: Tier) {
        autoTier = false
        applyTier(tier)
        listener.onTierApplied(tier, automatic = false)
    }

    /** Main thread. Lets the rate controller pick the tier again from its next window. */
    fun setAutoTier() {
        autoTier = true
    }

    /** Bitrate changes at once; size, frame rate and audio-only take effect at the next boundary. */
    private fun applyTier(tier: Tier) {
        val supported = spec.tiers.firstOrNull { it.ordinal >= tier.ordinal } ?: Tier.A
        capture.post {
            if (captureStopped) return@post
            muxer.setTier(supported)
            video.onTier(supported)
        }
    }

    /** Main thread. Stops recording and wipes K; the mesh keeps spreading what was sealed. */
    fun stopCapture() {
        if (captureStopped) return
        captureStopped = true
        capture.post {
            camera.close()
            audio.stop()
            muxer.finish()
            video.stop {
                renderer.release { capture.post { captureThread.quitSafely() } }
                sealer.execute { session.close() }
                sealer.shutdown()
            }
        }
    }

    /** Main thread. Stops capture if needed, then the mesh. */
    fun shutdown() {
        stopCapture()
        meshDriver.stop()
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
        val clean = scrubbed(plain.bytes)
        try {
            val durMs = ((plain.endPtsUs - plain.startPtsUs) / US_PER_MS).coerceIn(0L, MAX_DUR_MS).toInt()
            val tStartMs = startWallMs + (plain.startPtsUs - ptsOriginUs) / US_PER_MS
            val appended = session.append(tStartMs, durMs, clean)
            session = appended.session
            store.writeSegment(appended.segment, appended.root)
            val record = owner?.let { RecoveryRecord(session.header, it, appended.segment.entry, appended.root,
                Merkle.proof(appended.segment.entry.index, session.entries.map { entry -> entry.leafHash() })) }
            meshDriver.onSealed(appended, record)
            val check = debugDir?.let { selfCheck(appended, clean, it) }
            val entry = appended.segment.entry
            val report = SegmentReport(entry.index, durMs, entry.ctLen, plain.withVideo, check)
            main.post { listener.onSegmentSealed(report) }
        } catch (e: Exception) {
            reportError("sealing failed: ${e.message}")
        } finally {
            plain.bytes.fill(0)
            clean.fill(0)
        }
    }

    /**
     * Location, OS strings and creation times out before encryption. If the MP4 cannot be parsed the
     * segment is still sealed as it is (losing evidence is worse), and the error is shown.
     */
    private fun scrubbed(mp4: ByteArray): ByteArray = try {
        Mp4Scrubber.scrub(mp4)
    } catch (e: Mp4FormatException) {
        reportError("metadata not removed: ${e.message}")
        mp4.copyOf()
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
