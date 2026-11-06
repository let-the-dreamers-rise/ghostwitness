package xyz.ghostwitness.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BytesTest {
    @Test
    fun `sha256 matches the FIPS 180-2 abc vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Bytes.toHex(Bytes.sha256("abc".toByteArray())),
        )
    }

    @Test
    fun `sha256 over parts equals sha256 over the concatenation`() {
        val a = "ghost".toByteArray()
        val b = "witness".toByteArray()
        assertContentEquals(Bytes.sha256(Bytes.concat(a, b)), Bytes.sha256(a, b))
    }

    @Test
    fun `integers encode big-endian`() {
        assertContentEquals(byteArrayOf(0xFF.toByte()), Bytes.u8(255))
        assertContentEquals(byteArrayOf(0x12, 0x34), Bytes.u16be(0x1234))
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), Bytes.u32be(0x01020304))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0x01, 0x00), Bytes.u64be(256))
    }

    @Test
    fun `out of range integers are rejected`() {
        assertFailsWith<IllegalArgumentException> { Bytes.u8(256) }
        assertFailsWith<IllegalArgumentException> { Bytes.u16be(-1) }
        assertFailsWith<IllegalArgumentException> { Bytes.u16be(0x10000) }
        assertFailsWith<IllegalArgumentException> { Bytes.u32be(-1) }
    }

    @Test
    fun `concat joins parts in order and zeros are zero`() {
        assertContentEquals(byteArrayOf(1, 2, 3), Bytes.concat(byteArrayOf(1), byteArrayOf(), byteArrayOf(2, 3)))
        assertContentEquals(ByteArray(4), Bytes.zeros(4))
        assertEquals("00ff10", Bytes.toHex(byteArrayOf(0, 0xFF.toByte(), 0x10)))
    }

    @Test
    fun `hex decodes back and rejects bad input`() {
        assertContentEquals(byteArrayOf(0, 0xFF.toByte(), 0x10), Bytes.fromHex("00FF10"))
        assertFailsWith<IllegalArgumentException> { Bytes.fromHex("abc") }
        assertFailsWith<IllegalArgumentException> { Bytes.fromHex("zz") }
    }
}
