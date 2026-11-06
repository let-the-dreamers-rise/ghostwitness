package xyz.ghostwitness.core.mesh

import xyz.ghostwitness.core.capture.RateController
import xyz.ghostwitness.core.capture.RateState
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.manifest.SignedRoot
import xyz.ghostwitness.core.rs.ShardCodec
import xyz.ghostwitness.core.sched.Progress
import xyz.ghostwitness.core.sched.Scheduler
import xyz.ghostwitness.core.sched.SchedulerConfig
import xyz.ghostwitness.core.sched.SchedulerState
import xyz.ghostwitness.core.sched.SegmentStatus
import xyz.ghostwitness.core.session.SessionHeader
import xyz.ghostwitness.core.wire.AckEntry
import xyz.ghostwitness.core.wire.AckFrame

/** Link quality as Nearby reports it after the bandwidth upgrade. LOW is Bluetooth only. */
enum class LinkQuality(val priorBytesPerSec: Double) {
    LOW(20_000.0),
    MEDIUM(150_000.0),
    HIGH(600_000.0),
    ;

    val carriesVideo: Boolean get() = this != LOW
}

data class PeerLink(
    val quality: LinkQuality,
    val goodputBytesPerSec: Double,
    val samples: Int = 0,
    val lastAckMs: Long? = null,
)

sealed interface MeshCommand {
    class SendControl(val peerId: String, frame: ByteArray) : MeshCommand {
        private val bytes = frame.copyOf()
        val frame: ByteArray get() = bytes.copyOf()
    }

    data class SendShard(val peerId: String, val segmentIndex: Int, val shardIndex: Int) : MeshCommand

    data class SetTier(val tier: Tier) : MeshCommand
}

data class MeshConfig(
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val ewmaAlpha: Double = 0.3,
    val rateWindowMs: Long = 2_000,
    val minSampleMs: Long = 50,
)

data class WitnessState(
    val rate: RateState,
    val windowStartMs: Long,
    val scheduler: SchedulerState = SchedulerState(),
    val links: Map<String, PeerLink> = emptyMap(),
    val entries: Map<Int, ManifestEntry> = emptyMap(),
    val shardHashes: Map<Int, List<ByteArray>> = emptyMap(),
    val sentAtMs: Map<Pair<Int, Int>, Long> = emptyMap(),
    val entriesSent: Set<Pair<String, Int>> = emptySet(),
    val latestRoot: SignedRoot? = null,
)

data class MeshStep(val state: WitnessState, val commands: List<MeshCommand>)

/**
 * The recording phone's side of the mesh (spec section 6). Pure: Nearby callbacks become calls here, and
 * the returned commands become sends. It primes each new holder with the session header and newest root,
 * sends each holder the manifest entry before its first shard of a segment, runs the scheduler every
 * tick, learns per-peer goodput from ACK frames, and runs the rate controller every window.
 */
class WitnessMesh(
    header: SessionHeader,
    private val config: MeshConfig = MeshConfig(),
    private val rates: RateController = RateController(),
) {
    private val sessionId = header.sessionId
    private val headerFrame = header.encode()
    private val scheduler = Scheduler(config.scheduler)
    val progress = Progress(config.scheduler)

    fun initial(tier: Tier, nowMs: Long): WitnessState = WitnessState(rate = RateState(rates.clamp(tier)), windowStartMs = nowMs)

    fun onPeerConnected(state: WitnessState, peerId: String, quality: LinkQuality, nowMs: Long): MeshStep {
        val link = PeerLink(quality, quality.priorBytesPerSec)
        val next = state.copy(
            links = state.links + (peerId to link),
            scheduler = scheduler.upsertPeer(state.scheduler, peerId, link.goodputBytesPerSec, nowMs),
            entriesSent = state.entriesSent.filterNot { it.first == peerId }.toSet(),
        )
        val commands = listOfNotNull(
            MeshCommand.SendControl(peerId, headerFrame),
            state.latestRoot?.let { MeshCommand.SendControl(peerId, it.encode()) },
        )
        return MeshStep(next, commands)
    }

    fun onPeerQuality(state: WitnessState, peerId: String, quality: LinkQuality, nowMs: Long): WitnessState {
        val link = state.links[peerId] ?: return state
        val goodput = if (link.samples == 0) quality.priorBytesPerSec else link.goodputBytesPerSec
        val updated = link.copy(quality = quality, goodputBytesPerSec = goodput)
        return state.copy(
            links = state.links + (peerId to updated),
            scheduler = scheduler.upsertPeer(state.scheduler, peerId, goodput, nowMs),
        )
    }

    fun onPeerLost(state: WitnessState, peerId: String): WitnessState = state.copy(
        links = state.links - peerId,
        scheduler = scheduler.removePeer(state.scheduler, peerId),
        entriesSent = state.entriesSent.filterNot { it.first == peerId }.toSet(),
    )

    /** A newly sealed segment. [shardHashes] are SHA-256 of each shard, checked against holder ACKs. */
    fun onSegment(state: WitnessState, entry: ManifestEntry, root: SignedRoot, shardHashes: List<ByteArray>): MeshStep {
        require(shardHashes.size == config.scheduler.totalShards) { "need one hash per shard" }
        val next = state.copy(
            scheduler = scheduler.addSegment(state.scheduler, entry.index),
            entries = state.entries + (entry.index to entry),
            shardHashes = state.shardHashes + (entry.index to shardHashes.map { it.copyOf() }),
            latestRoot = root,
        )
        val rootFrame = root.encode()
        return MeshStep(next, state.links.keys.sorted().map { MeshCommand.SendControl(it, rootFrame) })
    }

    /** Applies one batched ACK frame and takes one goodput sample from it. */
    fun onAck(state: WitnessState, peerId: String, frame: AckFrame, nowMs: Long): WitnessState {
        if (!frame.sessionId.contentEquals(sessionId)) return state
        val valid = frame.entries.filter { hashMatches(state, it) }
        if (valid.isEmpty()) return state
        val sentTimes = valid.mapNotNull { ack ->
            val slot = state.scheduler.segment(ack.segmentIndex)?.slots?.getOrNull(ack.shardIndex)
            state.sentAtMs[ack.segmentIndex to ack.shardIndex]?.takeIf { slot?.peerId == peerId }
        }
        val bytes = valid.sumOf { ShardCodec.shardSize(state.entries.getValue(it.segmentIndex).ctLen, config.scheduler.dataShards).toLong() }
        val links = if (sentTimes.isEmpty()) state.links else sample(state.links, peerId, bytes, sentTimes.min(), nowMs)
        val acked = valid.fold(state.scheduler) { acc, ack -> scheduler.onAck(acc, peerId, ack.segmentIndex, ack.shardIndex) }
        val goodput = links[peerId]?.goodputBytesPerSec
        return state.copy(
            scheduler = if (goodput != null) scheduler.upsertPeer(acked, peerId, goodput, nowMs) else acked,
            links = links,
            sentAtMs = state.sentAtMs - valid.map { it.segmentIndex to it.shardIndex }.toSet(),
        )
    }

    fun onTick(state: WitnessState, nowMs: Long): MeshStep {
        val refreshed = state.links.entries.fold(state.scheduler) { acc, (id, link) ->
            scheduler.upsertPeer(acc, id, link.goodputBytesPerSec, nowMs)
        }
        val plan = scheduler.plan(scheduler.expire(refreshed, nowMs), nowMs)
        val start = state.copy(scheduler = plan.state) to emptyList<MeshCommand>()
        val (sent, sendCommands) = plan.sends.fold(start) { (acc, commands), send ->
            val key = send.peerId to send.segmentIndex
            val entryCommand = acc.entries[send.segmentIndex]
                ?.takeIf { key !in acc.entriesSent }
                ?.let { MeshCommand.SendControl(send.peerId, EntryFrame(sessionId, it).encode()) }
            val next = acc.copy(
                entriesSent = acc.entriesSent + key,
                sentAtMs = acc.sentAtMs + ((send.segmentIndex to send.shardIndex) to nowMs),
            )
            next to commands + listOfNotNull(entryCommand, MeshCommand.SendShard(send.peerId, send.segmentIndex, send.shardIndex))
        }
        val (rated, tierCommands) = rateWindow(sent, nowMs)
        return MeshStep(rated, sendCommands + tierCommands)
    }

    private fun hashMatches(state: WitnessState, ack: AckEntry): Boolean {
        val expected = state.shardHashes[ack.segmentIndex]?.getOrNull(ack.shardIndex) ?: return false
        return expected.contentEquals(ack.shardHash)
    }

    private fun rateWindow(state: WitnessState, nowMs: Long): Pair<WitnessState, List<MeshCommand>> {
        if (nowMs - state.windowStartMs < config.rateWindowMs) return state to emptyList()
        // Alone, nothing can be sent anyway: keep the tier rather than throw video away.
        if (state.links.isEmpty()) return state.copy(windowStartMs = nowMs) to emptyList()
        val goodput = state.links.values.sumOf { it.goodputBytesPerSec }
        val backlog = state.scheduler.segments.count { progress.status(it) == SegmentStatus.SENDING }
        // Trust measured goodput too, in case the bandwidth callback never reports the upgrade.
        val bulkLaneUp = state.links.values.any { it.quality.carriesVideo || (it.samples > 0 && it.goodputBytesPerSec >= BULK_BYTES_PER_SEC) }
        val next = rates.onWindow(state.rate, goodput, backlog, bulkLaneUp)
        val commands = if (next.tier != state.rate.tier) listOf(MeshCommand.SetTier(next.tier)) else emptyList()
        return state.copy(rate = next, windowStartMs = nowMs) to commands
    }

    /** Busy-time goodput: bytes in the frame over the time since the later of the last ACK and the first send. */
    private fun sample(links: Map<String, PeerLink>, peerId: String, bytes: Long, firstSentMs: Long, nowMs: Long): Map<String, PeerLink> {
        val link = links[peerId] ?: return links
        val start = maxOf(firstSentMs, link.lastAckMs ?: firstSentMs)
        val rate = bytes * MS_PER_SECOND / maxOf(nowMs - start, config.minSampleMs).toDouble()
        val blended = if (link.samples == 0) rate else link.goodputBytesPerSec * (1 - config.ewmaAlpha) + rate * config.ewmaAlpha
        return links + (peerId to link.copy(goodputBytesPerSec = blended, samples = link.samples + 1, lastAckMs = nowMs))
    }

    private companion object {
        const val MS_PER_SECOND = 1_000.0

        /** Far above what Bluetooth delivers, so a link this fast must be the Wi-Fi lane. */
        const val BULK_BYTES_PER_SEC = 60_000.0
    }
}

/** SHA-256 of each shard, in shard order, for [WitnessMesh.onSegment]. */
fun shardHashes(shards: List<ByteArray>): List<ByteArray> = shards.map { Bytes.sha256(it) }
