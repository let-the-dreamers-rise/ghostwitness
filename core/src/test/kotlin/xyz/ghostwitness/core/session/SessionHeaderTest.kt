package xyz.ghostwitness.core.session

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.ghostwitness.core.manifest.ManifestFormatException
import xyz.ghostwitness.core.manifest.SignedRoot

class SessionHeaderTest {
    private fun header() = SessionHeader(
        sessionId = ByteArray(16) { it.toByte() },
        startWallMs = 1_762_400_000_000,
        totalShards = 5,
        dataShards = 3,
        salt = ByteArray(8) { 7 },
        sessionPublicKey = ByteArray(65) { 4 },
        wrappedKey = ByteArray(80) { 9 },
    )

    @Test
    fun `header round trips`() {
        val bytes = header().encode()
        assertEquals(3 + 1 + 16 + 8 + 1 + 1 + 8 + 65 + 2 + 80, bytes.size)
        val decoded = SessionHeader.decode(bytes)
        assertContentEquals(ByteArray(16) { it.toByte() }, decoded.sessionId)
        assertEquals(1_762_400_000_000, decoded.startWallMs)
        assertEquals(5, decoded.totalShards)
        assertEquals(3, decoded.dataShards)
        assertContentEquals(ByteArray(8) { 7 }, decoded.salt)
        assertContentEquals(ByteArray(65) { 4 }, decoded.sessionPublicKey)
        assertContentEquals(ByteArray(80) { 9 }, decoded.wrappedKey)
    }

    @Test
    fun `malformed headers are rejected`() {
        val bytes = header().encode()
        val badMagic = bytes.copyOf().also { it[0] = 0 }
        val badVersion = bytes.copyOf().also { it[3] = 9 }
        val badShards = bytes.copyOf().also { it[29] = 9 }
        listOf(badMagic, badVersion, badShards, bytes.copyOf(40), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0)).forEach {
            assertFailsWith<SessionFormatException> { SessionHeader.decode(it) }
        }
    }

    @Test
    fun `constructor validates sizes`() {
        assertFailsWith<IllegalArgumentException> {
            SessionHeader(ByteArray(16), 0, 5, 3, ByteArray(8), ByteArray(64), ByteArray(80))
        }
        assertFailsWith<IllegalArgumentException> {
            SessionHeader(ByteArray(16), 0, 5, 3, ByteArray(8), ByteArray(65), ByteArray(0))
        }
    }

    @Test
    fun `malformed signed roots are rejected`() {
        val valid = SignedRoot(ByteArray(16), 3, ByteArray(32), ByteArray(64)).encode()
        val badMagic = valid.copyOf().also { it[0] = 0 }
        val badVersion = valid.copyOf().also { it[3] = 2 }
        val negative = valid.copyOf().also { it[20] = 0x80.toByte() }
        listOf(badMagic, badVersion, negative, valid.copyOf(100)).forEach {
            assertFailsWith<ManifestFormatException> { SignedRoot.decode(it) }
        }
        assertFailsWith<IllegalArgumentException> { SignedRoot(ByteArray(16), 0, ByteArray(31), ByteArray(64)) }
    }
}
