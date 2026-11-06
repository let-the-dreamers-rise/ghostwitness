package xyz.ghostwitness.core.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.ghostwitness.core.codec.EncodedSegment
import xyz.ghostwitness.core.codec.SegmentCodec
import xyz.ghostwitness.core.crypto.SegmentAad
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner

class ChainAndRootTest {
    private val secrets = SessionSecrets.generate()
    private val codec = SegmentCodec(secrets.cipher(), secrets.sessionId)
    private val signer = SessionSigner.generate()

    private fun chain(count: Int): List<EncodedSegment> = (0 until count).fold(emptyList<EncodedSegment>()) { acc, i ->
        val prev = acc.lastOrNull()?.ciphertextHash ?: SegmentAad.genesisPrevHash()
        acc + codec.encode(i, i * 2_000L, 2_000, "segment $i".toByteArray(), prev)
    }

    private fun hashes(segments: List<EncodedSegment>) = segments.associate { it.entry.index to it.ciphertextHash }

    @Test
    fun `an intact chain has no problems`() {
        val segments = chain(5)
        assertEquals(emptyList(), ChainVerifier.verify(segments.map { it.entry }, hashes(segments)))
    }

    @Test
    fun `a dropped segment is reported as a gap`() {
        val segments = chain(5).filter { it.entry.index != 2 }
        assertEquals(listOf<ChainProblem>(ChainProblem.Gap(2)), ChainVerifier.verify(segments.map { it.entry }, hashes(segments)))
    }

    @Test
    fun `reordered segments are reported`() {
        val segments = chain(4)
        val reordered = listOf(segments[0], segments[2], segments[1], segments[3])
        val problems = ChainVerifier.verify(reordered.map { it.entry }, hashes(segments))
        assertTrue(ChainProblem.OutOfOrder(position = 2, index = 1) in problems)
    }

    @Test
    fun `a substituted ciphertext breaks the next link`() {
        val segments = chain(3)
        val impostor = codec.encode(1, 2_000L, 2_000, "something else".toByteArray(), segments[0].ciphertextHash)
        val seen = hashes(segments) + (1 to impostor.ciphertextHash)
        assertEquals(listOf<ChainProblem>(ChainProblem.BrokenLink(2)), ChainVerifier.verify(segments.map { it.entry }, seen))
    }

    @Test
    fun `the first segment must point at the genesis hash`() {
        val notGenesis = codec.encode(0, 0, 2_000, byteArrayOf(1), ByteArray(32) { 1 })
        assertEquals(listOf<ChainProblem>(ChainProblem.BrokenLink(0)), ChainVerifier.verify(listOf(notGenesis.entry), emptyMap()))
    }

    @Test
    fun `signed root verifies and covers exactly its entries`() {
        val entries = chain(4).map { it.entry }
        val signed = SignedRoot.create(secrets.sessionId, entries, signer)
        assertEquals(3, signed.uptoIndex)
        assertTrue(signed.verify(signer.publicKey))
        assertTrue(signed.covers(entries))
        assertFalse(signed.covers(entries.reversed()))
        assertFalse(signed.covers(entries.dropLast(1)))
    }

    @Test
    fun `signed root rejects another key and a forged upto index`() {
        val signed = SignedRoot.create(secrets.sessionId, chain(3).map { it.entry }, signer)
        assertFalse(signed.verify(SessionSigner.generate().publicKey))
        val forged = SignedRoot(secrets.sessionId, 1, signed.root, signed.signature)
        assertFalse(forged.verify(signer.publicKey))
    }

    @Test
    fun `create requires a contiguous run from index 0`() {
        val entries = chain(3).map { it.entry }
        assertFailsWith<IllegalArgumentException> { SignedRoot.create(secrets.sessionId, emptyList(), signer) }
        assertFailsWith<IllegalArgumentException> { SignedRoot.create(secrets.sessionId, entries.drop(1), signer) }
        assertFailsWith<IllegalArgumentException> { SignedRoot(ByteArray(15), 0, ByteArray(32), ByteArray(64)) }
    }
}
