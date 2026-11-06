package xyz.ghostwitness.core.eth

import java.math.BigInteger
import xyz.ghostwitness.core.crypto.Bytes

/** An EIP-1559 (type 2) transaction with an empty access list. */
class Eip1559Tx(
    val chainId: Long,
    val nonce: Long,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val gasLimit: Long,
    to: ByteArray,
    val value: BigInteger,
    data: ByteArray,
) {
    private val toBytes = to.copyOf()
    private val dataBytes = data.copyOf()

    init {
        require(to.size == ADDRESS_BYTES) { "to must be an address" }
        require(chainId > 0 && nonce >= 0 && gasLimit > 0) { "bad transaction fields" }
    }

    /** Keccak of `0x02 || rlp(fields)`: what the sender signs. */
    fun signingHash(): ByteArray = Keccak.hash(byteArrayOf(TYPE), Rlp.encode(Rlp.Items(fields())))

    /** `0x02 || rlp(fields ++ [yParity, r, s])`, ready for eth_sendRawTransaction. */
    fun signedBy(key: EthKey): ByteArray {
        val signature = key.sign(signingHash())
        val signed = fields() + listOf(Rlp.of(signature.recId.toLong()), Rlp.of(signature.r), Rlp.of(signature.s))
        return Bytes.concat(byteArrayOf(TYPE), Rlp.encode(Rlp.Items(signed)))
    }

    private fun fields(): List<Rlp> = listOf(
        Rlp.of(chainId),
        Rlp.of(nonce),
        Rlp.of(maxPriorityFeePerGas),
        Rlp.of(maxFeePerGas),
        Rlp.of(gasLimit),
        Rlp.of(toBytes),
        Rlp.of(value),
        Rlp.of(dataBytes),
        Rlp.Items(emptyList()),
    )

    private companion object {
        const val TYPE: Byte = 0x02
    }
}
