package xyz.ghostwitness.core.eth

import java.math.BigInteger

/** EIP-155 legacy transaction for Rabdi's current explicit-gasPrice write policy. */
class LegacyTx(
    private val chainId: Long,
    private val nonce: Long,
    private val gasPrice: BigInteger,
    private val gasLimit: Long,
    to: ByteArray,
    data: ByteArray,
) {
    private val to = to.copyOf()
    private val data = data.copyOf()
    init { require(chainId > 0 && nonce >= 0 && gasPrice.signum() >= 0 && gasLimit > 0 && to.size == 20) }
    private fun fields() = listOf(Rlp.of(nonce), Rlp.of(gasPrice), Rlp.of(gasLimit), Rlp.of(to), Rlp.of(0L), Rlp.of(data))
    fun signedBy(key: EthKey): ByteArray {
        val digest = Keccak.hash(Rlp.encode(Rlp.Items(fields() + listOf(Rlp.of(chainId), Rlp.of(0L), Rlp.of(0L)))))
        val sig = key.sign(digest)
        return Rlp.encode(Rlp.Items(fields() + listOf(Rlp.of(chainId * 2 + 35 + sig.recId), Rlp.of(sig.r), Rlp.of(sig.s))))
    }
}
