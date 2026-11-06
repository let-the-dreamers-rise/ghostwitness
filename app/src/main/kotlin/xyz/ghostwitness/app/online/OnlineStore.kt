package xyz.ghostwitness.app.online

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.recovery.RecoveryRecord

/** Shards on disk are the durable queue; journals checkpoint uploads and exact signed transactions. */
class OnlineStore(private val root: File) {
    data class Task(val sessionDir: File, val record: RecoveryRecord, val shardIndex: Int, val shardFile: File)

    fun saveRecord(record: RecoveryRecord) {
        require(record.verify())
        val dir = File(root, "holdings/${Bytes.toHex(record.header.sessionId)}/records")
        val file = File(dir, "${record.entry.index}.bin")
        if (file.exists()) {
            val old = RecoveryRecord.decode(file.readBytes())
            require(old.sessionCode == record.sessionCode && old.entry.encode().contentEquals(record.entry.encode()) && old.owner.contentEquals(record.owner)) { "Conflicting session metadata" }
            return
        }
        atomic(file, record.encode())
    }

    fun tasks(): List<Task> = File(root, "holdings").listFiles().orEmpty().filter { it.isDirectory }.flatMap { dir ->
        File(dir, "records").listFiles().orEmpty().filter { it.extension == "bin" }.flatMap { file ->
            val record = runCatching { RecoveryRecord.decode(file.readBytes()) }.getOrNull() ?: return@flatMap emptyList()
            (0 until record.header.totalShards).mapNotNull { j ->
                File(dir, "shards/${record.entry.index}-$j.bin").takeIf { it.isFile }?.let { Task(dir, record, j, it) }
            }
        }
    }

    fun journal(file: File): JSONObject = if (AtomicFile(file).baseFile.exists()) {
        JSONObject(String(AtomicFile(file).openRead().use { it.readBytes() }, Charsets.UTF_8))
    } else JSONObject()

    fun checkpoint(file: File, state: JSONObject) = atomic(file, state.toString().toByteArray(Charsets.UTF_8))

    fun summary(): String {
        val tasks = tasks()
        val states = tasks.map { journal(File(it.sessionDir, "uploads/${it.record.entry.index}-${it.shardIndex}.json")) }
        val uploaded = states.count { it.optBoolean("indexed") }
        val paid = states.count { it.optString("reward") in listOf("PAID", "ALREADY_PAID") }
        val elsewhere = states.count { it.optString("reward") == "PAID_ELSEWHERE" }
        return "Saved shards: ${tasks.size}; online and indexed: $uploaded; pending: ${tasks.size - uploaded}\n" +
            "Reward claims confirmed by verifier: $paid; already rewarded another witness: $elsewhere; pending: ${uploaded - paid - elsewhere}."
    }

    companion object {
        fun atomic(file: File, bytes: ByteArray) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Could not create storage" }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
    }
}
