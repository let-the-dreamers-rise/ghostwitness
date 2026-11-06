package xyz.ghostwitness.app.holder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.security.SecureRandom
import xyz.ghostwitness.app.mesh.NearbyLink
import xyz.ghostwitness.app.store.HoldingStore
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.mesh.ControlCodec
import xyz.ghostwitness.core.mesh.HolderAction
import xyz.ghostwitness.core.mesh.HolderLedger
import xyz.ghostwitness.core.mesh.HolderState
import xyz.ghostwitness.core.mesh.HolderStep
import xyz.ghostwitness.core.mesh.LinkQuality
import xyz.ghostwitness.core.wire.FrameFormatException
import xyz.ghostwitness.app.online.OnlineStore
import xyz.ghostwitness.app.online.UploadService
import xyz.ghostwitness.core.recovery.RecoveryRecord
import xyz.ghostwitness.core.recovery.UploadTicket
import xyz.ghostwitness.core.recovery.SessionOwnership
import xyz.ghostwitness.app.online.OnlineSettings
import java.io.File

data class HolderStatus(
    val connected: Boolean,
    val quality: LinkQuality?,
    val session: String?,
    val shardsHeld: Int,
    val segmentsSeen: Int,
    val rootUpto: Int?,
    val rejected: Int,
    val lastProblem: String?,
)

/**
 * A holder phone: finds a recording phone over Nearby, verifies and stores the shards it is sent, and
 * ACKs them in batches. Runs [HolderLedger] on its own thread ("gw-holder"); listener calls arrive on
 * the main thread.
 */
class HolderNode(context: Context, private val listener: Listener) {
    private val appContext = context.applicationContext
    interface Listener {
        fun onHolderStatus(status: HolderStatus)
    }

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("gw-holder").apply { start() }
    private val handler = Handler(thread.looper)
    private val ledger = HolderLedger()
    private val store = HoldingStore(context.applicationContext.filesDir)
    /** Random per holder run: the witness counts holders by this tag, so it must not collide or repeat. */
    private val name = "${NearbyLink.HOLDER_PREFIX}-${Bytes.toHex(ByteArray(TAG_BYTES).also { SecureRandom().nextBytes(it) })}"
    private var state = HolderState()
    private var witness: String? = null
    private var quality: LinkQuality? = null
    private var rejected = 0
    private var lastProblem: String? = null
    private var lastStatusMs = 0L
    private var stopped = false

    private val events = object : NearbyLink.Events {
        override fun onConnected(endpointId: String, name: String) {
            witness = endpointId
            quality = LinkQuality.LOW
        }

        override fun onDisconnected(endpointId: String) {
            if (witness == endpointId) {
                witness = null
                quality = null
            }
        }

        override fun onQuality(endpointId: String, quality: LinkQuality) {
            if (witness == endpointId) this@HolderNode.quality = quality
        }

        override fun onBytes(endpointId: String, bytes: ByteArray) {
            if (endpointId != witness) return
            if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "GWO1") {
                try {
                    val proof = SessionOwnership.decode(bytes)
                    require(state.header?.encode()?.contentEquals(proof.header.encode()) == true)
                    OnlineStore(appContext.filesDir).saveOwnership(proof)
                    UploadService.schedule(appContext)
                } catch (_: Exception) { rejected++; lastProblem = "Could not validate wallet ownership" }
                return
            }
            if (bytes.size == 141 && String(bytes, 0, 3, Charsets.US_ASCII) == "GWT") {
                try {
                    val ticket = UploadTicket.decode(bytes)
                    require(ticket.uploader.contentEquals(OnlineSettings(appContext).writer().address))
                    val sid = Bytes.toHex(ticket.sessionId)
                    require(state.header?.sessionId?.contentEquals(ticket.sessionId) == true)
                    OnlineStore.atomic(File(appContext.filesDir, "holdings/$sid/tickets/${ticket.index}-${ticket.shard}.bin"), ticket.encode())
                    UploadService.schedule(appContext)
                } catch (_: Exception) { rejected++; lastProblem = "Could not save upload assignment" }
                return
            }
            if (bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "GWP") {
                try {
                    val record = RecoveryRecord.decode(bytes)
                    val header = state.header ?: throw IllegalArgumentException("Session header required")
                    require(record.header.encode().contentEquals(header.encode())) { "Wrong session proof" }
                    OnlineStore(appContext.filesDir).saveRecord(record)
                    UploadService.schedule(appContext)
                } catch (_: Exception) { rejected++; lastProblem = "Could not validate or save segment proof" }
                return
            }
            val frame = try {
                ControlCodec.decode(bytes)
            } catch (e: FrameFormatException) {
                rejected++
                lastProblem = "bad frame: ${e.message}"
                return
            }
            applyStep(ledger.onControl(state, frame))
            if (frame is xyz.ghostwitness.core.mesh.ControlFrame.Session) {
                link.sendBytes(endpointId, Bytes.concat("GWI1".toByteArray(), frame.header.sessionId, OnlineSettings(appContext).writer().address))
            }
        }

        override fun onFile(endpointId: String, payloadId: Long, bytes: ByteArray) {
            if (endpointId == witness) applyStep(ledger.onBody(state, payloadId, bytes))
        }

        override fun onLinkError(message: String) {
            lastProblem = message
        }
    }

    private val link: NearbyLink = NearbyLink(context.applicationContext, handler, events)

    fun start() {
        handler.post {
            link.discover(name, NearbyLink.WITNESS_PREFIX)
            tick()
        }
    }

    fun stop() {
        handler.post {
            stopped = true
            link.stop()
            thread.quitSafely()
        }
    }

    /** Persists each action; a shard that fails to store is taken back out so it is never ACKed. */
    private fun applyStep(step: HolderStep) {
        state = step.actions.fold(step.state) { acc, action ->
            when (action) {
                is HolderAction.Rejected -> {
                    rejected++
                    lastProblem = action.reason
                    acc
                }
                else -> try {
                    store.persist(action, acc.header)
                    if (action is HolderAction.StoreShard) UploadService.schedule(appContext)
                    acc
                } catch (e: IOException) {
                    lastProblem = "storage: ${e.message}"
                    if (action is HolderAction.StoreShard) withdraw(acc, action) else acc
                }
            }
        }
    }

    private fun withdraw(state: HolderState, action: HolderAction.StoreShard): HolderState {
        val key = action.segmentIndex to action.shardIndex
        return state.copy(
            held = state.held - key,
            pendingAcks = state.pendingAcks.filterNot { it.segmentIndex to it.shardIndex == key },
        )
    }

    private fun tick() {
        if (stopped) return
        val nowMs = SystemClock.elapsedRealtime()
        val (next, frame) = ledger.onTick(state, nowMs)
        state = next
        val target = witness
        if (frame != null) {
            if (target != null) link.sendBytes(target, frame.encode()) else lastProblem = "ACK dropped while disconnected"
        }
        if (nowMs - lastStatusMs >= STATUS_INTERVAL_MS) {
            lastStatusMs = nowMs
            val status = HolderStatus(
                connected = target != null,
                quality = quality,
                session = state.header?.let { Bytes.toHex(it.sessionId).take(SHORT_ID_CHARS) },
                shardsHeld = state.held.size,
                segmentsSeen = state.held.map { it.first }.toSet().size,
                rootUpto = state.root?.uptoIndex,
                rejected = rejected,
                lastProblem = lastProblem,
            )
            main.post { listener.onHolderStatus(status) }
        }
        handler.postDelayed(::tick, TICK_MS)
    }

    private companion object {
        const val TICK_MS = 100L
        const val STATUS_INTERVAL_MS = 500L
        const val SHORT_ID_CHARS = 8
        const val TAG_BYTES = 8
    }
}
