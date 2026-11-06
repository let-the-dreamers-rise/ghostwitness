package xyz.ghostwitness.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.util.concurrent.Executors
import xyz.ghostwitness.app.online.*
import xyz.ghostwitness.core.eth.EthKey

/** External wallet login, discover the owner's recordings, select one and recover. */
class OnlineActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settings: OnlineSettings
    private lateinit var root: LinearLayout
    private lateinit var recordings: LinearLayout
    private lateinit var status: TextView
    private lateinit var ownerStatus: TextView
    private lateinit var video: VideoView
    private var busy = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = OnlineSettings(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        label("GhostWitness · My recordings")
        label("Connect the same wallet you used on the recorder. Select an uploaded recording to recover it on this device.")
        ownerStatus = label("")
        button("Connect MetaMask and sign in") { login() }
        button("Find my recordings") { findRecordings() }
        button("Sign out") {
            if (!busy) {
                settings.logoutOwner(); WalletConnection.disconnect(); recordings.removeAllViews()
                showOwner(); status.text = "Signed out"
            }
        }
        status = label("")
        recordings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        video = VideoView(this)
        root.addView(video, LinearLayout.LayoutParams(-1, 600))
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        button("Connection settings") { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        root.addView(advanced)
        advanced.visibility = if (settings.keyService.isBlank() || settings.walletProjectId.isBlank()) View.VISIBLE else View.GONE
        val keyService = input("Wallet key service HTTPS origin", settings.keyService, advanced)
        val project = input("WalletConnect / Reown project ID", settings.walletProjectId, advanced)
        val rpc = input("Arkiv RPC HTTPS URL", settings.rpc, advanced)
        val bee = input("Bee HTTPS URL", settings.bee, advanced)
        val postage = input("Postage batch ID (receiving witnesses only)", settings.postage, advanced)
        val rewards = input("Reward verifier HTTPS URL (optional)", settings.rewards, advanced)
        val fee = input("Maximum Arkiv fee per write (GLM)", settings.maxFee, advanced)
        val unmetered = CheckBox(this).apply { text = "Upload only on unmetered internet"; isChecked = settings.unmetered; advanced.addView(this) }
        advanced.addView(TextView(this).apply { text = "The key service is trusted to unlock your recordings after wallet verification. Keep its origin consistent across devices. Receiving witnesses need Bee/postage and a funded Rabdi writer; owner recovery reads do not." })
        button("Save connection settings", advanced) {
            if (!busy) try {
                listOf(rpc.text.toString(), bee.text.toString(), rewards.text.toString(), keyService.text.toString()).filter { it.isNotBlank() }.forEach {
                    val uri = java.net.URI(it.trim()); require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
                }
                val origin = keyService.text.toString().trim().trimEnd('/')
                if (origin.isNotBlank()) { val uri = java.net.URI(origin); require(uri.path.isNullOrEmpty() && uri.query == null) }
                require(project.text.isBlank() || project.text.toString().matches(Regex("[0-9a-fA-F]{32}")))
                require(postage.text.isBlank() || postage.text.toString().matches(Regex("[0-9a-fA-F]{64}")))
                require(fee.text.toString().toBigDecimal().signum() > 0 && fee.text.toString().toBigDecimal().scale() <= 18)
                settings.keyService = origin; settings.walletProjectId = project.text.toString()
                settings.rpc = rpc.text.toString(); settings.bee = bee.text.toString(); settings.postage = postage.text.toString()
                settings.rewards = rewards.text.toString(); settings.maxFee = fee.text.toString(); settings.unmetered = unmetered.isChecked
                UploadService.reconfigure(this)
                recordings.removeAllViews(); showOwner(); status.text = "Connection settings saved. Connect your wallet to record or recover."
            } catch (_: Exception) { status.text = "Check HTTPS endpoints, project ID, postage ID and fee ceiling" }
        }
        advanced.addView(TextView(this).apply { text = "Witness upload writer: " + EthKey.checksumless(settings.writer().address) })
        button("Refresh upload status", advanced) { status.text = OnlineStore(filesDir).summary() + "\nLast upload run: " + UploadEngine.status; UploadService.schedule(this) }
        showOwner()
    }

    private fun login() {
        if (busy) return
        if (settings.keyService.isBlank() || settings.walletProjectId.isBlank()) { status.text = "Save key service and WalletConnect settings first"; return }
        busy = true; recordings.removeAllViews(); status.text = "Approve the wallet connection, then sign the GhostWitness authorization. No transaction or private-key export is requested."
        try {
            WalletConnection.connect(applicationContext, { uri -> openWallet(uri) }) { owner ->
                if (owner == null) { finishError("Wallet connection was rejected or timed out"); return@connect }
                executor.execute {
                    try {
                        val keys = WalletKeys(applicationContext)
                        val challenge = keys.challenge(owner)
                        postUi {
                            WalletConnection.sign(challenge.message, owner, { openWallet(null) }) { signature ->
                                if (signature == null) { finishError("Wallet signature was rejected or timed out"); return@sign }
                                executor.execute {
                                    try { keys.login(challenge, signature); postUi { busy = false; showOwner(); status.text = "Signed in. Start recording or find your uploaded recordings." } }
                                    catch (_: Exception) { finishError("Wallet sign-in failed. Check key service and try again.") }
                                }
                            }
                        }
                    } catch (_: Exception) { finishError("Could not get a wallet challenge from the key service") }
                }
            }
        } catch (_: Exception) { finishError("Could not initialize wallet connection. Check configuration; restart after changing wallet settings.") }
    }

    private fun findRecordings() {
        if (busy) return
        if (settings.walletAuthorization() == null) { status.text = "Connect and sign in with your wallet first"; return }
        busy = true; recordings.removeAllViews(); status.text = "Finding your recordings on Arkiv…"
        executor.execute {
            try {
                val found = RecoveryEngine(applicationContext).discover()
                postUi {
                    busy = false
                    status.text = if (found.isEmpty()) "No wallet-authorized recordings have been uploaded yet" else "${found.size} recording(s), newest first. Select Recover."
                    found.forEach { recording ->
                        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(recording.ownership.header.startWallMs))
                        button("$date · ${recording.ownership.sessionId.take(8)}\nRecover", recordings) { recover(recording) }
                    }
                }
            } catch (_: Exception) { finishError("Could not load recordings. Check wallet sign-in and Arkiv connection.") }
        }
    }

    private fun recover(recording: OwnerRecording) {
        if (busy) return
        busy = true; video.stopPlayback()
        executor.execute {
            try {
                val result = RecoveryEngine(applicationContext).recover(recording) { message -> postUi { status.text = message } }
                postUi {
                    busy = false; status.text = result.report
                    if (result.files.isNotEmpty()) {
                        var index = 0
                        fun play() { video.setVideoPath(result.files[index].absolutePath); video.start() }
                        video.setOnCompletionListener { if (++index < result.files.size) play() }
                        video.setOnErrorListener { _, _, _ -> status.append("\nPlayback failed for segment ${result.files[index].name}"); true }
                        play()
                    }
                }
            } catch (error: Exception) {
                val reason = if (error is OnlineIssue) error.message else "Check wallet sign-in, Bee/key-service access and uploaded shard count"
                finishError("Recovery failed: $reason")
            }
        }
    }

    private fun openWallet(uri: String?) {
        if (destroyed) return
        try {
            val link = if (uri == null) "metamask://" else "https://metamask.app.link/wc?uri=" + Uri.encode(uri)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) { finishError("Install/open MetaMask to approve the wallet request") }
    }
    private fun showOwner() { ownerStatus.text = settings.walletAuthorization()?.let { "Wallet: ${it.owner}" } ?: "Not signed in" }
    private fun finishError(message: String) = postUi { busy = false; status.text = message }
    private fun postUi(action: () -> Unit) { runOnUiThread { if (!destroyed) action() } }
    private fun label(value: String): TextView = TextView(this).apply { text = value; setTextIsSelectable(true); root.addView(this) }
    private fun input(title: String, value: String, parent: LinearLayout): EditText = EditText(this).apply {
        hint = title; setText(value); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; parent.addView(this)
    }
    private fun button(title: String, parent: LinearLayout = root, action: () -> Unit) { parent.addView(Button(this).apply { text = title; setOnClickListener { action() } }) }
    override fun onDestroy() { destroyed = true; WalletConnection.cancelCallbacks(); video.stopPlayback(); executor.shutdownNow(); super.onDestroy() }
}
