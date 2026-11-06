package xyz.ghostwitness.app.online

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient
import org.json.JSONArray
import xyz.ghostwitness.core.crypto.Bytes
import java.util.concurrent.Executors

/** Only account connection and personal_sign are requested. No raw wallet key access or transaction permission. */
object WalletConnection : SignClient.DappDelegate {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var configured = ""
    @Volatile private var initialized = false
    @Volatile private var topic: String? = null
    private var connectResult: ((String?) -> Unit)? = null
    private var signatureResult: ((String?) -> Unit)? = null
    private var attempt = 0
    private const val CHAIN = "eip155:1"

    /** Why the last connection or signature ended, for the on-screen error. Never holds keys or tokens. */
    @Volatile var lastReason = ""
        private set

    fun cancelCallbacks() { connectResult = null; signatureResult = null; attempt++ }
    fun disconnect() {
        val old = topic
        topic = null
        cancelCallbacks()
        if (old != null) executor.execute { runCatching { SignClient.disconnect(Sign.Params.Disconnect(old), onError = {}) } }
    }

    fun connect(context: Context, openWallet: (String?) -> Unit, callback: (String?) -> Unit) {
        val settings = OnlineSettings(context)
        require(settings.walletProjectId.matches(Regex("[0-9a-fA-F]{32}")) && settings.keyService.isNotBlank()) { "Configure WalletConnect project ID and key service first" }
        val config = settings.walletProjectId + settings.keyService
        require(configured.isEmpty() || configured == config) { "Restart GhostWitness after changing wallet connection settings" }
        val current = ++attempt
        connectResult = callback
        topic = null
        lastReason = ""
        main.postDelayed({ if (attempt == current) fail("no answer from MetaMask within 5 minutes") }, 300_000)
        executor.execute {
            fun start() {
                SignClient.setDappDelegate(this)
                val pairing = CoreClient.Pairing.create { fail("pairing failed: " + it.throwable.message) } ?: return
                val proposal = Sign.Model.Namespace.Proposal(listOf(CHAIN), listOf("personal_sign"), listOf("accountsChanged", "chainChanged"))
                SignClient.connect(Sign.Params.ConnectParams(sessionNamespaces = mapOf("eip155" to proposal), pairing = pairing),
                    onSuccess = { uri -> main.post { openWallet(uri) } }, onError = { fail("connect request failed: " + it.throwable.message) })
            }
            try {
                if (initialized) start() else {
                    configured = config
                    CoreClient.initialize(application = context.applicationContext as Application, projectId = settings.walletProjectId,
                        metaData = Core.Model.AppMetaData("GhostWitness", "Wallet-authorized recording recovery", settings.keyService, emptyList(), "ghostwitness://wallet"),
                        // Core also reports later relay hiccups here; only an error before setup completes is fatal.
                        connectionType = ConnectionType.AUTOMATIC, telemetryEnabled = false,
                        onError = { val reason = "relay: " + it.throwable.message; if (initialized) lastReason = reason else fail(reason) })
                    SignClient.initialize(Sign.Params.Init(CoreClient), onSuccess = { initialized = true; start() },
                        onError = { fail("wallet SDK setup failed: " + it.throwable.message) })
                }
            } catch (error: Exception) { fail("wallet SDK error: " + error.message) }
        }
    }

    fun sign(message: String, owner: String, openWallet: () -> Unit, callback: (String?) -> Unit) {
        val session = topic ?: run { lastReason = "no active MetaMask session"; callback(null); return }
        signatureResult = callback
        val params = JSONArray().put("0x" + Bytes.toHex(message.toByteArray(Charsets.UTF_8))).put(owner).toString()
        executor.execute {
            try { SignClient.request(Sign.Params.Request(session, "personal_sign", params, CHAIN),
                onSuccess = { main.post(openWallet) }, onError = { fail("signature request failed: " + it.throwable.message) }) }
            catch (error: Exception) { fail("signature request error: " + error.message) }
        }
    }

    private fun fail(reason: String) { lastReason = reason; main.post { connectResult?.invoke(null); signatureResult?.invoke(null); connectResult = null; signatureResult = null; attempt++ } }
    override fun onSessionApproved(approvedSession: Sign.Model.ApprovedSession) {
        val account = approvedSession.namespaces["eip155"]?.accounts?.firstOrNull { it.startsWith("$CHAIN:") }
        if (account == null || "personal_sign" !in approvedSession.namespaces.getValue("eip155").methods) {
            fail("MetaMask approved without an Ethereum mainnet account and personal_sign"); return
        }
        topic = approvedSession.topic
        main.post { connectResult?.invoke(account.substringAfterLast(':').lowercase()); connectResult = null }
    }
    override fun onSessionRequestResponse(response: Sign.Model.SessionRequestResponse) {
        if (response.topic != topic || response.method != "personal_sign") return
        val signature = (response.result as? Sign.Model.JsonRpcResponse.JsonRpcResult)?.result as? String
        (response.result as? Sign.Model.JsonRpcResponse.JsonRpcError)?.let { lastReason = "MetaMask declined the signature: " + it.message }
        main.post { signatureResult?.invoke(signature); signatureResult = null; attempt++ }
    }
    override fun onSessionRejected(rejectedSession: Sign.Model.RejectedSession) = fail("MetaMask rejected the connection: " + rejectedSession.reason)
    // Wallets send namespace updates and chainChanged/accountsChanged right after approving; they are not failures.
    override fun onSessionUpdate(updatedSession: Sign.Model.UpdatedSession) = Unit
    @Deprecated("SDK callback") override fun onSessionEvent(sessionEvent: Sign.Model.SessionEvent) = Unit
    override fun onSessionExtend(session: Sign.Model.Session) = Unit
    override fun onSessionDelete(deletedSession: Sign.Model.DeletedSession) { topic = null; fail("MetaMask ended the session") }
    override fun onProposalExpired(proposal: Sign.Model.ExpiredProposal) = fail("connection request expired before approval")
    override fun onRequestExpired(request: Sign.Model.ExpiredRequest) = fail("signature request expired")
    override fun onConnectionStateChange(state: Sign.Model.ConnectionState) = Unit
    // Relay errors are usually transient (the socket pauses while MetaMask is in front): record, do not cancel.
    override fun onError(error: Sign.Model.Error) { lastReason = "relay: " + error.throwable.message }
}
