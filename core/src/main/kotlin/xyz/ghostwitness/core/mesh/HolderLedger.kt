package xyz.ghostwitness.core.mesh

import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.manifest.SignedRoot
import xyz.ghostwitness.core.merkle.Merkle
import xyz.ghostwitness.core.session.SessionHeader
import xyz.ghostwitness.core.wire.AckEntry
import xyz.ghostwitness.core.wire.AckFrame
import xyz.ghostwitness.core.wire.ShardHeader

/** What the holder app must persist. Each action is applied before the next ACK flush. */
sealed interface HolderAction {
    class StoreSession(val header: SessionHeader) : HolderAction

    class StoreEntry(val entry: ManifestEntry) : HolderAction

    class StoreRoot(val root: SignedRoot) : HolderAction

    class StoreShard(val segmentIndex: Int, val shardIndex: Int, shard: ByteArray) : HolderAction {
        private val bytes = shard.copyOf()
        val shard: ByteArray get() = bytes.copyOf()
    }

    data class Rejected(val reason: String) : HolderAction
}

data class HolderState(
    val header: SessionHeader? = null,
    val root: SignedRoot? = null,
    val entries: Map<Int, ManifestEntry> = emptyMap(),
    val pendingHeaders: Map<Long, ShardHeader> = emptyMap(),
    val pendingBodies: Map<Long, ByteArray> = emptyMap(),
    val held: Set<Pair<Int, Int>> = emptySet(),
    val pendingAcks: List<AckEntry> = emptyList(),
    val lastFlushMs: Long = 0,
)

data class HolderStep(val state: HolderState, val actions: List<HolderAction>)

/**
 * A holder phone's side of the mesh. Pure. A shard header (BYTES) and its body (FILE) can arrive in either
 * order; they are matched by payload id. A shard is stored and ACKed only if its size and block Merkle
 * root match the header and, when the entry is known, the signed manifest. Roots are kept only if the
 * session key signed them. ACKs are batched and flushed every [ackIntervalMs].
 */
class HolderLedger(private val ackIntervalMs: Long = 300) {
    fun onControl(state: HolderState, frame: ControlFrame): HolderStep = when (frame) {
        is ControlFrame.Session -> onSession(state, frame.header)
        is ControlFrame.Root -> onRoot(state, frame.root)
        is ControlFrame.Entry -> onEntry(state, frame.frame)
        is ControlFrame.Shard -> onShardHeader(state, frame.header)
        is ControlFrame.Ack -> HolderStep(state, emptyList())
    }

    fun onBody(state: HolderState, payloadId: Long, body: ByteArray): HolderStep =
        match(state.copy(pendingBodies = state.pendingBodies + (payloadId to body.copyOf())), payloadId)

    /** Returns an ACK frame to send when one is due. */
    fun onTick(state: HolderState, nowMs: Long): Pair<HolderState, AckFrame?> {
        val header = state.header
        if (header == null || state.pendingAcks.isEmpty() || nowMs - state.lastFlushMs < ackIntervalMs) return state to null
        val batch = state.pendingAcks.take(AckFrame.MAX_ENTRIES)
        val next = state.copy(pendingAcks = state.pendingAcks.drop(batch.size), lastFlushMs = nowMs)
        return next to AckFrame(header.sessionId, batch)
    }

    private fun onSession(state: HolderState, header: SessionHeader): HolderStep {
        val current = state.header
        if (current != null && current.sessionId.contentEquals(header.sessionId)) return HolderStep(state, emptyList())
        return HolderStep(HolderState(header = header, lastFlushMs = state.lastFlushMs), listOf(HolderAction.StoreSession(header)))
    }

    private fun onRoot(state: HolderState, root: SignedRoot): HolderStep {
        val header = state.header ?: return rejected(state, "root before session header")
        if (!root.sessionId.contentEquals(header.sessionId)) return rejected(state, "root for another session")
        if (!root.verify(header.sessionPublicKey)) return rejected(state, "root signature invalid")
        val current = state.root
        if (current != null && current.uptoIndex >= root.uptoIndex) return HolderStep(state, emptyList())
        return HolderStep(state.copy(root = root), listOf(HolderAction.StoreRoot(root)))
    }

    private fun onEntry(state: HolderState, frame: EntryFrame): HolderStep {
        val header = state.header ?: return rejected(state, "entry before session header")
        if (!frame.sessionId.contentEquals(header.sessionId)) return rejected(state, "entry for another session")
        if (frame.entry.index in state.entries) return HolderStep(state, emptyList())
        return HolderStep(state.copy(entries = state.entries + (frame.entry.index to frame.entry)), listOf(HolderAction.StoreEntry(frame.entry)))
    }

    private fun onShardHeader(state: HolderState, shard: ShardHeader): HolderStep {
        val header = state.header ?: return rejected(state, "shard before session header")
        if (!shard.sessionId.contentEquals(header.sessionId)) return rejected(state, "shard for another session")
        return match(state.copy(pendingHeaders = state.pendingHeaders + (shard.payloadId to shard)), shard.payloadId)
    }

    private fun match(state: HolderState, payloadId: Long): HolderStep {
        val shard = state.pendingHeaders[payloadId] ?: return HolderStep(state, emptyList())
        val body = state.pendingBodies[payloadId] ?: return HolderStep(state, emptyList())
        val cleared = state.copy(pendingHeaders = state.pendingHeaders - payloadId, pendingBodies = state.pendingBodies - payloadId)
        val problem = verify(state, shard, body)
        if (problem != null) return rejected(cleared, "segment ${shard.segmentIndex} shard ${shard.shardIndex}: $problem")
        val ack = AckEntry(shard.segmentIndex, shard.shardIndex, Bytes.sha256(body))
        val stored = cleared.copy(held = cleared.held + (shard.segmentIndex to shard.shardIndex), pendingAcks = cleared.pendingAcks + ack)
        return HolderStep(stored, listOf(HolderAction.StoreShard(shard.segmentIndex, shard.shardIndex, body)))
    }

    private fun verify(state: HolderState, shard: ShardHeader, body: ByteArray): String? {
        if (body.size != shard.shardLen) return "size ${body.size} != ${shard.shardLen}"
        if (!Merkle.blockRoot(body).contentEquals(shard.shardRoot)) return "block root mismatch"
        val entry = state.entries[shard.segmentIndex] ?: return null
        return if (entry.shardRootMatches(shard.shardIndex, body)) null else "not the shard in the signed manifest"
    }

    private fun rejected(state: HolderState, reason: String) = HolderStep(state, listOf(HolderAction.Rejected(reason)))
}
