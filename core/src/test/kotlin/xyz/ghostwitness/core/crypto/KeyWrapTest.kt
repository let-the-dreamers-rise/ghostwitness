package xyz.ghostwitness.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KeyWrapTest {
    private val recovery = KeyWrap.generateRecoveryKey()
    private val sessionId = ByteArray(SESSION_ID_BYTES) { it.toByte() }
    private val key = ByteArray(KEY_BYTES) { (it * 7).toByte() }

    @Test
    fun `wrapped key opens with the recovery key`() {
        val wrapped = KeyWrap.wrap(key, recovery.publicKey, sessionId)
        assertEquals(32 + KEY_BYTES + GCM_TAG_BYTES, wrapped.size)
        assertContentEquals(key, KeyWrap.unwrap(wrapped, recovery, sessionId))
    }

    @Test
    fun `each wrap uses a fresh ephemeral key`() {
        val first = KeyWrap.wrap(key, recovery.publicKey, sessionId)
        val second = KeyWrap.wrap(key, recovery.publicKey, sessionId)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `another recovery key cannot open it`() {
        val wrapped = KeyWrap.wrap(key, recovery.publicKey, sessionId)
        assertFailsWith<KeyUnwrapException> { KeyWrap.unwrap(wrapped, KeyWrap.generateRecoveryKey(), sessionId) }
    }

    @Test
    fun `wrapped key is bound to its session`() {
        val wrapped = KeyWrap.wrap(key, recovery.publicKey, sessionId)
        val otherSession = sessionId.copyOf().also { it[15] = 99 }
        assertFailsWith<KeyUnwrapException> { KeyWrap.unwrap(wrapped, recovery, otherSession) }
    }

    @Test
    fun `truncated or tampered input fails`() {
        val wrapped = KeyWrap.wrap(key, recovery.publicKey, sessionId)
        assertFailsWith<KeyUnwrapException> { KeyWrap.unwrap(wrapped.copyOf(32), recovery, sessionId) }
        val tampered = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<KeyUnwrapException> { KeyWrap.unwrap(tampered, recovery, sessionId) }
    }

    @Test
    fun `recovery key halves are 32 bytes and returned as copies`() {
        assertEquals(32, recovery.publicKey.size)
        assertEquals(32, recovery.privateKey.size)
        recovery.privateKey.fill(0)
        assertFalse(recovery.privateKey.contentEquals(ByteArray(32)))
    }
}
