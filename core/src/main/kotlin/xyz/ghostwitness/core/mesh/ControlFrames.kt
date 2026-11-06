package xyz.ghostwitness.core.mesh

import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SESSION_ID_BYTES
import xyz.ghostwitness.core.manifest.ManifestEntry
import xyz.ghostwitness.core.manifest.ManifestFormatException
import xyz.ghostwitness.core.manifest.SignedRoot
import xyz.ghostwitness.core.session.SessionFormatException
import xyz.ghostwitness.core.session.SessionHeader
import xyz.ghostwitness.core.wire.AckFrame
import xyz.ghostwitness.core.wire.FrameFormatException
import xyz.ghostwitness.core.wire.ShardHeader

private val ENTRY_MAGIC = "GWE".toByteArray()
private const val ENTRY_VERSION = 1
private const val MAGIC_BYTES = 3

/** A manifest entry sent to each holder of a segment: `"GWE" | ver | session_id(16) | entry`. */
class EntryFrame(sessionId: ByteArray, val entry: ManifestEntry) {
    private val sessionIdBytes = sessionId.copyOf()

    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
    }

    val sessionId: ByteArray get() = sessionIdBytes.copyOf()

    fun encode(): ByteArray = Bytes.concat(ENTRY_MAGIC, Bytes.u8(ENTRY_VERSION), sessionIdBytes, entry.encode())

    companion object {
        fun decode(bytes: ByteArray): EntryFrame {
            val prefix = MAGIC_BYTES + 1 + SESSION_ID_BYTES
            if (bytes.size <= prefix) throw FrameFormatException("entry frame too short")
            if (!bytes.copyOfRange(0, MAGIC_BYTES).contentEquals(ENTRY_MAGIC)) throw FrameFormatException("bad magic")
            val version = bytes[MAGIC_BYTES].toInt() and 0xFF
            if (version != ENTRY_VERSION) throw FrameFormatException("unsupported entry frame version $version")
            val sessionId = bytes.copyOfRange(MAGIC_BYTES + 1, prefix)
            return EntryFrame(sessionId, ManifestEntry.decode(bytes.copyOfRange(prefix, bytes.size)))
        }
    }
}

/** Everything that travels as a Nearby BYTES payload, told apart by its 3-byte magic. */
sealed interface ControlFrame {
    class Session(val header: SessionHeader) : ControlFrame

    class Root(val root: SignedRoot) : ControlFrame

    class Entry(val frame: EntryFrame) : ControlFrame

    class Shard(val header: ShardHeader) : ControlFrame

    class Ack(val frame: AckFrame) : ControlFrame
}

object ControlCodec {
    fun decode(bytes: ByteArray): ControlFrame {
        if (bytes.size < MAGIC_BYTES) throw FrameFormatException("frame too short")
        val magic = String(bytes, 0, MAGIC_BYTES, Charsets.US_ASCII)
        try {
            return when (magic) {
                "GWS" -> ControlFrame.Session(SessionHeader.decode(bytes))
                "GWR" -> ControlFrame.Root(SignedRoot.decode(bytes))
                "GWE" -> ControlFrame.Entry(EntryFrame.decode(bytes))
                "GW2" -> ControlFrame.Shard(ShardHeader.decode(bytes))
                "GWA" -> ControlFrame.Ack(AckFrame.decode(bytes))
                else -> throw FrameFormatException("unknown frame type")
            }
        } catch (e: SessionFormatException) {
            throw FrameFormatException(e.message ?: "bad session header", e)
        } catch (e: ManifestFormatException) {
            throw FrameFormatException(e.message ?: "bad manifest frame", e)
        }
    }
}
