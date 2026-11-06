package xyz.ghostwitness.app.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.RecoveryKeyPair
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.eth.EthKey

/** Private keys and endpoint credentials are encrypted with a device-bound Android Keystore key. */
class OnlineSettings(context: Context) {
    private val prefs = context.getSharedPreferences("online", Context.MODE_PRIVATE)
    private val vault = context.getSharedPreferences("online-secrets", Context.MODE_PRIVATE)

    var rpc: String
        get() = secret("rpc") ?: "https://rpc.rabdi.db-chain.testnet.arkiv.network"
        set(value) { requireHttps(value); putSecret("rpc", value.trim()) }
    var bee: String
        get() = secret("bee") ?: ""
        set(value) { requireHttps(value); putSecret("bee", value.trim().trimEnd('/')) }
    var postage: String
        get() = secret("postage") ?: ""
        set(value) { require(value.isBlank() || value.matches(Regex("[0-9a-fA-F]{64}"))); putSecret("postage", value.lowercase()) }
    var owner: String
        get() = prefs.getString("owner", "").orEmpty()
        set(value) { require(value.isBlank() || value.matches(Regex("0x[0-9a-fA-F]{40}")) && value.drop(2).any { it != '0' }); prefs.edit().putString("owner", value.lowercase()).commit() }
    var recoveryPublic: String
        get() = prefs.getString("recovery-public", "").orEmpty()
        set(value) { require(value.isBlank() || value.matches(Regex("[0-9a-fA-F]{64}"))); prefs.edit().putString("recovery-public", value.lowercase()).commit() }
    var unmetered: Boolean
        get() = prefs.getBoolean("unmetered", true)
        set(value) { prefs.edit().putBoolean("unmetered", value).commit() }
    var rewards: String
        get() = secret("rewards") ?: ""
        set(value) { requireHttps(value); putSecret("rewards", value.trim().trimEnd('/')) }
    var maxFee: String
        get() = prefs.getString("max-fee", "0.01").orEmpty()
        set(value) {
            require(value.toBigDecimal().signum() > 0 && value.toBigDecimal().scale() <= 18) { "Positive fee ceiling required" }
            prefs.edit().putString("max-fee", value).commit()
        }

    fun writer(): EthKey = synchronized(LOCK) {
        secret("writer")?.let { EthKey.fromPrivateKey(Bytes.fromHex(it)) }
            ?: EthKey.generate().also { putSecret("writer", Bytes.toHex(it.privateKeyBytes())) }
    }

    fun recovery(): RecoveryKeyPair? = secret("recovery")?.split(':')?.let {
        RecoveryKeyPair(Bytes.fromHex(it[0]), Bytes.fromHex(it[1]))
    }

    fun generateRecovery(): RecoveryKeyPair = synchronized(LOCK) {
        check(recovery() == null) { "Recovery key already exists. Keep it to retain access to old recordings." }
        KeyWrap.generateRecoveryKey().also { importRecovery(Bytes.toHex(it.publicKey) + ":" + Bytes.toHex(it.privateKey)) }
    }

    fun importRecovery(value: String) {
        val parts = value.trim().split(':')
        require(parts.size == 2 && parts.all { it.matches(Regex("[0-9a-fA-F]{64}")) }) { "Use public-key-hex:private-key-hex" }
        val pair = RecoveryKeyPair(Bytes.fromHex(parts[0]), Bytes.fromHex(parts[1]))
        val sid = ByteArray(16)
        val key = ByteArray(32) { 1 }
        require(KeyWrap.unwrap(KeyWrap.wrap(key, pair.publicKey, sid), pair, sid).contentEquals(key)) { "Key pair does not match" }
        val current = recovery()
        require(current == null || current.publicKey.contentEquals(pair.publicKey)) { "A different recovery key is already saved" }
        putSecret("recovery", value.trim().lowercase())
    }

    fun uploadsConfigured() = bee.isNotBlank() && postage.isNotBlank() && rpc.isNotBlank()

    private fun key(): SecretKey = synchronized(LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ghostwitness-vault", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("ghostwitness-vault", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun putSecret(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(vault.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Could not persist settings" }
    }

    private fun secret(name: String): String? = vault.getString(name, null)?.let {
        val bytes = Base64.decode(it, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray())
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun requireHttps(value: String) {
        if (value.isBlank()) return
        val uri = java.net.URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Use an HTTPS endpoint" }
    }

    companion object { private val LOCK = Any() }
}
