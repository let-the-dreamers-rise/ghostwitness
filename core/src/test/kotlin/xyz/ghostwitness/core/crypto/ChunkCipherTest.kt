package xyz.ghostwitness.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkCipherTest {
    private val key = ByteArray(KEY_BYTES) { it.toByte() }
    private val salt = ByteArray(SALT_BYTES) { (0xA0 + it).toByte() }
    private val sessionId = ByteArray(SESSION_ID_BYTES) { (0x10 + it).toByte() }
    private val cipher = ChunkCipher(key, salt)
    private val plaintext = "two seconds of video".toByteArray()

    private fun aad(
        index: Int = 3,
        tStart: Long = 6_000,
        prev: ByteArray = ByteArray(Bytes.HASH_BYTES) { 7 },
        session: ByteArray = sessionId,
    ) = SegmentAad(session, index, tStart, prev)

    @Test
    fun `encrypt then decrypt returns the plaintext`() {
        val ciphertext = cipher.encrypt(plaintext, aad())
        assertEquals(plaintext.size + GCM_TAG_BYTES, ciphertext.size)
        assertContentEquals(plaintext, cipher.decrypt(ciphertext, aad()))
    }

    @Test
    fun `nonce is the salt followed by the big-endian index`() {
        assertContentEquals(Bytes.concat(salt, byteArrayOf(0, 0, 1, 2)), cipher.nonceFor(258))
    }

    @Test
    fun `aad layout is session, index, start time, previous hash`() {
        val prev = ByteArray(Bytes.HASH_BYTES) { 9 }
        val encoded = SegmentAad(sessionId, 1, 2L, prev).encode()
        assertEquals(SESSION_ID_BYTES + 4 + 8 + Bytes.HASH_BYTES, encoded.size)
        assertContentEquals(Bytes.concat(sessionId, Bytes.u32be(1), Bytes.u64be(2L), prev), encoded)
    }

    @Test
    fun `any change to the bound context fails authentication`() {
        val ciphertext = cipher.encrypt(plaintext, aad())
        val otherSession = sessionId.copyOf().also { it[0] = 0 }
        val wrongContexts = listOf(
            aad(index = 4),
            aad(tStart = 6_001),
            aad(prev = SegmentAad.genesisPrevHash()),
            aad(session = otherSession),
        )
        wrongContexts.forEach { wrong -> assertFailsWith<SegmentAuthException> { cipher.decrypt(ciphertext, wrong) } }
    }

    @Test
    fun `a flipped ciphertext bit or a wrong key fails authentication`() {
        val ciphertext = cipher.encrypt(plaintext, aad())
        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<SegmentAuthException> { cipher.decrypt(tampered, aad()) }
        val otherKey = ChunkCipher(ByteArray(KEY_BYTES) { 1 }, salt)
        assertFailsWith<SegmentAuthException> { otherKey.decrypt(ciphertext, aad()) }
    }

    @Test
    fun `genesis previous hash is 32 zero bytes`() {
        assertContentEquals(ByteArray(Bytes.HASH_BYTES), SegmentAad.genesisPrevHash())
    }

    @Test
    fun `invalid sizes are rejected`() {
        val prev = ByteArray(Bytes.HASH_BYTES)
        assertFailsWith<IllegalArgumentException> { ChunkCipher(ByteArray(16), salt) }
        assertFailsWith<IllegalArgumentException> { ChunkCipher(key, ByteArray(12)) }
        assertFailsWith<IllegalArgumentException> { SegmentAad(ByteArray(15), 0, 0, prev) }
        assertFailsWith<IllegalArgumentException> { SegmentAad(sessionId, -1, 0, prev) }
        assertFailsWith<IllegalArgumentException> { SegmentAad(sessionId, 0, 0, ByteArray(31)) }
    }

    @Test
    fun `constructor copies its inputs`() {
        val callerKey = key.copyOf()
        val owned = ChunkCipher(callerKey, salt)
        val ciphertext = owned.encrypt(plaintext, aad())
        callerKey.fill(0)
        assertContentEquals(plaintext, owned.decrypt(ciphertext, aad()))
    }
}
