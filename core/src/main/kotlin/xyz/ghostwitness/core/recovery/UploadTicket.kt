package xyz.ghostwitness.core.recovery

import java.nio.ByteBuffer
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SessionSigner

/** Recorder authorization binds a shard reward to its receiving witness's writer address. */
class UploadTicket private constructor(private val bytes: ByteArray) {
    val sessionId: ByteArray get() = bytes.copyOfRange(4, 20)
    val index: Int get() = ByteBuffer.wrap(bytes, 20, 4).int
    val shard: Int get() = bytes[24].toInt() and 255
    val uploader: ByteArray get() = bytes.copyOfRange(25, 45)
    val shardHash: ByteArray get() = bytes.copyOfRange(45, 77)
    fun encode(): ByteArray = bytes.copyOf()
    fun verify(record: RecoveryRecord): Boolean = record.verify() && index == record.entry.index &&
        sessionId.contentEquals(record.header.sessionId) && shard in 0 until record.header.totalShards &&
        SessionSigner.verify(record.header.sessionPublicKey, message(bytes.copyOfRange(4, 77)), bytes.copyOfRange(77, 141))

    companion object {
        private fun message(body: ByteArray) = Bytes.concat("GWASSIGN".toByteArray(Charsets.US_ASCII), body)
        fun create(sid: ByteArray, index: Int, shard: Int, uploader: ByteArray, hash: ByteArray, signer: SessionSigner): UploadTicket {
            require(sid.size == 16 && uploader.size == 20 && hash.size == 32 && index >= 0 && shard in 0..254)
            val body = Bytes.concat(sid, Bytes.u32be(index), Bytes.u8(shard), uploader, hash)
            return UploadTicket(Bytes.concat("GWT".toByteArray(), Bytes.u8(1), body, signer.sign(message(body))))
        }
        fun decode(bytes: ByteArray): UploadTicket {
            require(bytes.size == 141 && String(bytes, 0, 3, Charsets.US_ASCII) == "GWT" && bytes[3] == 1.toByte()) { "Invalid upload ticket" }
            return UploadTicket(bytes.copyOf()).also { require(it.index >= 0) }
        }
    }
}
