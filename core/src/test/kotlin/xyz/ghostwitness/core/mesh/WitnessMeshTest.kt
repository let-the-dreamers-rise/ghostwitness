package xyz.ghostwitness.core.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.ghostwitness.core.capture.Tier
import xyz.ghostwitness.core.sched.SegmentStatus
import xyz.ghostwitness.core.sched.SlotStatus
import xyz.ghostwitness.core.wire.AckFrame

class WitnessMeshTest {
    private val recorded = RecordedSession(6)
    private val mesh = WitnessMesh(recorded.header)

    private fun connect(state: WitnessState, vararg peers: Pair<String, LinkQuality>, now: Long = 0): WitnessState =
        peers.fold(state) { acc, (id, q) -> mesh.onPeerConnected(acc, id, q, now).state }

    private fun seal(state: WitnessState, i: Int): WitnessState {
        val s = recorded.segments[i]
        return mesh.onSegment(state, s.segment.entry, s.root, shardHashes(s.segment.shards)).state
    }

    private fun shards(commands: List<MeshCommand>) = commands.filterIsInstance<MeshCommand.SendShard>()

    private fun decoded(commands: List<MeshCommand>, peer: String) =
        commands.filterIsInstance<MeshCommand.SendControl>().filter { it.peerId == peer }.map { ControlCodec.decode(it.frame) }

    @Test
    fun `a new peer gets the session header, and the newest root once there is one`() {
        val first = mesh.onPeerConnected(mesh.initial(Tier.V1, 0), "a", LinkQuality.HIGH, 0)
        assertEquals(1, first.commands.size)
        assertIs<ControlFrame.Session>(decoded(first.commands, "a").single())
        val withRoot = seal(first.state, 0)
        val second = mesh.onPeerConnected(withRoot, "b", LinkQuality.HIGH, 0)
        val frames = decoded(second.commands, "b")
        assertIs<ControlFrame.Session>(frames[0])
        assertEquals(0, assertIs<ControlFrame.Root>(frames[1]).root.uptoIndex)
    }

    @Test
    fun `each sealed segment broadcasts its signed root to every connected peer`() {
        val state = connect(mesh.initial(Tier.V1, 0), "a" to LinkQuality.HIGH, "b" to LinkQuality.LOW)
        val s = recorded.segments[0]
        val step = mesh.onSegment(state, s.segment.entry, s.root, shardHashes(s.segment.shards))
        assertEquals(listOf("a", "b"), step.commands.map { (it as MeshCommand.SendControl).peerId })
        assertFailsWith<IllegalArgumentException> { mesh.onSegment(state, s.segment.entry, s.root, emptyList()) }
    }

    @Test
    fun `a tick spreads one segment over five peers with the entry sent first and only once`() {
        val peers = listOf("a", "b", "c", "d", "e").map { it to LinkQuality.HIGH }.toTypedArray()
        val state = seal(connect(mesh.initial(Tier.V1, 0), *peers), 0)
        val step = mesh.onTick(state, 100)
        val sends = shards(step.commands)
        assertEquals(5, sends.size)
        assertEquals(listOf(0, 1, 2), sends.take(3).map { it.shardIndex })
        assertEquals(5, sends.map { it.peerId }.toSet().size)
        sends.forEach { send ->
            val position = step.commands.indexOf(send)
            val before = step.commands[position - 1] as MeshCommand.SendControl
            assertEquals(send.peerId, before.peerId)
            assertIs<ControlFrame.Entry>(ControlCodec.decode(before.frame))
        }
        assertEquals(emptyList(), mesh.onTick(step.state, 200).commands)
    }

    @Test
    fun `valid acks make a segment recoverable while wrong hash or session are ignored`() {
        val peers = listOf("a", "b", "c").map { it to LinkQuality.HIGH }.toTypedArray()
        val sent = mesh.onTick(seal(connect(mesh.initial(Tier.V1, 0), *peers), 0), 0)
        val byShard = shards(sent.commands).associate { it.shardIndex to it.peerId }
        val bogus = AckFrame(recorded.header.sessionId, listOf(recorded.ack(1, 0)))
        val ignored = mesh.onAck(sent.state, byShard.getValue(0), bogus, 100)
        assertEquals(sent.state.scheduler, ignored.scheduler)
        val otherSession = AckFrame(ByteArray(16), listOf(recorded.ack(0, 0)))
        assertEquals(sent.state, mesh.onAck(sent.state, byShard.getValue(0), otherSession, 100))
        val acked = (0..2).fold(sent.state) { acc, j -> mesh.onAck(acc, byShard.getValue(j), recorded.ackFrame(0 to j), 400) }
        assertEquals(SegmentStatus.RECOVERABLE, mesh.progress.status(acked.scheduler.segment(0)!!))
        assertTrue(mesh.progress.okToLosePhone(acked.scheduler))
        assertEquals(emptyMap(), acked.sentAtMs)
    }

    @Test
    fun `ack timing teaches goodput, and the faster peer then gets data shards first`() {
        val state = seal(connect(mesh.initial(Tier.V1, 0), "fast" to LinkQuality.MEDIUM, "slow" to LinkQuality.HIGH), 0)
        val sent = mesh.onTick(state, 0)
        val byPeer = shards(sent.commands).associate { it.peerId to it.shardIndex }
        val afterFast = mesh.onAck(sent.state, "fast", recorded.ackFrame(0 to byPeer.getValue("fast")), 50)
        val afterBoth = mesh.onAck(afterFast, "slow", recorded.ackFrame(0 to byPeer.getValue("slow")), 5_000)
        val fast = afterBoth.links.getValue("fast")
        val slow = afterBoth.links.getValue("slow")
        assertEquals(1, fast.samples)
        assertTrue(fast.goodputBytesPerSec > slow.goodputBytesPerSec * 10)
        val next = mesh.onTick(seal(afterBoth, 1), 5_100)
        assertEquals(MeshCommand.SendShard("fast", 1, 0), shards(next.commands).first())
    }

    @Test
    fun `a lost peer's unacknowledged shard goes to another peer on the next tick`() {
        val state = seal(connect(mesh.initial(Tier.V1, 0), "a" to LinkQuality.HIGH, "b" to LinkQuality.HIGH), 0)
        val sent = mesh.onTick(state, 0)
        val lostShard = shards(sent.commands).first { it.peerId == "a" }.shardIndex
        val gone = mesh.onPeerLost(sent.state, "a")
        val replaced = connect(gone, "c" to LinkQuality.HIGH, now = 10)
        val next = mesh.onTick(replaced, 20)
        assertTrue(MeshCommand.SendShard("c", 0, lostShard) in shards(next.commands))
        assertEquals(SlotStatus.SENT, next.state.scheduler.segment(0)!!.slots[lostShard].status)
    }

    @Test
    fun `only Bluetooth links drop capture to audio only at the next window`() {
        val state = seal(connect(mesh.initial(Tier.V1, 0), "a" to LinkQuality.LOW), 0)
        assertEquals(emptyList(), mesh.onTick(state, 1_000).commands.filterIsInstance<MeshCommand.SetTier>())
        val window = mesh.onTick(state, 2_000)
        assertEquals(listOf(MeshCommand.SetTier(Tier.A)), window.commands.filterIsInstance<MeshCommand.SetTier>())
        assertEquals(Tier.A, window.state.rate.tier)
    }

    @Test
    fun `alone the tier is kept and with fast peers it climbs after clean windows`() {
        val alone = mesh.onTick(mesh.initial(Tier.V0, 0), 2_000)
        assertEquals(Tier.V0, alone.state.rate.tier)
        val peers = listOf("a", "b", "c").map { it to LinkQuality.HIGH }.toTypedArray()
        val start = connect(mesh.initial(Tier.V0, 0), *peers)
        val climbed = (1..3).fold(start) { acc, w -> mesh.onTick(acc, w * 2_000L).state }
        assertEquals(Tier.V1, climbed.rate.tier)
        assertEquals(Tier.V1, mesh.initial(Tier.V2, 0).rate.tier)
    }

    @Test
    fun `peer quality updates move the prior until real samples exist`() {
        val state = connect(mesh.initial(Tier.V1, 0), "a" to LinkQuality.LOW)
        val upgraded = mesh.onPeerQuality(state, "a", LinkQuality.HIGH, 10)
        assertEquals(LinkQuality.HIGH.priorBytesPerSec, upgraded.links.getValue("a").goodputBytesPerSec)
        assertEquals(upgraded, mesh.onPeerQuality(upgraded, "nobody", LinkQuality.LOW, 10))
    }
}
