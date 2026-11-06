package xyz.ghostwitness.core.eth

import org.bouncycastle.crypto.digests.KeccakDigest

/** Ethereum's Keccak-256 (the pre-NIST padding, not SHA3-256). */
object Keccak {
    private const val BITS = 256

    fun hash(vararg parts: ByteArray): ByteArray {
        val digest = KeccakDigest(BITS)
        parts.forEach { digest.update(it, 0, it.size) }
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    /** First 4 bytes of the hash of a canonical function signature, e.g. `transfer(address,uint256)`. */
    fun selector(signature: String): ByteArray = hash(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)
}
