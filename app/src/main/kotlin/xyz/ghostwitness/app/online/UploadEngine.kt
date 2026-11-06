package xyz.ghostwitness.app.online

import android.content.Context
import java.io.File
import xyz.ghostwitness.core.arkiv.ArkivCodec
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.eth.EthKey
import xyz.ghostwitness.core.recovery.UploadTicket
import org.json.JSONObject
import xyz.ghostwitness.core.recovery.SessionOwnership

/** Only receiving witness holdings are uploaded here; capture never waits for internet. */
class UploadEngine(context: Context) {
    private val settings = OnlineSettings(context)
    private val store = OnlineStore(context.filesDir)
    private val bee = BeeClient(settings)
    private val arkiv = ArkivClient(settings, store)

    fun run(): Boolean = synchronized(LOCK) {
        if (!settings.uploadsConfigured()) return false
        var retry = false
        val uploader = EthKey.checksumless(settings.writer().address)
        for (task in store.tasks()) {
            if (Thread.currentThread().isInterrupted) return true
            try { process(task, uploader) } catch (_: IllegalArgumentException) {
                settingsStatus("A stored contribution failed validation; other files will continue")
            } catch (error: Exception) {
                retry = true
                // Do not retain URLs, access keys, private keys or service response bodies in status:
                // only this app's own OnlineIssue messages are shown.
                settingsStatus("Upload pending: " + if (error is OnlineIssue) error.message else error.javaClass.simpleName)
                // One outstanding writer transaction at a time keeps entity-nonce prediction correct.
                break
            }
        }
        // Rewards are a separate pass: an unavailable verifier must never block evidence uploads.
        for (task in store.tasks()) {
            if (Thread.currentThread().isInterrupted) return true
            val journal = File(task.sessionDir, "uploads/${task.record.entry.index}-${task.shardIndex}.json")
            try {
                if (store.journal(journal).optBoolean("indexed")) claimReward(journal)
            } catch (_: Exception) { retry = true; settingsStatus("Evidence upload progress saved; reward verification pending") }
        }
        if (!retry) settingsStatus(store.summary())
        retry
    }

    private fun process(task: OnlineStore.Task, uploader: String) {
        val record = task.record
        val sid = Bytes.toHex(record.header.sessionId)
        val owner = "0x" + Bytes.toHex(record.owner)
        val bytes = task.shardFile.readBytes()
        require(record.verify() && record.entry.shardRootMatches(task.shardIndex, bytes)) { "Invalid stored shard" }
        val journal = File(task.sessionDir, "uploads/${record.entry.index}-${task.shardIndex}.json")
        val ticketFile = File(task.sessionDir, "tickets/${record.entry.index}-${task.shardIndex}.bin")
        if (!ticketFile.exists()) return // Transfer may still be delivering its assignment.
        val ticket = UploadTicket.decode(ticketFile.readBytes())
        require(ticket.verify(record) && ticket.shard == task.shardIndex && ticket.shardHash.contentEquals(Bytes.sha256(bytes)) &&
            EthKey.checksumless(ticket.uploader) == uploader) { "Invalid upload assignment" }
        val state = store.journal(journal)
        // Ownership may arrive after the shard was already indexed. Still publish its discovery entry.
        if (!state.has("reference")) {
            val ref = bee.upload(bytes)
            store.checkpoint(journal, state.put("reference", ref))
        }
        val reference = state.getString("reference")
        if (!state.optBoolean("readbackVerified")) {
            require(bee.fetch(reference).contentEquals(bytes)) { "Swarm readback mismatch" }
            store.checkpoint(journal, state.put("readbackVerified", true))
        }
        val encoded = record.encode()
        val ownershipFile = File(task.sessionDir, "ownership.bin")
        if (ownershipFile.exists()) {
            val ownership = SessionOwnership.decode(ownershipFile.readBytes())
            require(ownership.header.encode().contentEquals(record.header.encode()) && ownership.wallet.owner == owner)
            val sessionBytes = ownership.encode()
            val sessionDedupe = Bytes.toHex(Bytes.sha256("GW-SESSION".toByteArray(), sessionBytes))
            arkiv.publish(owner, sid, "session", sessionDedupe, common(sid, "session", sessionDedupe) + listOf(
                ArkivCodec.number("startedAt", ownership.header.startWallMs), ArkivCodec.str("recorder", ownership.wallet.recorder),
                ArkivCodec.bytes("data", sessionBytes),
            ), File(task.sessionDir, "uploads/session.json"))
        }
        if (state.optBoolean("indexed")) return
        val recordHash = Bytes.toHex(Bytes.sha256(encoded))
        val metadataId = Bytes.toHex(Bytes.sha256("GW-METADATA".toByteArray(), encoded))
        arkiv.publish(owner, sid, "segment", metadataId, common(sid, "segment", metadataId) + listOf(
            ArkivCodec.number("i", record.entry.index.toLong()), ArkivCodec.str("recordHash", recordHash),
            ArkivCodec.bytes("data", encoded),
        ), File(task.sessionDir, "uploads/metadata-${record.entry.index}.json"))
        val shardHash = Bytes.toHex(Bytes.sha256(bytes))
        val contribution = Bytes.toHex(Bytes.sha256("GW-UPLOAD".toByteArray(), record.header.sessionId,
            Bytes.u32be(record.entry.index), Bytes.u8(task.shardIndex), Bytes.fromHex(uploader.drop(2)), Bytes.fromHex(reference)))
        arkiv.publish(owner, sid, "upload", contribution, common(sid, "upload", contribution) + listOf(
            ArkivCodec.number("i", record.entry.index.toLong()), ArkivCodec.number("j", task.shardIndex.toLong()),
            ArkivCodec.str("ref", reference), ArkivCodec.str("sha256", shardHash), ArkivCodec.str("uploader", uploader),
            ArkivCodec.str("recordHash", recordHash), ArkivCodec.number("size", bytes.size.toLong()),
            ArkivCodec.bytes("ticket", ticket.encode()),
        ), journal)
        val confirmed = store.journal(journal)
        store.checkpoint(journal, confirmed.put("reward", "AWAITING_VERIFIER").put("contribution", contribution))
    }

    private fun claimReward(journal: File) {
        val state = store.journal(journal)
        if (settings.rewards.isBlank() || state.optString("reward") in listOf("PAID", "ALREADY_PAID", "PAID_ELSEWHERE")) return
        require(state.optBoolean("indexed") && state.optBoolean("readbackVerified"))
        val response = JSONObject(String(Http.request(settings.rewards + "/claim",
            JSONObject().put("entityKey", state.getString("entity")).toString().toByteArray(), mapOf("Content-Type" to "application/json")), Charsets.UTF_8))
        val reward = response.getString("state")
        require(reward in listOf("PAID", "ALREADY_PAID", "PAID_ELSEWHERE")) { "Verification pending" }
        store.checkpoint(journal, state.put("reward", reward).put("rewardTransaction", response.optString("transaction")))
    }

    private fun common(sid: String, kind: String, dedupe: String) = listOf(
        ArkivCodec.str("app", "ghostwitness"), ArkivCodec.str("sid", sid), ArkivCodec.str("kind", kind),
        ArkivCodec.str("dedupe", dedupe), ArkivCodec.number("v", 3),
    )
    private fun settingsStatus(message: String) { status = message }

    companion object {
        private val LOCK = Any()
        @Volatile var status: String = "Waiting for configured internet upload"
            private set
    }
}
