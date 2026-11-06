package xyz.ghostwitness.core.eth

import java.math.BigInteger
import xyz.ghostwitness.core.crypto.Bytes

/** Solidity ABI values, just the kinds GhostWitness sends. */
sealed interface AbiValue {
    val isDynamic: Boolean

    class Uint(val value: BigInteger) : AbiValue {
        init {
            require(value.signum() >= 0 && value.bitLength() <= WORD_BITS) { "uint256 out of range" }
        }

        override val isDynamic = false
    }

    /** bytes32, or an address (20 bytes, left-padded) when [leftPad] is true. */
    class Word(bytes: ByteArray, val leftPad: Boolean) : AbiValue {
        private val value = bytes.copyOf()
        val bytes: ByteArray get() = value.copyOf()

        init {
            require(bytes.size <= WORD) { "a word holds at most $WORD bytes" }
        }

        override val isDynamic = false
    }

    class DynBytes(bytes: ByteArray) : AbiValue {
        private val value = bytes.copyOf()
        val bytes: ByteArray get() = value.copyOf()
        override val isDynamic = true
    }

    class Tuple(val items: List<AbiValue>) : AbiValue {
        override val isDynamic: Boolean get() = items.any { it.isDynamic }
    }

    class Array(val items: List<AbiValue>) : AbiValue {
        override val isDynamic = true
    }

    companion object {
        const val WORD = 32
        const val WORD_BITS = 256

        fun uint(value: Long): AbiValue = Uint(BigInteger.valueOf(value))

        fun address(bytes: ByteArray): AbiValue {
            require(bytes.size == ADDRESS_BYTES) { "address must be $ADDRESS_BYTES bytes" }
            return Word(bytes, leftPad = true)
        }

        fun bytes32(bytes: ByteArray): AbiValue {
            require(bytes.size == WORD) { "bytes32 must be $WORD bytes" }
            return Word(bytes, leftPad = false)
        }
    }
}

const val ADDRESS_BYTES = 20

/** Head/tail ABI encoding, as `abi.encode(...)`. */
object Abi {
    fun encode(values: List<AbiValue>): ByteArray {
        val heads = values.map { if (it.isDynamic) null else encodeStatic(it) }
        val headSize = heads.sumOf { it?.size ?: AbiValue.WORD }
        val tails = values.map { if (it.isDynamic) encodeDynamic(it) else ByteArray(0) }
        val (headParts, _) = values.indices.fold(emptyList<ByteArray>() to headSize) { (acc, offset), i ->
            val head = heads[i] ?: word(BigInteger.valueOf(offset.toLong()))
            (acc + head) to offset + tails[i].size
        }
        return Bytes.concat(*(headParts + tails).toTypedArray())
    }

    fun call(selector: ByteArray, vararg values: AbiValue): ByteArray = Bytes.concat(selector, encode(values.toList()))

    fun word(value: BigInteger): ByteArray {
        val raw = Rlp.unsignedBytes(value)
        return Bytes.concat(ByteArray(AbiValue.WORD - raw.size), raw)
    }

    private fun encodeStatic(value: AbiValue): ByteArray = when (value) {
        is AbiValue.Uint -> word(value.value)
        is AbiValue.Word -> {
            val bytes = value.bytes
            val pad = ByteArray(AbiValue.WORD - bytes.size)
            if (value.leftPad) Bytes.concat(pad, bytes) else Bytes.concat(bytes, pad)
        }
        is AbiValue.Tuple -> encode(value.items)
        else -> throw IllegalArgumentException("not a static value")
    }

    private fun encodeDynamic(value: AbiValue): ByteArray = when (value) {
        is AbiValue.DynBytes -> {
            val bytes = value.bytes
            val padded = (bytes.size + AbiValue.WORD - 1) / AbiValue.WORD * AbiValue.WORD
            Bytes.concat(word(BigInteger.valueOf(bytes.size.toLong())), bytes.copyOf(padded))
        }
        is AbiValue.Array -> Bytes.concat(word(BigInteger.valueOf(value.items.size.toLong())), encode(value.items))
        is AbiValue.Tuple -> encode(value.items)
        else -> throw IllegalArgumentException("not a dynamic value")
    }
}
