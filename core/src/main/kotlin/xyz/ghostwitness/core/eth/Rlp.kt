package xyz.ghostwitness.core.eth

import java.math.BigInteger
import xyz.ghostwitness.core.crypto.Bytes

/** Recursive Length Prefix encoding (Ethereum yellow paper, appendix B). Encode only. */
sealed interface Rlp {
    class Str(bytes: ByteArray) : Rlp {
        private val value = bytes.copyOf()
        val bytes: ByteArray get() = value.copyOf()
    }

    class Items(val items: List<Rlp>) : Rlp

    companion object {
        private const val SHORT_LIMIT = 55
        private const val STRING_OFFSET = 0x80
        private const val LIST_OFFSET = 0xC0

        fun of(bytes: ByteArray): Rlp = Str(bytes)

        /** A non-negative integer as its minimal big-endian bytes; zero is the empty string. */
        fun of(value: BigInteger): Rlp {
            require(value.signum() >= 0) { "RLP integers are non-negative" }
            return Str(unsignedBytes(value))
        }

        fun of(value: Long): Rlp = of(BigInteger.valueOf(value))

        fun list(vararg items: Rlp): Rlp = Items(items.toList())

        fun encode(item: Rlp): ByteArray = when (item) {
            is Str -> encodeString(item.bytes)
            is Items -> withLength(Bytes.concat(*item.items.map { encode(it) }.toTypedArray()), LIST_OFFSET)
        }

        fun unsignedBytes(value: BigInteger): ByteArray {
            if (value.signum() == 0) return ByteArray(0)
            val raw = value.toByteArray()
            return if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }

        private fun encodeString(bytes: ByteArray): ByteArray =
            if (bytes.size == 1 && (bytes[0].toInt() and 0xFF) < STRING_OFFSET) bytes else withLength(bytes, STRING_OFFSET)

        private fun withLength(payload: ByteArray, offset: Int): ByteArray {
            if (payload.size <= SHORT_LIMIT) return Bytes.concat(byteArrayOf((offset + payload.size).toByte()), payload)
            val length = unsignedBytes(BigInteger.valueOf(payload.size.toLong()))
            return Bytes.concat(byteArrayOf((offset + SHORT_LIMIT + length.size).toByte()), length, payload)
        }
    }
}
