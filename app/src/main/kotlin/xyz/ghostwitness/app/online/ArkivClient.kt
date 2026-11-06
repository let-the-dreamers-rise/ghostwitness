package xyz.ghostwitness.app.online

import java.io.File
import java.io.IOException
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import xyz.ghostwitness.core.arkiv.ArkivCodec
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.eth.Abi
import xyz.ghostwitness.core.eth.AbiValue
import xyz.ghostwitness.core.eth.LegacyTx
import xyz.ghostwitness.core.eth.EthKey
import xyz.ghostwitness.core.eth.Keccak

class ArkivClient(private val settings: OnlineSettings, private val store: OnlineStore) {
    private val contract = "0x" + Bytes.toHex(ArkivCodec.address)

    fun rpc(method: String, params: JSONArray = JSONArray()): Any? {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val response = JSONObject(String(Http.request(settings.rpc, request.toString().toByteArray(), mapOf("Content-Type" to "application/json")), Charsets.UTF_8))
        if (response.has("error")) throw OnlineIssue("Arkiv $method failed (${response.getJSONObject("error").optInt("code")})")
        return response.opt("result").takeUnless { it == JSONObject.NULL }
    }

    fun query(owner: String, sid: String?, kind: String, extra: String = ""): List<JSONObject> {
        require(owner.matches(Regex("0x[0-9a-fA-F]{40}")) && (sid == null || sid.matches(Regex("[0-9a-f]{32}"))))
        require(kind in listOf("session", "segment", "upload") && (sid != null || kind == "session"))
        val filter = sid?.let { " AND sid = str('$it')" }.orEmpty()
        val base = "app = str('ghostwitness') AND kind = str('$kind') AND \$owner = addr($owner)" + filter
        val result = mutableListOf<JSONObject>()
        var last = ""
        repeat(1000) {
            val after = if (last.isEmpty()) "" else " AND \$key > key($last)"
            val options = JSONObject().put("limit", "0x64").put("select", JSONObject().put("key", true).put("owner", true).put("creator", true).put("attributes", true))
            val page = (rpc("arkiv_query", JSONArray().put(base + extra + after + " ORDER BY \$key ASC").put(options)) as JSONObject).getJSONArray("data")
            if (page.length() == 0) return result
            for (i in 0 until page.length()) result += page.getJSONObject(i)
            val next = page.getJSONObject(page.length() - 1).getString("key")
            require(next.matches(Regex("0x[0-9a-fA-F]{64}")) && next > last) { "Invalid Arkiv pagination" }
            last = next
            if (page.length() < 100) return result
        }
        throw IOException("Session exceeds query limit")
    }

    /** Persist the exact raw transaction before broadcasting; retries never create a second payment/write. */
    fun publish(owner: String, sid: String, kind: String, dedupe: String, attributes: List<ArkivCodec.Attribute>, journalFile: File) {
        val state = store.journal(journalFile)
        if (state.optBoolean("indexed")) return
        val writer = settings.writer()
        val address = EthKey.checksumless(writer.address)
        require(dedupe.matches(Regex("[0-9a-f]{64}")))
        if (state.optBoolean("reverted")) throw OnlineIssue("Arkiv write reverted; operator reconciliation required")
        if (!state.has("raw")) {
            val found = query(owner, sid, kind, " AND dedupe = str('$dedupe') AND \$creator = addr($address)")
            if (found.isNotEmpty()) {
                verifyEntity(found.first().getString("key"), owner, address, attributes)
                store.checkpoint(journalFile, state.put("indexed", true).put("entity", found.first().getString("key")))
                return
            }
            val chain = quantity(rpc("eth_chainId") as String).safeLong()
            // Setup problems are retryable and shown on screen, not treated as a bad stored file.
            need(chain == 7794095L, "Configure Arkiv Rabdi (chain 7794095)")
            val nonce = quantity(rpc("eth_getTransactionCount", JSONArray().put(address).put("pending")) as String).safeLong()
            val latestNonce = quantity(rpc("eth_getTransactionCount", JSONArray().put(address).put("latest")) as String).safeLong()
            need(nonce == latestNonce, "A writer transaction is already pending")
            val call = Abi.call(Keccak.selector("entityNonce(address)"), AbiValue.address(writer.address))
            val entityNonce = quantity(rpc("eth_call", JSONArray().put(JSONObject().put("to", contract).put("data", hex(call))).put("latest")) as String).safeLong()
            val data = ArkivCodec.createAndTransfer(attributes, Bytes.fromHex(dedupe.take(32)), chain, writer.address, entityNonce, Bytes.fromHex(owner.drop(2)))
            val gas = quantity(rpc("eth_estimateGas", JSONArray().put(JSONObject().put("from", address).put("to", contract).put("data", hex(data)).put("value", "0x0"))) as String)
                .multiply(BigInteger.valueOf(2)).safeLong().coerceAtLeast(if (owner.equals(address, true)) 1_000_000 else 1_400_000)
            val block = rpc("eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject
            need(BigInteger.valueOf(gas) <= quantity(block.getString("gasLimit")).divide(BigInteger.valueOf(2)), "Write exceeds half-block gas limit")
            val price = quantity(rpc("eth_gasPrice") as String)
            val cost = price.multiply(BigInteger.valueOf(gas))
            need(cost <= settings.maxFee.toBigDecimal().movePointRight(18).toBigIntegerExact(), "Write exceeds configured fee ceiling")
            need(cost <= quantity(rpc("eth_getBalance", JSONArray().put(address).put("latest")) as String), "Fund the writer $address on Rabdi")
            val tx = LegacyTx(chain, nonce, price, gas, ArkivCodec.address, data)
            val raw = tx.signedBy(writer)
            state.put("raw", hex(raw)).put("tx", hex(Keccak.hash(raw)))
                .put("entity", hex(ArkivCodec.entityKey(chain, writer.address, entityNonce, Bytes.fromHex(dedupe.take(32)))))
            store.checkpoint(journalFile, state)
        }
        val hash = state.getString("tx")
        var receipt = rpc("eth_getTransactionReceipt", JSONArray().put(hash)) as? JSONObject
        if (receipt == null) {
            if (state.optBoolean("receiptConfirmed")) throw OnlineIssue("Confirmed receipt unavailable; reconciliation required")
            try { rpc("eth_sendRawTransaction", JSONArray().put(state.getString("raw"))) } catch (error: IOException) {
                // A timeout or already-known transaction is ambiguous; preserve raw bytes and inspect on retry.
                receipt = rpc("eth_getTransactionReceipt", JSONArray().put(hash)) as? JSONObject
                if (receipt == null) throw error
            }
            if (receipt == null) receipt = rpc("eth_getTransactionReceipt", JSONArray().put(hash)) as? JSONObject
        }
        val confirmed = receipt ?: throw OnlineIssue("Arkiv transaction pending; will retry")
        if (quantity(confirmed.getString("status")) != BigInteger.ONE) {
            store.checkpoint(journalFile, state.put("reverted", true))
            throw OnlineIssue("Arkiv transaction reverted; operator reconciliation required")
        }
        val logs = confirmed.getJSONArray("logs")
        val event = hex(Keccak.hash("EntityCreated(bytes32,address,uint64,uint8)".toByteArray()))
        require((0 until logs.length()).any { n ->
            val log = logs.getJSONObject(n); val topics = log.getJSONArray("topics")
            log.getString("address").equals(contract, true) && topics.length() >= 3 && topics.getString(0).equals(event, true) && topics.getString(1).equals(state.getString("entity"), true)
        }) { "Receipt does not match prepared entity" }
        store.checkpoint(journalFile, state.put("receiptConfirmed", true))
        verifyEntity(state.getString("entity"), owner, address, attributes)
        store.checkpoint(journalFile, state.put("indexed", true))
    }

    private fun verifyEntity(key: String, owner: String, creator: String, expected: List<ArkivCodec.Attribute>) {
        val entity = rpc("arkiv_getEntity", JSONArray().put(key)) as? JSONObject ?: throw OnlineIssue("Entity readback pending")
        require(entity.getString("owner").equals(owner, true) && entity.getString("creator").equals(creator, true)) { "Entity ownership mismatch" }
        val actual = attributes(entity)
        expected.forEach { attr ->
            val value = actual.getValue(attr.name)
            when (attr.type) {
                0x88 -> require(value == String(attr.value, Charsets.UTF_8))
                7 -> require(Bytes.fromHex(value.removePrefix("0x")).contentEquals(attr.value))
                0x83 -> require(quantity(value) == BigInteger(1, attr.value))
            }
        }
    }

    private fun need(ok: Boolean, message: String) {
        if (!ok) throw OnlineIssue(message)
    }

    companion object {
        fun attributes(entity: JSONObject): Map<String, String> {
            val attrs = entity.getJSONArray("attributes")
            return (0 until attrs.length()).associate { val a = attrs.getJSONObject(it); a.getString("name") to a.getString("value") }
        }
        fun quantity(value: String) = BigInteger(value.removePrefix("0x").ifEmpty { "0" }, 16)
        fun hex(bytes: ByteArray) = "0x" + Bytes.toHex(bytes)
    }
}
