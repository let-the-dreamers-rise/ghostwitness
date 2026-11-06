package xyz.ghostwitness.core.mesh

import kotlin.random.Random
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.merkle.Merkle
import xyz.ghostwitness.core.session.RecordingSession
import xyz.ghostwitness.core.session.SegmentAppended
import xyz.ghostwitness.core.wire.AckEntry
import xyz.ghostwitness.core.wire.AckFrame
import xyz.ghostwitness.core.wire.ShardHeader

/** A real recorded session with [count] sealed segments, for mesh tests. */
internal class RecordedSession(count: Int, segmentBytes: Int = 3_000) {
    val start: RecordingSession = RecordingSession.start(
        SessionSecrets.generate(), SessionSigner.generate(), KeyWrap.generateRecoveryKey().publicKey, 1_000L,
    )
    val header = start.header
    val segments: List<SegmentAppended> = (0 until count).fold(emptyList<SegmentAppended>()) { acc, i ->
        acc + (acc.lastOrNull()?.session ?: start).append(1_000L + i * 2_000L, 2_000, Random(i).nextBytes(segmentBytes))
    }

    fun ack(i: Int, j: Int): AckEntry = AckEntry(i, j, shardHashes(segments[i].segment.shards)[j])

    fun ackFrame(vararg pairs: Pair<Int, Int>): AckFrame = AckFrame(header.sessionId, pairs.map { (i, j) -> ack(i, j) })

    fun shardHeader(i: Int, j: Int, payloadId: Long): ShardHeader {
        val entry = segments[i].segment.entry
        val shard = segments[i].segment.shards[j]
        return ShardHeader(header.sessionId, i, j, 5, 3, entry.ctLen, entry.tStartMs, entry.durMs, shard.size, Merkle.blockRoot(shard), payloadId)
    }
}
