package xyz.ghostwitness.core.merkle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.hexToBytes

class MerkleTest {
    private fun leaves(n: Int) = List(n) { Merkle.leafHash(byteArrayOf(it.toByte())) }

    @Test
    fun `matches the RFC 6962 reference roots`() {
        val inputs = listOf(
            "", "00", "10", "2021", "3031", "40414243", "5051525354555657", "606162636465666768696a6b6c6d6e6f",
        ).map { it.hexToBytes() }
        val expected = listOf(
            "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
            "fac54203e7cc696cf0dfcb42c92a1d9dbaf70ad9e621f4bd8d98662f00e3c125",
            "aeb6bcfe274b70a14fb067a5e5578264db0fa9b51af5e0ba159158f329e06e77",
            "d37ee418976dd95753c1c73862b9398fa2a2cf9b4ff0fdfe8b30cd95209614b7",
            "4e3bbb1f7b478dcfe71fb631631519a3bca12c9aefca1612bfce4c13a86264d4",
            "76e67dadbcdf1e10e1b74ddc608abd2f98dfb16fbce75277b5232a127f2087ef",
            "ddb89be403809e325750d3d263cd78929c2942b7942a34b77e122c9594a74c8c",
            "5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328",
        )
        expected.forEachIndexed { i, root ->
            assertEquals(root, Bytes.toHex(Merkle.root(inputs.take(i + 1).map { Merkle.leafHash(it) })), "tree size ${i + 1}")
        }
    }

    @Test
    fun `empty tree root is the hash of nothing`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Bytes.toHex(Merkle.root(emptyList())))
    }

    @Test
    fun `every inclusion proof verifies for trees of 1 to 17 leaves`() {
        for (n in 1..17) {
            val l = leaves(n)
            val root = Merkle.root(l)
            for (i in 0 until n) {
                assertTrue(Merkle.verify(l[i], i, n, Merkle.proof(i, l), root), "n=$n i=$i")
            }
        }
    }

    @Test
    fun `proofs fail for the wrong leaf, index, size, path or root`() {
        val l = leaves(7)
        val root = Merkle.root(l)
        val proof = Merkle.proof(3, l)
        assertFalse(Merkle.verify(l[4], 3, 7, proof, root))
        assertFalse(Merkle.verify(l[3], 4, 7, proof, root))
        assertFalse(Merkle.verify(l[3], 3, 4, proof, root))
        assertFalse(Merkle.verify(l[3], 7, 7, proof, root))
        assertFalse(Merkle.verify(l[3], -1, 7, proof, root))
        assertFalse(Merkle.verify(l[3], 3, 7, proof.dropLast(1), root))
        assertFalse(Merkle.verify(l[3], 3, 7, proof + listOf(l[0]), root))
        assertFalse(Merkle.verify(l[3], 3, 7, proof, Merkle.leafHash(byteArrayOf(1))))
    }

    @Test
    fun `single leaf tree has an empty proof and its root is the leaf`() {
        val l = leaves(1)
        assertEquals(emptyList(), Merkle.proof(0, l))
        assertContentEquals(l[0], Merkle.root(l))
        assertFailsWith<IllegalArgumentException> { Merkle.proof(1, l) }
    }

    @Test
    fun `block leaves split at 1 KiB`() {
        assertEquals(2, Merkle.blockLeaves(ByteArray(2048)).size)
        assertEquals(3, Merkle.blockLeaves(ByteArray(2049)).size)
        assertEquals(1, Merkle.blockLeaves(ByteArray(0)).size)
        val oneBlock = ByteArray(Merkle.BLOCK_BYTES) { it.toByte() }
        assertContentEquals(Merkle.leafHash(oneBlock), Merkle.blockRoot(oneBlock))
        assertFailsWith<IllegalArgumentException> { Merkle.blockLeaves(oneBlock, 0) }
    }
}
