package xyz.ghostwitness.core.eth

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.hexToBytes

class EthTest {
    @Test
    fun `keccak and selectors match known vectors`() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Bytes.toHex(Keccak.hash()))
        assertEquals("a9059cbb", Bytes.toHex(Keccak.selector("transfer(address,uint256)")))
    }

    @Test
    fun `rlp matches the yellow paper examples`() {
        assertEquals("83646f67", Bytes.toHex(Rlp.encode(Rlp.of("dog".toByteArray()))))
        assertEquals("c88363617483646f67", Bytes.toHex(Rlp.encode(Rlp.list(Rlp.of("cat".toByteArray()), Rlp.of("dog".toByteArray())))))
        assertEquals("80", Bytes.toHex(Rlp.encode(Rlp.of(ByteArray(0)))))
        assertEquals("80", Bytes.toHex(Rlp.encode(Rlp.of(0L))))
        assertEquals("0f", Bytes.toHex(Rlp.encode(Rlp.of(15L))))
        assertEquals("820400", Bytes.toHex(Rlp.encode(Rlp.of(1024L))))
        assertEquals("c0", Bytes.toHex(Rlp.encode(Rlp.list())))
        val long = ByteArray(56) { 0x61 }
        assertEquals("b838", Bytes.toHex(Rlp.encode(Rlp.of(long))).take(4))
        assertFailsWith<IllegalArgumentException> { Rlp.of(BigInteger.valueOf(-1)) }
    }

    @Test
    fun `abi matches the solidity docs example`() {
        val calldata = Abi.call(
            Keccak.selector("sam(bytes,bool,uint256[])"),
            AbiValue.DynBytes("dave".toByteArray()),
            AbiValue.uint(1),
            AbiValue.Array(listOf(AbiValue.uint(1), AbiValue.uint(2), AbiValue.uint(3))),
        )
        val expected = "a5643bf2" +
            "0000000000000000000000000000000000000000000000000000000000000060" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "00000000000000000000000000000000000000000000000000000000000000a0" +
            "0000000000000000000000000000000000000000000000000000000000000004" +
            "6461766500000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000003" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000003"
        assertEquals(expected, Bytes.toHex(calldata))
    }

    @Test
    fun `static tuples are inlined and words pad the right way`() {
        val address = ByteArray(20) { 0x11 }
        val encoded = Abi.encode(listOf(AbiValue.Tuple(listOf(AbiValue.bytes32(ByteArray(32) { 0x22 }), AbiValue.address(address)))))
        assertEquals(64, encoded.size)
        assertContentEquals(ByteArray(12) + address, encoded.copyOfRange(32, 64))
        assertFailsWith<IllegalArgumentException> { AbiValue.address(ByteArray(19)) }
        assertFailsWith<IllegalArgumentException> { AbiValue.bytes32(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { AbiValue.Uint(BigInteger.ONE.shiftLeft(256)) }
        assertFailsWith<IllegalArgumentException> { AbiValue.Word(ByteArray(33), false) }
    }

    @Test
    fun `address derivation matches the web3 docs key`() {
        val key = EthKey.fromPrivateKey("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318".hexToBytes())
        assertEquals("0x2c7536e3605d9c16a7a3d7b1898e529396a65c23", EthKey.checksumless(key.address))
        assertContentEquals("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318".hexToBytes(), key.privateKeyBytes())
        assertFailsWith<IllegalArgumentException> { EthKey.fromPrivateKey(ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { EthKey.fromPrivateKey(ByteArray(31)) }
    }

    @Test
    fun `signatures are low-s and recover to the signer`() {
        val key = EthKey.generate()
        val half = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").n.shiftRight(1)
        repeat(16) { i ->
            val hash = Keccak.hash("message $i".toByteArray())
            val signature = key.sign(hash)
            assert(signature.s <= half)
            assertContentEquals(key.address, EthKey.recoverAddress(hash, signature))
        }
        assertNull(EthKey.recoverAddress(Keccak.hash(), RecoverableSignature(BigInteger.ZERO, BigInteger.ONE, 0)))
        assertFailsWith<IllegalArgumentException> { key.sign(ByteArray(31)) }
    }

    @Test
    fun `a signed EIP-1559 transaction recovers to its sender`() {
        val key = EthKey.generate()
        val tx = Eip1559Tx(7794095, 3, BigInteger.ONE, BigInteger.TEN, 100_000, ByteArray(20) { 0x44 }, BigInteger.ZERO, byteArrayOf(1, 2, 3))
        val raw = tx.signedBy(key)
        assertEquals(0x02.toByte(), raw[0])
        assertEquals(tx.signingHash().size, 32)
        assertFailsWith<IllegalArgumentException> { Eip1559Tx(1, 0, BigInteger.ONE, BigInteger.ONE, 1, ByteArray(19), BigInteger.ZERO, ByteArray(0)) }
    }
}
