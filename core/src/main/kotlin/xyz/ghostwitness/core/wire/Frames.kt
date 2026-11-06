package xyz.ghostwitness.core.wire

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SESSION_ID_BYTES

class FrameFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val FRAME_VERSION = 1
private val SHARD_MAGIC = "GW2".toByteArray()
private val ACK_MAGIC = "GWA".toByteArray()

/**
 * Header frame sent as a small BYTES payload; the shard body follows as a FILE/STREAM payload with the same
 * [payloadId] (spec section 6.3). Fixed size, far below the 32 KB BYTES limit.
 */
class ShardHeader(
    sessionId: ByteArray,
    val segmentIndex: Int,
    val shardIndex: Int,
    val totalShards: Int,
    val dataShards: Int,
    val ctLen: Int,
    val tStartMs: Long,
    val durMs: Int,
    val shardLen: Int,
    shardRoot: ByteArray,
    val payloadId: Long,
) {
    private val sessionIdBytes = sessionId.copyOf()
    private val shardRootBytes = shardRoot.copyOf()

    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
        require(shardRoot.size == Bytes.HASH_BYTES) { "shard root must be ${Bytes.HASH_BYTES} bytes" }
        require(shardIndex in 0 until totalShards && dataShards in 1..totalShards) { "bad shard indices" }
    }

    val sessionId: ByteArray get() = sessionIdBytes.copyOf()
    val shardRoot: ByteArray get() = shardRootBytes.copyOf()

    fun encode(): ByteArray = Bytes.concat(
        SHARD_MAGIC, Bytes.u8(FRAME_VERSION), sessionIdBytes,
        Bytes.u32be(segmentIndex), Bytes.u8(shardIndex), Bytes.u8(totalShards), Bytes.u8(dataShards),
        Bytes.u32be(ctLen), Bytes.u64be(tStartMs), Bytes.u16be(durMs), Bytes.u32be(shardLen),
        shardRootBytes, Bytes.u64be(payloadId),
    )

    companion object {
        const val ENCODED_BYTES = 3 + 1 + SESSION_ID_BYTES + 4 + 1 + 1 + 1 + 4 + 8 + 2 + 4 + Bytes.HASH_BYTES + 8

        fun decode(bytes: ByteArray): ShardHeader = parse(bytes) { buffer ->
            if (bytes.size != ENCODED_BYTES) throw FrameFormatException("shard header must be $ENCODED_BYTES bytes")
            expectMagic(buffer, SHARD_MAGIC)
            ShardHeader(
                sessionId = buffer.bytes(SESSION_ID_BYTES),
                segmentIndex = buffer.int,
                shardIndex = buffer.unsignedByte(),
                totalShards = buffer.unsignedByte(),
                dataShards = buffer.unsignedByte(),
                ctLen = buffer.int,
                tStartMs = buffer.long,
                durMs = buffer.short.toInt() and 0xFFFF,
                shardLen = buffer.int,
                shardRoot = buffer.bytes(Bytes.HASH_BYTES),
                payloadId = buffer.long,
            )
        }
    }
}

/** One acknowledged shard: the holder has it on disk and it hashes to [shardHash]. */
class AckEntry(val segmentIndex: Int, val shardIndex: Int, shardHash: ByteArray) {
    private val hashBytes = shardHash.copyOf()

    init {
        require(shardHash.size == Bytes.HASH_BYTES) { "shard hash must be ${Bytes.HASH_BYTES} bytes" }
    }

    val shardHash: ByteArray get() = hashBytes.copyOf()

    internal fun encode(): ByteArray = Bytes.concat(Bytes.u32be(segmentIndex), Bytes.u8(shardIndex), hashBytes)
}

/** Batched ACK frame (sent every ~300 ms per peer): `"GWA" | ver | session_id | u16 count | entries`. */
class AckFrame(sessionId: ByteArray, entries: List<AckEntry>) {
    private val sessionIdBytes = sessionId.copyOf()
    val entries: List<AckEntry> = entries.toList()

    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
        require(entries.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES entries per frame" }
    }

    val sessionId: ByteArray get() = sessionIdBytes.copyOf()

    fun encode(): ByteArray = Bytes.concat(
        ACK_MAGIC, Bytes.u8(FRAME_VERSION), sessionIdBytes, Bytes.u16be(entries.size),
        *entries.map { it.encode() }.toTypedArray(),
    )

    companion object {
        const val MAX_ENTRIES = 0xFFFF
        private const val ENTRY_BYTES = 4 + 1 + Bytes.HASH_BYTES

        fun decode(bytes: ByteArray): AckFrame = parse(bytes) { buffer ->
            expectMagic(buffer, ACK_MAGIC)
            val sessionId = buffer.bytes(SESSION_ID_BYTES)
            val count = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() != count * ENTRY_BYTES) throw FrameFormatException("ack entry count mismatch")
            AckFrame(sessionId, List(count) { AckEntry(buffer.int, buffer.unsignedByte(), buffer.bytes(Bytes.HASH_BYTES)) })
        }
    }
}

private fun <T> parse(bytes: ByteArray, block: (ByteBuffer) -> T): T {
    try {
        return block(ByteBuffer.wrap(bytes))
    } catch (e: BufferUnderflowException) {
        throw FrameFormatException("frame truncated", e)
    } catch (e: IllegalArgumentException) {
        throw FrameFormatException(e.message ?: "invalid frame", e)
    }
}

private fun expectMagic(buffer: ByteBuffer, magic: ByteArray) {
    val found = buffer.bytes(magic.size)
    if (!found.contentEquals(magic)) throw FrameFormatException("bad magic")
    val version = buffer.unsignedByte()
    if (version != FRAME_VERSION) throw FrameFormatException("unsupported frame version $version")
}

private fun ByteBuffer.bytes(size: Int): ByteArray = ByteArray(size).also { get(it) }

private fun ByteBuffer.unsignedByte(): Int = get().toInt() and 0xFF
