package xyz.ghostwitness.app.mesh

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import xyz.ghostwitness.app.store.SessionStore
import xyz.ghostwitness.core.capture.RateController
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.mesh.ControlCodec
import xyz.ghostwitness.core.mesh.ControlFrame
import xyz.ghostwitness.core.mesh.LinkQuality
import xyz.ghostwitness.core.mesh.MeshCommand
import xyz.ghostwitness.core.mesh.MeshStep
import xyz.ghostwitness.core.mesh.WitnessMesh
import xyz.ghostwitness.core.mesh.WitnessState
import xyz.ghostwitness.core.mesh.shardHashes
import xyz.ghostwitness.core.sched.SegmentStatus
import xyz.ghostwitness.core.session.SegmentAppended
import xyz.ghostwitness.core.session.SessionHeader
import xyz.ghostwitness.core.wire.FrameFormatException
import xyz.ghostwitness.core.wire.ShardHeader
import xyz.ghostwitness.core.recovery.RecoveryRecord
import xyz.ghostwitness.core.recovery.UploadTicket

/** What the record screen shows about the mesh. */
data class MeshStatus(
    val peers: Int,
    val videoPeers: Int,
    val goodputKBps: Int,
    val sealed: Int,
    val recoverableUpTo: Int,
    val fullySpread: Int,
    val okToLosePhone: Boolean,
    val spare: Int,
    /** Fewest distinct holders across finished segments; 1 means a single point of failure. */
    val minHolders: Int,
    val suggestedTier: Tier,
    val lastError: String?,
)

/**
 * Runs [WitnessMesh] on its own thread ("gw-mesh") and turns its commands into Nearby sends. Ticks every
 * 250 ms. Listener calls arrive on the main thread.
 *
 * The mesh sees each holder by its advertised name, not its Nearby endpoint id. Endpoint ids change on
 * every reconnect; counting a returning holder as a new one would overstate how many holders a segment
 * has and so how many losses it tolerates.
 */
class WitnessMeshDriver(
    context: Context,
    private val header: SessionHeader,
    private val store: SessionStore,
    initialTier: Tier,
    ladder: List<Tier>,
    private val listener: Listener,
    private val authorizeUpload: (Int, Int, ByteArray, ByteArray) -> UploadTicket,
) {
    interface Listener {
        fun onMeshStatus(status: MeshStatus)

        fun onTierDecision(tier: Tier)
    }

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("gw-mesh").apply { start() }
    private val handler = Handler(thread.looper)
    private val mesh = WitnessMesh(header, rates = RateController(ladder))
    private var state: WitnessState = mesh.initial(initialTier, SystemClock.elapsedRealtime())
    private var lastError: String? = null
    private var lastStatusMs = 0L
    private var stopped = false
    private val records = mutableMapOf<Int, RecoveryRecord>()
    private val writers = mutableMapOf<String, ByteArray>()

    /** Holder name to its current endpoint, and back. Handler thread only. */
    private var endpointOf: Map<String, String> = emptyMap()
    private var holderOf: Map<String, String> = emptyMap()

    private val events = object : NearbyLink.Events {
        override fun onConnected(endpointId: String, name: String) {
            endpointOf[name]?.let { old -> holderOf = holderOf - old }
            endpointOf = endpointOf + (name to endpointId)
            holderOf = holderOf + (endpointId to name)
            applyStep(mesh.onPeerConnected(state, name, LinkQuality.MEDIUM, now()))
        }

        override fun onDisconnected(endpointId: String) {
            val holder = holderOf[endpointId] ?: return
            holderOf = holderOf - endpointId
            // A holder that already reconnected under a new endpoint stays connected.
            if (endpointOf[holder] != endpointId) return
            endpointOf = endpointOf - holder
            state = mesh.onPeerLost(state, holder)
        }

        override fun onQuality(endpointId: String, quality: LinkQuality) {
            val holder = holderOf[endpointId] ?: return
            state = mesh.onPeerQuality(state, holder, quality, now())
        }

        override fun onBytes(endpointId: String, bytes: ByteArray) {
            val holder = holderOf[endpointId] ?: return
            if (bytes.size == 40 && String(bytes, 0, 4, Charsets.US_ASCII) == "GWI1" && bytes.copyOfRange(4, 20).contentEquals(header.sessionId)) {
                writers[holder] = bytes.copyOfRange(20, 40)
                return
            }
            val frame = try {
                ControlCodec.decode(bytes)
            } catch (e: FrameFormatException) {
                lastError = "bad frame from $holder: ${e.message}"
                return
            }
            if (frame is ControlFrame.Ack) state = mesh.onAck(state, holder, frame.frame, now())
        }

        override fun onFile(endpointId: String, payloadId: Long, bytes: ByteArray) {
            lastError = "unexpected file from $endpointId"
        }

        override fun onLinkError(message: String) {
            lastError = message
        }
    }

    private val link = NearbyLink(context.applicationContext, handler, events)

    fun start() {
        handler.post {
            val shortId = Bytes.toHex(header.sessionId).take(SHORT_ID_CHARS)
            link.advertise("${NearbyLink.WITNESS_PREFIX}-$shortId", NearbyLink.HOLDER_PREFIX)
            tick()
        }
    }

    /** Called on the sealer thread for every sealed segment. */
    fun onSealed(appended: SegmentAppended, record: RecoveryRecord? = null) {
        val hashes = shardHashes(appended.segment.shards)
        val entry = appended.segment.entry
        val root = appended.root
        handler.post {
            if (!stopped) {
                if (record != null) records[entry.index] = record
                applyStep(mesh.onSegment(state, entry, root, hashes))
            }
        }
    }

    fun stop() {
        handler.post {
            stopped = true
            link.stop()
            thread.quitSafely()
        }
    }

    private fun tick() {
        if (stopped) return
        val nowMs = now()
        applyStep(mesh.onTick(state, nowMs))
        if (nowMs - lastStatusMs >= STATUS_INTERVAL_MS) {
            lastStatusMs = nowMs
            val status = status()
            main.post { listener.onMeshStatus(status) }
        }
        handler.postDelayed(::tick, TICK_MS)
    }

    private fun applyStep(step: MeshStep) {
        state = step.state
        step.commands.forEach { command ->
            when (command) {
                is MeshCommand.SendControl -> endpointOf[command.peerId]?.let { link.sendBytes(it, command.frame) }
                is MeshCommand.SendShard -> sendShard(command)
                is MeshCommand.SetTier -> main.post { listener.onTierDecision(command.tier) }
            }
        }
    }

    private fun sendShard(command: MeshCommand.SendShard) {
        val entry = state.entries[command.segmentIndex] ?: return
        val endpoint = endpointOf[command.peerId] ?: return
        val writer = writers[command.peerId] ?: return
        val file = store.shardFile(command.segmentIndex, command.shardIndex)
        val shardLen = file.length().toInt()
        val shardRoot = entry.shardRoots[command.shardIndex]
        // Repeat on every shard/retry: a witness needs its own signed proof even if other entries are absent.
        records[command.segmentIndex]?.let { link.sendBytes(endpoint, it.encode()) }
        val ticket = authorizeUpload(command.segmentIndex, command.shardIndex, writer, state.shardHashes.getValue(command.segmentIndex)[command.shardIndex])
        link.sendBytes(endpoint, ticket.encode())
        link.sendFile(endpoint, file) { payloadId ->
            ShardHeader(
                sessionId = header.sessionId,
                segmentIndex = command.segmentIndex,
                shardIndex = command.shardIndex,
                totalShards = header.totalShards,
                dataShards = header.dataShards,
                ctLen = entry.ctLen,
                tStartMs = entry.tStartMs,
                durMs = entry.durMs,
                shardLen = shardLen,
                shardRoot = shardRoot,
                payloadId = payloadId,
            ).encode()
        }
    }

    private fun status(): MeshStatus {
        val segments = state.scheduler.segments
        val progress = mesh.progress
        return MeshStatus(
            peers = state.links.size,
            videoPeers = state.links.values.count { it.quality.carriesVideo },
            goodputKBps = (state.links.values.sumOf { it.goodputBytesPerSec } / BYTES_PER_KB).toInt(),
            sealed = segments.size,
            recoverableUpTo = progress.recoverableUpTo(state.scheduler),
            fullySpread = segments.count { progress.status(it) == SegmentStatus.FULLY_SPREAD },
            okToLosePhone = progress.okToLosePhone(state.scheduler),
            spare = progress.minimumSpare(state.scheduler),
            minHolders = segments.minOfOrNull { progress.holderCount(it) } ?: 0,
            suggestedTier = state.rate.tier,
            lastError = lastError,
        )
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TICK_MS = 250L
        const val STATUS_INTERVAL_MS = 500L
        const val SHORT_ID_CHARS = 8
        const val BYTES_PER_KB = 1024.0
    }
}
