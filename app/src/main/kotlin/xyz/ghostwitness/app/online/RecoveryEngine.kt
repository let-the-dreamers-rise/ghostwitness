package xyz.ghostwitness.app.online

import android.content.Context
import java.io.File
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.recovery.RecoveryRecord
import xyz.ghostwitness.core.recovery.SegmentRecovery
import xyz.ghostwitness.core.recovery.SessionOwnership

data class RecoveryResult(val files: List<File>, val report: String)
data class OwnerRecording(val ownership: SessionOwnership)

class RecoveryEngine(private val context: Context) {
    fun discover(): List<OwnerRecording> {
        val settings = OnlineSettings(context)
        val auth = settings.walletAuthorization() ?: error("Sign in with your wallet first")
        val arkiv = ArkivClient(settings, OnlineStore(context.filesDir))
        return arkiv.query(auth.owner, null, "session").mapNotNull { entity ->
            runCatching {
                val attrs = ArkivClient.attributes(entity)
                SessionOwnership.decode(Bytes.fromHex(attrs.getValue("data").removePrefix("0x"))).also {
                    require(it.wallet.owner == auth.owner && it.wallet.origin == settings.keyService && it.sessionId == attrs.getValue("sid"))
                    require(it.wallet.recoveryPublicKey.contentEquals(auth.recoveryPublicKey))
                }
            }.getOrNull()
        }.groupBy { it.sessionId }.values.mapNotNull { group ->
            group.first().takeIf { first -> group.all { it.encode().contentEquals(first.encode()) } }
        }.sortedWith(compareByDescending<SessionOwnership> { it.header.startWallMs }.thenBy { it.sessionId }).map(::OwnerRecording)
    }

    fun recover(selected: OwnerRecording, progress: (String) -> Unit): RecoveryResult {
        val settings = OnlineSettings(context)
        val auth = settings.walletAuthorization() ?: error("Sign in with your wallet first")
        val ownership = selected.ownership
        require(ownership.verify() && ownership.wallet.owner == auth.owner && ownership.wallet.origin == settings.keyService)
        require(ownership.wallet.recoveryPublicKey.contentEquals(auth.recoveryPublicKey))
        val owner = auth.owner
        val sessionCode = ownership.sessionCode // Internal identity; no session code is entered by the owner.
        require(settings.bee.isNotBlank()) { "Configure a Bee HTTPS endpoint" }
        val sid = sessionCode.substringBefore(':')
        val arkiv = ArkivClient(settings, OnlineStore(context.filesDir))
        val bee = BeeClient(settings)
        progress("Reading segment proofs from Arkiv…")
        val records = arkiv.query(owner, sid, "segment").mapNotNull { entity ->
            runCatching {
                val attrs = ArkivClient.attributes(entity)
                val record = RecoveryRecord.decode(Bytes.fromHex(attrs.getValue("data").removePrefix("0x")))
                require(record.sessionCode == sessionCode && ("0x" + Bytes.toHex(record.owner)).equals(owner, true))
                require(record.header.encode().contentEquals(ownership.header.encode()))
                require(Bytes.toHex(Bytes.sha256(record.encode())) == attrs.getValue("recordHash"))
                record
            }.getOrNull()
        }.groupBy { it.entry.index }.mapValues { (_, candidates) ->
            require(candidates.map { Bytes.toHex(it.entry.encode()) }.distinct().size == 1) { "Conflicting signed segment entries" }
            candidates.first()
        }
        require(records.isNotEmpty()) { "No trusted uploaded segments found for this session" }
        val total = records.values.maxOf { it.root.uptoIndex } + 1
        require(total <= 100_000) { "Recording exceeds recovery limit" }
        val uploads = arkiv.query(owner, sid, "upload").mapNotNull { entity ->
            runCatching { ArkivClient.attributes(entity).also {
                require(it.getValue("uploader").equals(entity.getString("creator"), true))
                ArkivClient.quantity(it.getValue("i")).safeInt()
                require(ArkivClient.quantity(it.getValue("j")).safeInt() in 0..254)
                require(it.getValue("ref").matches(Regex("[0-9a-fA-F]{64}")))
                require(it.getValue("sha256").matches(Regex("[0-9a-fA-F]{64}")))
            } }.getOrNull()
        }.groupBy { ArkivClient.quantity(it.getValue("i")).safeInt() }
        val output = File(context.filesDir, "recovered/$sid").apply { mkdirs() }
        val files = mutableListOf<File>()
        val gaps = mutableListOf<Int>()
        val notes = mutableListOf<String>()
        var previousHash: ByteArray? = null
        progress("Unlocking this recording with your wallet authorization…")
        val contentKey = WalletKeys(context).unlock(ownership)
        try {
            for (i in 0 until total) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val record = records[i]
                if (record == null) { gaps += i; previousHash = null; continue }
                progress("Downloading segment ${i + 1}/$total from Swarm…")
                val shards = mutableMapOf<Int, ByteArray>()
                for (attrs in uploads[i].orEmpty()) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    if (shards.size >= record.header.dataShards) break
                    runCatching {
                        val j = ArkivClient.quantity(attrs.getValue("j")).safeInt()
                        require(j in 0 until record.header.totalShards)
                        if (j !in shards) {
                            val bytes = bee.fetch(attrs.getValue("ref"))
                            require(Bytes.toHex(Bytes.sha256(bytes)) == attrs.getValue("sha256"))
                            require(record.entry.shardRootMatches(j, bytes))
                            shards[j] = bytes
                        }
                    }
                }
                try {
                    val restored = SegmentRecovery.recoverWithKey(record, sessionCode, contentKey, shards, previousHash)
                    try {
                        val file = File(output, "$i.mp4")
                        OnlineStore.atomic(file, restored.plaintext)
                        files += file
                        previousHash = restored.ciphertextHash
                    } finally { restored.plaintext.fill(0) }
                } catch (error: Exception) {
                    gaps += i; previousHash = null
                    notes += "Segment $i: ${error.javaClass.simpleName}"
                }
            }
        } finally { contentKey.fill(0) }
        val report = "Recovered ${files.size}/$total segments in the known signed history.\nGaps: ${gaps.joinToString().ifBlank { "none" }}\n" +
            "Available segments verified against your wallet's recorder authorization.\n" +
            "A recording tail never uploaded cannot be discovered here.\n" +
            "Chain links across missing segments cannot be checked.\n" + notes.joinToString("\n")
        OnlineStore.atomic(File(output, "report.txt"), report.toByteArray())
        return RecoveryResult(files, report)
    }
}
