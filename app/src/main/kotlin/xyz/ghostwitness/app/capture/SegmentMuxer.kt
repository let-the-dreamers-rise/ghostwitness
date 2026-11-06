package xyz.ghostwitness.app.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import xyz.ghostwitness.core.capture.MuxAction
import xyz.ghostwitness.core.capture.PlannerState
import xyz.ghostwitness.core.capture.Sample
import xyz.ghostwitness.core.capture.SegmentPlanner
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.capture.Track

/** One finished plaintext MP4 segment. [bytes] is owned by the receiver, which should wipe it after sealing. */
class PlainSegment(val startPtsUs: Long, val endPtsUs: Long, val withVideo: Boolean, val bytes: ByteArray)

/**
 * Applies [SegmentPlanner] decisions with MediaMuxer. Not thread-safe: every method must run on the capture
 * thread, which is also where the video encoder delivers samples.
 */
class SegmentMuxer(
    private val cacheDir: File,
    private val orientationHint: Int,
    initialTier: Tier,
    private val requestSync: () -> Unit,
    private val onSegment: (PlainSegment) -> Unit,
    private val onError: (String) -> Unit,
) {
    private class OpenFile(val muxer: MediaMuxer, val sink: SegmentSink, val videoTrack: Int, val audioTrack: Int)

    private val planner = SegmentPlanner()
    private var state = PlannerState(initialTier)
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var current: OpenFile? = null
    private var finished = false

    fun onVideoFormat(format: MediaFormat) {
        videoFormat = format
        state = planner.onFormat(state, Track.VIDEO)
    }

    fun onAudioFormat(format: MediaFormat) {
        audioFormat = format
        state = planner.onFormat(state, Track.AUDIO)
    }

    fun setTier(tier: Tier) {
        state = planner.setTier(state, tier)
    }

    fun onVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        handle(Sample(Track.VIDEO, info.presentationTimeUs, isKey), buffer, info)
    }

    fun onAudioSample(bytes: ByteArray, ptsUs: Long) {
        val info = MediaCodec.BufferInfo().apply { set(0, bytes.size, ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
        handle(Sample(Track.AUDIO, ptsUs, true), ByteBuffer.wrap(bytes), info)
    }

    /** Closes the last segment. Samples that arrive afterwards are ignored. */
    fun finish() {
        if (finished) return
        val step = planner.finish(state)
        state = step.state
        step.actions.forEach { perform(it, null, null, null) }
        finished = true
    }

    private fun handle(sample: Sample, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (finished) return
        val step = planner.onSample(state, sample)
        state = step.state
        step.actions.forEach { perform(it, sample.track, buffer, info) }
    }

    private fun perform(action: MuxAction, track: Track?, buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
        when (action) {
            is MuxAction.Open -> current = openFile(action)
            is MuxAction.Close -> closeFile(action)
            MuxAction.Write -> if (track != null && buffer != null && info != null) write(track, buffer, info)
            MuxAction.RequestSync -> requestSync()
            MuxAction.Drop -> Unit
        }
    }

    private fun openFile(action: MuxAction.Open): OpenFile? {
        val sink = SegmentSinks.create(cacheDir)
        return try {
            val muxer = MediaMuxer(sink.fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationHint)
            val videoTrack = if (action.withVideo) muxer.addTrack(checkNotNull(videoFormat)) else NO_TRACK
            val audioTrack = muxer.addTrack(checkNotNull(audioFormat))
            muxer.start()
            OpenFile(muxer, sink, videoTrack, audioTrack)
        } catch (e: Exception) {
            sink.close()
            onError("could not start segment ${action.fileIndex}: ${e.message}")
            null
        }
    }

    private fun write(track: Track, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val file = current ?: return
        val trackIndex = if (track == Track.VIDEO) file.videoTrack else file.audioTrack
        if (trackIndex == NO_TRACK) return
        try {
            file.muxer.writeSampleData(trackIndex, buffer, info)
        } catch (e: IllegalStateException) {
            abandon(file, "write failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            abandon(file, "write failed: ${e.message}")
        }
    }

    private fun closeFile(action: MuxAction.Close) {
        val file = current ?: return
        current = null
        try {
            file.muxer.stop()
            onSegment(PlainSegment(action.startPtsUs, action.endPtsUs, action.withVideo, file.sink.readAll()))
        } catch (e: Exception) {
            onError("segment ${action.fileIndex} dropped: ${e.message}")
        } finally {
            runCatching { file.muxer.release() }
            file.sink.close()
        }
    }

    private fun abandon(file: OpenFile, reason: String) {
        if (current === file) current = null
        runCatching { file.muxer.release() }
        file.sink.close()
        onError(reason)
    }

    private companion object {
        const val NO_TRACK = -1
    }
}
