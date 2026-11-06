package xyz.ghostwitness.app.store

import java.io.File
import java.io.IOException
import xyz.ghostwitness.core.codec.EncodedSegment
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.manifest.SignedRoot
import xyz.ghostwitness.core.session.SessionHeader

/**
 * The recording phone's own copy of a session: header, manifest entries, shards and the newest signed
 * root, all ciphertext or public data. The mesh layer resends from here. Layout:
 * `sessions/<session_id>/{header.bin, root.bin, entries/<i>.bin, shards/<i>-<j>.bin}`.
 */
class SessionStore(root: File, header: SessionHeader) {
    val dir: File = File(root, "sessions/${Bytes.toHex(header.sessionId)}")
    private val entries = File(dir, "entries")
    private val shards = File(dir, "shards")

    init {
        listOf(entries, shards).forEach { if (!it.isDirectory && !it.mkdirs()) throw IOException("could not create $it") }
        writeAtomically(File(dir, "header.bin"), header.encode())
    }

    fun writeSegment(segment: EncodedSegment, root: SignedRoot) {
        val index = segment.entry.index
        segment.shards.forEachIndexed { j, shard -> writeAtomically(File(shards, "$index-$j.bin"), shard) }
        writeAtomically(File(entries, "$index.bin"), segment.entry.encode())
        writeAtomically(File(dir, "root.bin"), root.encode())
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) throw IOException("could not write ${target.name}")
    }
}
