package xyz.ghostwitness.core.recovery

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.crypto.SessionSecrets
import xyz.ghostwitness.core.crypto.SessionSigner
import xyz.ghostwitness.core.merkle.Merkle
import xyz.ghostwitness.core.session.RecordingSession
import xyz.ghostwitness.core.arkiv.ArkivCodec
import xyz.ghostwitness.core.eth.Abi
import xyz.ghostwitness.core.eth.AbiValue
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.eth.LegacyTx
import xyz.ghostwitness.core.eth.EthKey

class RecoveryTest {
    @Test fun `later uploaded segment recovers with a gap and no earlier entries`() {
        val key = KeyWrap.generateRecoveryKey()
        var session = RecordingSession.start(SessionSecrets.generate(), SessionSigner.generate(), key.publicKey, 1000)
        val segments = (0..4).map { i -> session.append(1000 + i * 2000L, 2000, "segment $i".toByteArray()).also { session = it.session } }
        val last = segments.last()
        val record = RecoveryRecord(session.header, ByteArray(20) { 1 }, last.segment.entry, last.root, Merkle.proof(4, session.entries.map { it.leafHash() }))
        session.close()
        val parsed = RecoveryRecord.decode(record.encode())
        assertTrue(parsed.verify())
        val shards = last.segment.shards
        assertContentEquals("segment 4".toByteArray(), SegmentRecovery.recover(parsed, parsed.sessionCode, key, mapOf(1 to shards[1], 3 to shards[3], 4 to shards[4])).plaintext)
        assertFailsWith<IllegalArgumentException> { SegmentRecovery.recover(parsed, "0".repeat(32) + ":" + "0".repeat(64), key, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SegmentRecovery.recover(parsed, parsed.sessionCode, key, emptyMap(), ByteArray(32)) }
        val corrupt = record.encode().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { RecoveryRecord.decode(corrupt) }
        assertFailsWith<IllegalArgumentException> { RecoveryRecord.decode(record.encode() + byteArrayOf(1)) }
        val ticket = last.session.authorizeUpload(4, 3, ByteArray(20) { 2 }, Bytes.sha256(shards[3]))
        assertTrue(UploadTicket.decode(ticket.encode()).verify(parsed))
        val changed = ticket.encode().also { it[25] = 9 }
        kotlin.test.assertFalse(UploadTicket.decode(changed).verify(parsed))
    }

    @Test fun `dynamic offset follows the full static tuple head`() {
        val encoded = Abi.encode(listOf(AbiValue.Tuple(listOf(AbiValue.uint(1), AbiValue.uint(2))), AbiValue.DynBytes(byteArrayOf(3))))
        assertContentEquals(Abi.word(java.math.BigInteger.valueOf(96)), encoded.copyOfRange(64, 96))
        assertContentEquals(Abi.word(java.math.BigInteger.ONE), encoded.copyOfRange(96, 128))
    }

    @Test fun `Arkiv execute has deterministic sorted attributes and the documented selector`() {
        val attrs = listOf(ArkivCodec.str("sid", "a".repeat(32)), ArkivCodec.bytes("data", byteArrayOf(1, 2)), ArkivCodec.number("i", 3))
        fun encode(a: List<ArkivCodec.Attribute>) = ArkivCodec.createAndTransfer(a, ByteArray(16), 7794095, ByteArray(20) { 1 }, 0, ByteArray(20) { 2 })
        assertContentEquals(encode(attrs), encode(attrs.reversed()))
        assertContentEquals(Bytes.fromHex("49650044"), encode(attrs).copyOfRange(0, 4))
        // Independent ethers 6.15.0 AbiCoder fixture, create-only (writer is owner).
        val createOnly = ArkivCodec.createAndTransfer(attrs, ByteArray(16), 7794095, ByteArray(20) { 1 }, 0, ByteArray(20) { 1 })
        kotlin.test.assertEquals("84bc1971c561f5002421241e7b9448576fc705d2d7e8358fc6c9b4bae661d87f", Bytes.toHex(Bytes.sha256(createOnly)))
        assertFailsWith<IllegalArgumentException> { encode(attrs + attrs.first()) }
        assertFailsWith<IllegalArgumentException> { ArkivCodec.str("\$owner", "bad") }
    }

    @Test fun `Rabdi legacy transaction matches independent ethers signing vector`() {
        val tx = LegacyTx(7794095, 0, java.math.BigInteger.valueOf(123), 1_400_000, ByteArray(20) { 0x44 }, byteArrayOf(1, 2, 3))
        kotlin.test.assertEquals("f866807b83155cc0944444444444444444444444444444444444444444808301020383eddb82a0ada93c74f7591e2c80133e739abd17ae6bf49ddfeaa90a25238892aad6f5f90aa069cb0167a2deb4913874d26fe6926ed65fe0b4764a44e075f4f83369296140c8",
            Bytes.toHex(tx.signedBy(EthKey.fromPrivateKey(ByteArray(32) { 1 }))))
    }
}
