package xyz.ghostwitness.core.crypto

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.crypto.ec.CustomNamedCurves

class SessionSignerTest {
    private val signer = SessionSigner.generate()
    private val message = "GWROOT test".toByteArray()
    private val order: BigInteger = CustomNamedCurves.getByName("secp256k1").n

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
    }

    @Test
    fun `signature verifies against the uncompressed public key`() {
        val signature = signer.sign(message)
        assertEquals(SIGNATURE_BYTES, signature.size)
        assertEquals(65, signer.publicKey.size)
        assertEquals(0x04.toByte(), signer.publicKey[0])
        assertTrue(SessionSigner.verify(signer.publicKey, message, signature))
    }

    @Test
    fun `signing is deterministic`() {
        assertContentEquals(signer.sign(message), signer.sign(message))
    }

    @Test
    fun `changed message, signature or key fails`() {
        val signature = signer.sign(message)
        assertFalse(SessionSigner.verify(signer.publicKey, "other".toByteArray(), signature))
        val tampered = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(SessionSigner.verify(signer.publicKey, message, tampered))
        assertFalse(SessionSigner.verify(SessionSigner.generate().publicKey, message, signature))
    }

    @Test
    fun `signatures are always low-s`() {
        repeat(32) { i ->
            val signature = signer.sign("segment $i".toByteArray())
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            assertTrue(s <= order.shiftRight(1), "signature $i has high s")
        }
    }

    @Test
    fun `high-s twin of a valid signature is rejected`() {
        val signature = signer.sign(message)
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        val twin = signature.copyOfRange(0, 32) + fixed32(order - s)
        assertFalse(SessionSigner.verify(signer.publicKey, message, twin))
    }

    @Test
    fun `malformed signature or key returns false`() {
        val signature = signer.sign(message)
        assertFalse(SessionSigner.verify(signer.publicKey, message, signature.copyOf(63)))
        assertFalse(SessionSigner.verify(ByteArray(10), message, signature))
    }
}
