package xyz.ghostwitness.app.online

import android.content.Context
import org.json.JSONObject
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.eth.EthKey
import xyz.ghostwitness.core.recovery.SessionOwnership
import xyz.ghostwitness.core.recovery.WalletAuthorization

/** Wallet signs public authorization; the key custodian rewraps K to a fresh local delivery key. */
class WalletKeys(context: Context) {
    private val settings = OnlineSettings(context)
    data class Challenge(val id: String, val message: String, val owner: String)

    fun challenge(owner: String): Challenge {
        require(owner.matches(Regex("0x[0-9a-f]{40}")))
        val json = post("/challenge", JSONObject().put("owner", owner).put("recorder", EthKey.checksumless(settings.writer().address)))
        val id = json.getString("challengeId")
        require(id.matches(Regex("[0-9a-f]{64}")))
        val message = json.getString("message")
        WalletAuthorization.validateChallenge(message, owner, EthKey.checksumless(settings.writer().address), settings.keyService, id, System.currentTimeMillis())
        return Challenge(id, message, owner)
    }
    fun login(challenge: Challenge, signatureHex: String) {
        val auth = WalletAuthorization.parse(challenge.message, Bytes.fromHex(signatureHex.removePrefix("0x")))
        require(auth.owner == challenge.owner && auth.origin == settings.keyService && auth.nonce == challenge.id && auth.signInExpires > System.currentTimeMillis())
        val json = post("/login", JSONObject().put("challengeId", challenge.id).put("signature", signatureHex))
        require(json.getString("message") == challenge.message && json.getString("signature").equals(signatureHex, true))
        settings.saveWalletLogin(auth, json.getString("token"), json.getLong("expiresAt"))
    }
    fun unlock(proof: SessionOwnership): ByteArray {
        require(proof.verify() && proof.wallet.owner == settings.owner && proof.wallet.origin == settings.keyService)
        val delivery = KeyWrap.generateRecoveryKey()
        val json = post("/unlock", JSONObject().put("proof", Bytes.toHex(proof.encode()))
            .put("deliveryPublic", Bytes.toHex(delivery.publicKey)), true)
        return KeyWrap.unwrap(Bytes.fromHex(json.getString("wrappedKey")), delivery, proof.header.sessionId)
    }
    private fun post(path: String, body: JSONObject, authenticated: Boolean = false): JSONObject {
        require(settings.keyService.isNotBlank()) { "Configure the wallet key service first" }
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (authenticated) headers["Authorization"] = "Bearer " + settings.walletToken()
        return JSONObject(String(Http.request(settings.keyService + path, body.toString().toByteArray(), headers, 16000), Charsets.UTF_8))
    }
}
