package xyz.ghostwitness.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.util.concurrent.Executors
import xyz.ghostwitness.app.online.OnlineSettings
import xyz.ghostwitness.app.online.OnlineStore
import xyz.ghostwitness.app.online.RecoveryEngine
import xyz.ghostwitness.app.online.UploadService
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.eth.EthKey

/** Setup on each role's phone; recovery keys stay on the owner's recovery device. */
class OnlineActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settings: OnlineSettings
    private lateinit var status: TextView
    private lateinit var video: VideoView
    private lateinit var root: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = OnlineSettings(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        label("Recorder → nearby witnesses → Swarm + Arkiv → owner's recovery phone")
        val rpc = input("Arkiv RPC HTTPS URL", settings.rpc)
        val bee = input("Bee HTTPS URL", settings.bee)
        val postage = input("Postage batch ID (uploading witnesses only)", settings.postage)
        val rewards = input("Reward verifier HTTPS URL (optional until deployed)", settings.rewards)
        val fee = input("Maximum Arkiv fee per write (GLM)", settings.maxFee)
        label("Saving upload setup enables gas-paid Arkiv writes from this device's funded writer. Default maximum is 0.01 GLM per write.")
        val owner = input("Original owner's wallet address", settings.owner)
        val publicKey = input("Owner's recovery public key (recorder only)", settings.recoveryPublic)
        val unmetered = CheckBox(this).apply { text = "Upload only on unmetered internet"; isChecked = settings.unmetered }
        root.addView(unmetered)
        button("Save setup and schedule uploads") {
            try {
                // Validate all fields before committing any setting.
                listOf(rpc.text.toString(), bee.text.toString(), rewards.text.toString()).filter { it.isNotBlank() }.forEach {
                    val uri = java.net.URI(it.trim()); require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Use HTTPS URLs" }
                }
                require(postage.text.isBlank() || postage.text.toString().matches(Regex("[0-9a-fA-F]{64}"))) { "Postage ID must be 64 hex characters" }
                require(owner.text.isBlank() || owner.text.toString().matches(Regex("0x[0-9a-fA-F]{40}")) && owner.text.drop(2).any { it != '0' }) { "Enter a wallet address" }
                require(publicKey.text.isBlank() || publicKey.text.toString().matches(Regex("[0-9a-fA-F]{64}"))) { "Public key must be 64 hex characters" }
                require(fee.text.toString().toBigDecimal().signum() > 0 && fee.text.toString().toBigDecimal().scale() <= 18) { "Enter a positive fee ceiling" }
                settings.rpc = rpc.text.toString(); settings.bee = bee.text.toString(); settings.postage = postage.text.toString()
                settings.owner = owner.text.toString(); settings.recoveryPublic = publicKey.text.toString(); settings.unmetered = unmetered.isChecked
                settings.rewards = rewards.text.toString()
                settings.maxFee = fee.text.toString()
                UploadService.reconfigure(this)
                status.text = "Saved. Fund the writer address on Rabdi before uploads can be indexed."
            } catch (error: Exception) { status.text = error.message }
        }
        label("This device's Arkiv writer / upload contribution address:\n" + EthKey.checksumless(settings.writer().address))
        label("Owner recovery key: create on the recovery phone, then copy only its PUBLIC key to the recorder. Keep this phone/key available for recovery.")
        val ownPublic = label(settings.recovery()?.let { Bytes.toHex(it.publicKey) } ?: "No recovery private key saved")
        button("Create owner's recovery key on this phone") {
            try { ownPublic.text = Bytes.toHex(settings.generateRecovery().publicKey); status.text = "Recovery key saved securely on this device" }
            catch (error: Exception) { status.text = error.message }
        }
        val import = input("Restore key backup: public-key-hex:private-key-hex", "", true)
        button("Import owner's recovery key") {
            try { settings.importRecovery(import.text.toString()); import.text.clear(); ownPublic.text = Bytes.toHex(settings.recovery()!!.publicKey); status.text = "Recovery key restored" }
            catch (_: Exception) { status.text = "Invalid backup or a different key is already saved" }
        }
        val session = input("Full session code saved from recorder (session ID:signing-key hash)", "")
        label("Recovery downloads and decrypts on this phone. Recovered MP4s remain in its private app storage.")
        button("Download and recover from Arkiv + Swarm") {
            if (!busy) {
                busy = true
                val selectedOwner = owner.text.toString().trim().lowercase()
                val code = session.text.toString().trim().lowercase()
                executor.submit {
                    try {
                        val result = RecoveryEngine(applicationContext).recover(selectedOwner, code) { message -> runOnUiThread { status.text = message } }
                        runOnUiThread {
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
                        // RecoveryEngine's own require/error messages and OnlineIssue never carry secrets or URLs.
                        val reason = if (error is IllegalArgumentException || error is IllegalStateException || error is xyz.ghostwitness.app.online.OnlineIssue) error.message else error.javaClass.simpleName
                        runOnUiThread { busy = false; status.text = "Recovery failed: $reason. Check setup, original key, session code, network and uploaded shard count." }
                    }
                }
            }
        }
        button("Refresh upload status") {
            status.text = OnlineStore(filesDir).summary() + "\nLast upload run: " + xyz.ghostwitness.app.online.UploadEngine.status
            UploadService.schedule(this)
        }
        status = label(OnlineStore(filesDir).summary())
        video = VideoView(this)
        root.addView(video, LinearLayout.LayoutParams(-1, 600))
    }

    private fun label(value: String): TextView = TextView(this).apply { text = value; setTextIsSelectable(true); root.addView(this) }
    private fun input(hintText: String, value: String, secret: Boolean = false): EditText = EditText(this).apply {
        hint = hintText; setText(value); inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        root.addView(this)
    }
    private fun button(title: String, action: () -> Unit) { root.addView(Button(this).apply { text = title; setOnClickListener { action() } }) }
    override fun onDestroy() { video.stopPlayback(); executor.shutdownNow(); super.onDestroy() }
}
