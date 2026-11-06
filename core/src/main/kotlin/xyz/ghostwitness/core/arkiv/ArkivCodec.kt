package xyz.ghostwitness.core.arkiv

import java.math.BigInteger
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.eth.Abi
import xyz.ghostwitness.core.eth.AbiValue
import xyz.ghostwitness.core.eth.Keccak

/** Arkiv's typed-attribute execute protocol. See docs/online-flow.md for protocol references. */
object ArkivCodec {
    val address: ByteArray get() = Bytes.fromHex("4400000000000000000000000000000000000044")

    private val RESERVED = setOf(
        "and", "or", "not", "true", "false", "between", "startswith", "exists", "typeof", "order", "by", "asc", "desc",
        "bool", "i32", "u64", "u256", "dec", "bytes", "str", "addr", "key",
    )

    class Attribute(val name: String, val type: Int, val value: ByteArray) {
        init {
            require(name.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,31}")) && "--" !in name) { "invalid attribute name" }
            // Reserved by Arkiv's query language: such an attribute could be written but never queried.
            require(name.lowercase() !in RESERVED) { "'$name' is a reserved Arkiv query word" }
            require(type in listOf(7, 0x88, 0x83)) { "unsupported attribute type" }
            require(type != 0x88 || value.size <= 128) { "string too long" }
        }
    }

    fun str(name: String, value: String) = Attribute(name, 0x88, value.toByteArray(Charsets.UTF_8))
    fun bytes(name: String, value: ByteArray) = Attribute(name, 7, value.copyOf())
    fun number(name: String, value: Long) = Attribute(name, 0x83, Abi.word(BigInteger.valueOf(value).also { require(it.signum() >= 0) }))

    fun entityKey(chainId: Long, writer: ByteArray, nonce: Long, salt: ByteArray): ByteArray {
        require(writer.size == 20 && salt.size == 16 && nonce >= 0 && chainId > 0)
        return Keccak.hash(Abi.word(BigInteger.valueOf(chainId)), address, writer, Bytes.u64be(nonce), salt)
    }

    fun createAndTransfer(
        attributes: List<Attribute>, salt: ByteArray, chainId: Long, writer: ByteArray, entityNonce: Long, owner: ByteArray,
    ): ByteArray {
        require(salt.size == 16 && writer.size == 20 && owner.size == 20 && owner.any { it != 0.toByte() })
        require(attributes.size <= 32 && attributes.map { it.name }.distinct().size == attributes.size)
        require(attributes.filter { it.type == 7 }.sumOf { it.value.size } <= 131072)
        val attrs = attributes.sortedBy { it.name }.map { a ->
            AbiValue.Tuple(listOf(AbiValue.Word(a.name.toByteArray(Charsets.US_ASCII), false), AbiValue.uint(a.type.toLong()), AbiValue.DynBytes(a.value)))
        }
        val create = Abi.encode(listOf(AbiValue.Tuple(listOf(
            AbiValue.Uint(BigInteger(1, salt)), AbiValue.uint(0), AbiValue.uint(1_296_000),
            AbiValue.uint(1), AbiValue.Array(attrs),
        ))))
        val operations = mutableListOf<AbiValue>(AbiValue.Tuple(listOf(AbiValue.uint(1), AbiValue.DynBytes(create))))
        if (!writer.contentEquals(owner)) {
            val entityKey = entityKey(chainId, writer, entityNonce, salt)
            val transfer = Abi.encode(listOf(AbiValue.bytes32(entityKey), AbiValue.address(owner)))
            operations += AbiValue.Tuple(listOf(AbiValue.uint(4), AbiValue.DynBytes(transfer)))
        }
        return Abi.call(Keccak.selector("execute((uint8,bytes)[])"), AbiValue.Array(operations))
    }
}
