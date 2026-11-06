package xyz.ghostwitness.core.recovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.manifest.SignedRoot
import xyz.ghostwitness.core.merkle.Merkle
import xyz.ghostwitness.core.session.SessionHeader

/** Independently verifiable segment metadata. Missing earlier entries do not prevent recovery. */
class RecoveryRecord(
    val header: SessionHeader,
    owner: ByteArray,
    val entry: ManifestEntry,
    val root: SignedRoot,
    proof: List<ByteArray>,
) {
    private val ownerBytes = owner.copyOf()
    private val path = proof.map { it.copyOf() }
    val owner: ByteArray get() = ownerBytes.copyOf()
    val sessionCode: String get() = Bytes.toHex(header.sessionId) + ":" + Bytes.toHex(Bytes.sha256(header.sessionPublicKey))

    init {
        require(owner.size == 20 && owner.any { it != 0.toByte() }) { "owner wallet required" }
        require(path.size <= 31 && path.all { it.size == 32 }) { "invalid inclusion proof" }
    }

    fun verify(): Boolean = header.sessionId.contentEquals(root.sessionId) &&
        entry.shardCount == header.totalShards && root.uptoIndex < Int.MAX_VALUE &&
        root.verify(header.sessionPublicKey) &&
        Merkle.verify(entry.leafHash(), entry.index, root.uptoIndex + 1, path, root.root)

    fun encode(): ByteArray = ByteArrayOutputStream().also { stream ->
        DataOutputStream(stream).use { out ->
            out.writeBytes("GWP")
            out.writeByte(1)
            val h = header.encode()
            val e = entry.encode()
            out.writeShort(h.size)
            out.write(h)
            out.write(ownerBytes)
            out.writeShort(e.size)
            out.write(e)
            out.write(root.encode())
            out.writeByte(path.size)
            path.forEach(out::write)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): RecoveryRecord {
            require(bytes.size <= 4096) { "recovery record too large" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                fun read(n: Int) = ByteArray(n).also(input::readFully)
                require(String(read(3), Charsets.US_ASCII) == "GWP" && input.readUnsignedByte() == 1) { "bad recovery record" }
                val header = SessionHeader.decode(read(input.readUnsignedShort()))
                val owner = read(20)
                val entry = ManifestEntry.decode(read(input.readUnsignedShort()))
                val root = SignedRoot.decode(read(SignedRoot.ENCODED_BYTES))
                val count = input.readUnsignedByte()
                require(count <= 31) { "proof too long" }
                val proof = List(count) { read(32) }
                require(input.available() == 0) { "trailing recovery data" }
                RecoveryRecord(header, owner, entry, root, proof).also { require(it.verify()) { "invalid signed segment proof" } }
            }
        }
    }
}
