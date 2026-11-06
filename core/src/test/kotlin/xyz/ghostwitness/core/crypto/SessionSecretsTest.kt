package xyz.ghostwitness.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionSecretsTest {
    @Test
    fun `generated secrets have the spec sizes and differ each time`() {
        val a = SessionSecrets.generate()
        val b = SessionSecrets.generate()
        assertEquals(SESSION_ID_BYTES, a.sessionId.size)
        assertEquals(KEY_BYTES, a.key.size)
        assertEquals(SALT_BYTES, a.salt.size)
        assertFalse(a.key.contentEquals(b.key))
        assertFalse(a.sessionId.contentEquals(b.sessionId))
    }

    @Test
    fun `getters return copies and wipe zeroes the key`() {
        val secrets = SessionSecrets.generate()
        secrets.key.fill(0)
        assertFalse(secrets.key.contentEquals(ByteArray(KEY_BYTES)))
        secrets.wipe()
        assertContentEquals(ByteArray(KEY_BYTES), secrets.key)
    }

    @Test
    fun `cipher uses the session key and salt`() {
        val secrets = SessionSecrets.generate()
        val aad = SegmentAad(secrets.sessionId, 0, 0, SegmentAad.genesisPrevHash())
        val ciphertext = secrets.cipher().encrypt(byteArrayOf(1, 2, 3), aad)
        assertContentEquals(byteArrayOf(1, 2, 3), ChunkCipher(secrets.key, secrets.salt).decrypt(ciphertext, aad))
    }
}
