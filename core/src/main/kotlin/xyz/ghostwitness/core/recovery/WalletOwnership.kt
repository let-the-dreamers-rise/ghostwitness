package xyz.ghostwitness.core.recovery

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.eth.Abi
import xyz.ghostwitness.core.eth.EthKey
import xyz.ghostwitness.core.eth.Keccak
import xyz.ghostwitness.core.eth.RecoverableSignature
import xyz.ghostwitness.core.session.SessionHeader

/** Public wallet-signed authorization of this recorder and the service's recovery public key. */
class WalletAuthorization private constructor(val message: String, signature: ByteArray) {
    private val sig = signature.copyOf()
    private val lines = message.split('\n')
    val origin: String get() = lines[1].removePrefix("Service: ")
    val owner: String get() = lines[2].removePrefix("Owner: ")
    val recorder: String get() = lines[3].removePrefix("Recorder: ")
    val recoveryPublicKey: ByteArray get() = Bytes.fromHex(lines[4].removePrefix("Recovery public key: "))
    val issuedAt: Long get() = lines[5].removePrefix("Issued at: ").toLong()
    val validUntil: Long get() = lines[6].removePrefix("Recorder valid until: ").toLong()
    val signInExpires: Long get() = lines[7].removePrefix("Sign-in expires: ").toLong()
    val nonce: String get() = lines[8].removePrefix("Challenge: ")

    fun verify(): Boolean = WalletSignatures.address(personalDigest(message), sig) == owner
    fun permits(header: SessionHeader): Boolean = verify() && header.startWallMs >= issuedAt - 300_000 && header.startWallMs <= validUntil
    fun encode(): ByteArray = message.toByteArray(Charsets.UTF_8).let { Bytes.concat(Bytes.u16be(it.size), it, sig) }

    companion object {
        fun personalDigest(message: String): ByteArray {
            val bytes = message.toByteArray(Charsets.UTF_8)
            return Keccak.hash(("\u0019Ethereum Signed Message:\n" + bytes.size).toByteArray(Charsets.UTF_8), bytes)
        }
        fun parse(message: String, signature: ByteArray): WalletAuthorization = fields(message, signature).also {
            require(it.verify()) { "Invalid wallet signature" }
        }
        fun validateChallenge(message: String, owner: String, recorder: String, origin: String, nonce: String, now: Long) {
            val auth = fields(message, ByteArray(65))
            require(auth.owner == owner && auth.recorder == recorder && auth.origin == origin && auth.nonce == nonce)
            require(auth.issuedAt <= now + 300_000 && auth.signInExpires > now) { "Expired wallet challenge" }
        }
        private fun fields(message: String, signature: ByteArray): WalletAuthorization {
            require(message.toByteArray().size <= 2048 && signature.size == 65) { "Invalid wallet authorization size" }
            val lines = message.split('\n')
            val prefixes = listOf("Service: ", "Owner: ", "Recorder: ", "Recovery public key: ", "Issued at: ", "Recorder valid until: ", "Sign-in expires: ", "Challenge: ")
            require(lines.size == 9 && lines[0] == "GhostWitness Wallet Authorization v1" && prefixes.indices.all { lines[it + 1].startsWith(prefixes[it]) }) { "Invalid wallet authorization" }
            val auth = WalletAuthorization(message, signature)
            val uri = java.net.URI(auth.origin)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "Invalid key service origin" }
            require(listOf(auth.owner, auth.recorder).all { it.matches(Regex("0x[0-9a-f]{40}")) && it.drop(2).any { c -> c != '0' } })
            require(lines[4].removePrefix(prefixes[3]).matches(Regex("[0-9a-f]{64}")) && auth.recoveryPublicKey.any { it != 0.toByte() })
            require(auth.issuedAt >= 0 && auth.validUntil > auth.issuedAt && auth.validUntil - auth.issuedAt <= 366L * 86_400_000)
            require(auth.signInExpires > auth.issuedAt && auth.signInExpires - auth.issuedAt <= 600_000)
            require(auth.nonce.matches(Regex("[0-9a-f]{64}")))
            return auth
        }
        fun decode(bytes: ByteArray): WalletAuthorization = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(bytes.size <= 2115)
            val message = ByteArray(input.readUnsignedShort()).also(input::readFully)
            val signature = ByteArray(65).also(input::readFully)
            require(input.available() == 0)
            parse(String(message, Charsets.UTF_8), signature)
        }
    }
}

/** Wallet authorization delegates offline recording; the device signs each complete session header. */
class SessionOwnership private constructor(val header: SessionHeader, val wallet: WalletAuthorization, signature: ByteArray) {
    private val signatureBytes = signature.copyOf()
    val sessionId: String get() = Bytes.toHex(header.sessionId)
    val sessionCode: String get() = sessionId + ":" + Bytes.toHex(Bytes.sha256(header.sessionPublicKey))
    fun verify(): Boolean = wallet.permits(header) && WalletSignatures.address(digest(header, wallet), signatureBytes) == wallet.recorder
    fun encode(): ByteArray {
        val h = header.encode(); val w = wallet.encode()
        return Bytes.concat("GWO1".toByteArray(), Bytes.u16be(h.size), h, Bytes.u16be(w.size), w, signatureBytes)
    }
    companion object {
        private fun digest(header: SessionHeader, wallet: WalletAuthorization) = Keccak.hash("GWSESSION".toByteArray(), header.encode(), Bytes.sha256(wallet.encode()))
        fun create(header: SessionHeader, wallet: WalletAuthorization, recorder: EthKey): SessionOwnership {
            require(EthKey.checksumless(recorder.address) == wallet.recorder && wallet.permits(header)) { "Sign in with the owner's wallet before recording" }
            return SessionOwnership(header, wallet, WalletSignatures.sign(recorder, digest(header, wallet)))
        }
        fun decode(bytes: ByteArray): SessionOwnership = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(bytes.size <= 4096)
            fun read(n: Int) = ByteArray(n).also(input::readFully)
            require(String(read(4), Charsets.US_ASCII) == "GWO1")
            val header = SessionHeader.decode(read(input.readUnsignedShort()))
            val wallet = WalletAuthorization.decode(read(input.readUnsignedShort()))
            val signature = read(65)
            require(input.available() == 0)
            SessionOwnership(header, wallet, signature).also { require(it.verify()) { "Invalid session ownership proof" } }
        }
    }
}

private object WalletSignatures {
    private val halfOrder = BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16)
    fun sign(key: EthKey, digest: ByteArray): ByteArray = key.sign(digest).let { Bytes.concat(Abi.word(it.r), Abi.word(it.s), Bytes.u8(it.recId)) }
    fun address(digest: ByteArray, bytes: ByteArray): String? {
        if (bytes.size != 65) return null
        val r = BigInteger(1, bytes.copyOfRange(0, 32)); val s = BigInteger(1, bytes.copyOfRange(32, 64))
        if (s > halfOrder) return null
        val v = (bytes[64].toInt() and 255).let { if (it >= 27) it - 27 else it }
        return runCatching { EthKey.recoverAddress(digest, RecoverableSignature(r, s, v))?.let(EthKey::checksumless) }.getOrNull()
    }
}
