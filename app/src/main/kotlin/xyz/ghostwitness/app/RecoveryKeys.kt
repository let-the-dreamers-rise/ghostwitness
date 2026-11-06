package xyz.ghostwitness.app

import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.KeyWrap

/** The public key K is wrapped to. [testMode] means nobody holds the private half. */
class RecoveryKeyChoice(publicKey: ByteArray, val testMode: Boolean) {
    private val publicBytes = publicKey.copyOf()
    val publicKey: ByteArray get() = publicBytes.copyOf()
}

/**
 * Until QR import of the recovery holder's key lands (block 5), the key comes from the build property
 * `gw.recoveryPub`. Without it the app makes a throwaway key pair and drops the private half, so the whole
 * pipeline runs but recordings can't be recovered. The UI says so, and release builds refuse to record.
 */
object RecoveryKeys {
    private const val X25519_PUBLIC_BYTES = 32

    fun resolve(configuredHex: String): RecoveryKeyChoice {
        val configured = configuredHex.takeIf { it.isNotBlank() }?.let { Bytes.fromHex(it) }
        if (configured != null) {
            require(configured.size == X25519_PUBLIC_BYTES) { "gw.recoveryPub must be $X25519_PUBLIC_BYTES bytes" }
            return RecoveryKeyChoice(configured, testMode = false)
        }
        return RecoveryKeyChoice(KeyWrap.generateRecoveryKey().publicKey, testMode = true)
    }
}
