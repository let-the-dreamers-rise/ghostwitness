package xyz.ghostwitness.app.store

import java.io.File
import java.io.IOException
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.mesh.HolderAction
import xyz.ghostwitness.core.session.SessionHeader

/**
 * What a holder phone keeps for other people's sessions, all ciphertext or public data. Layout:
 * `holdings/<session_id>/{header.bin, root.bin, entries/<i>.bin, shards/<i>-<j>.bin}`.
 */
class HoldingStore(root: File) {
    private val base = File(root, "holdings")

    /** Persists one ledger action. [header] is the ledger's current session, required for all but StoreSession. */
    fun persist(action: HolderAction, header: SessionHeader?) {
        when (action) {
            is HolderAction.StoreSession -> write(sessionDir(action.header), "header.bin", action.header.encode())
            is HolderAction.StoreEntry -> write(File(sessionDir(need(header)), "entries"), "${action.entry.index}.bin", action.entry.encode())
            is HolderAction.StoreRoot -> write(sessionDir(need(header)), "root.bin", action.root.encode())
            is HolderAction.StoreShard ->
                write(File(sessionDir(need(header)), "shards"), "${action.segmentIndex}-${action.shardIndex}.bin", action.shard)
            is HolderAction.Rejected -> Unit
        }
    }

    private fun need(header: SessionHeader?): SessionHeader = header ?: throw IOException("no session to store into")

    private fun sessionDir(header: SessionHeader) = File(base, Bytes.toHex(header.sessionId))

    private fun write(dir: File, name: String, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) throw IOException("could not write $name")
    }
}
