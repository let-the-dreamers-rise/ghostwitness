package xyz.ghostwitness.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Small byte helpers shared by the codec, manifest and wire formats. All functions return new arrays. */
object Bytes {
    const val HASH_BYTES = 32

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    fun u8(value: Int): ByteArray {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        return byteArrayOf(value.toByte())
    }

    fun u16be(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        return ByteBuffer.allocate(2).putShort(value.toShort()).array()
    }

    fun u32be(value: Int): ByteArray {
        require(value >= 0) { "u32 must be non-negative: $value" }
        return ByteBuffer.allocate(4).putInt(value).array()
    }

    fun u64be(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

    fun zeros(size: Int): ByteArray = ByteArray(size)

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
