package xyz.ghostwitness.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.ghostwitness.core.codec.SegmentCodec
import xyz.ghostwitness.core.crypto.ChunkCipher
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.manifest.ChainVerifier
import xyz.ghostwitness.core.manifest.SignedRoot

class RecordingSessionTest {
    private val recovery = KeyWrap.generateRecoveryKey()
    private val signer = SessionSigner.generate()
    private val plaintexts = List(4) { Random(it).nextBytes(5_000 + it) }

    private fun record(): Pair<RecordingSession, List<SegmentAppended>> {
        val start = RecordingSession.start(SessionSecrets.generate(), signer, recovery.publicKey, startWallMs = 1_700_000_000_000)
        val appended = plaintexts.withIndex().fold(emptyList<SegmentAppended>()) { acc, (i, p) ->
            acc + (acc.lastOrNull()?.session ?: start).append(1_700_000_000_000 + i * 2_000L, 2_000, p)
        }
        return start to appended
    }

    @Test
    fun `appending builds an unbroken chain with a signed root after every segment`() {
        val (start, appended) = record()
        assertEquals(0, start.nextIndex)
        val last = appended.last()
        assertEquals(4, last.session.nextIndex)
        val entries = last.session.entries
        val hashes = appended.associate { it.segment.entry.index to it.segment.ciphertextHash }
        assertEquals(emptyList(), ChainVerifier.verify(entries, hashes))
        appended.forEachIndexed { i, a ->
            assertEquals(i, a.root.uptoIndex)
            assertTrue(a.root.verify(start.header.sessionPublicKey))
            assertTrue(a.root.covers(entries.take(i + 1)))
        }
    }

    @Test
    fun `the recovery key alone rebuilds every segment from any three shards`() {
        val (start, appended) = record()
        val header = SessionHeader.decode(start.header.encode())
        val key = KeyWrap.unwrap(header.wrappedKey, recovery, header.sessionId)
        val codec = SegmentCodec(ChunkCipher(key, header.salt), header.sessionId)
        appended.forEachIndexed { i, a ->
            val shards = a.segment.shards
            val subset = mapOf(1 to shards[1], 3 to shards[3], 4 to shards[4])
            assertContentEquals(plaintexts[i], codec.decode(a.segment.entry, subset))
        }
    }

    @Test
    fun `self-check opens a segment, and close wipes the key for every derived session`() {
        val (start, appended) = record()
        val a = appended[2]
        val shards = a.segment.shards.withIndex().associate { (j, s) -> j to s }
        assertContentEquals(plaintexts[2], a.session.open(a.segment.entry, shards))
        start.close()
        assertFailsWith<IllegalStateException> { a.session.open(a.segment.entry, shards) }
        assertFailsWith<IllegalStateException> { a.session.append(0, 2_000, byteArrayOf(1)) }
    }

    @Test
    fun `signed roots round trip through their encoding`() {
        val (_, appended) = record()
        val root = appended.last().root
        val bytes = root.encode()
        assertEquals(SignedRoot.ENCODED_BYTES, bytes.size)
        val decoded = SignedRoot.decode(bytes)
        assertEquals(root.uptoIndex, decoded.uptoIndex)
        assertContentEquals(root.sessionId, decoded.sessionId)
        assertTrue(decoded.verify(signer.publicKey))
    }
}
