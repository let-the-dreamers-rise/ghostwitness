package xyz.ghostwitness.core.recovery

import java.util.Properties
import kotlin.test.*
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.crypto.RecoveryKeyPair
import xyz.ghostwitness.core.eth.EthKey
import xyz.ghostwitness.core.session.SessionHeader

class WalletOwnershipTest {
    private val fixture = Properties().apply { WalletOwnershipTest::class.java.getResourceAsStream("/wallet.properties")!!.use(::load) }
    private fun bytes(name: String) = Bytes.fromHex(fixture.getProperty(name))
    private fun proof() = SessionOwnership.decode(bytes("proof"))

    @Test fun `ethers wallet and recorder signatures verify in Kotlin`() {
        val proof = proof()
        assertTrue(proof.verify())
        assertEquals(fixture.getProperty("owner"), proof.wallet.owner)
        assertEquals(fixture.getProperty("recorder"), proof.wallet.recorder)
        assertContentEquals(bytes("proof"), proof.encode())
        assertContentEquals(proof.wallet.encode(), WalletAuthorization.decode(proof.wallet.encode()).encode())
    }

    @Test fun `hpkeJS wrapped key opens in BouncyCastle`() {
        val proof = proof()
        assertContentEquals(bytes("content"), KeyWrap.unwrap(proof.header.wrappedKey,
            RecoveryKeyPair(bytes("public"), bytes("private")), proof.header.sessionId))
    }

    @Test fun `tampered headers certificates and signatures are rejected`() {
        for (offset in listOf(10, 30, 90, 190, bytes("proof").lastIndex)) {
            val bad = bytes("proof").also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFails { SessionOwnership.decode(bad) }
        }
        assertFails { SessionOwnership.decode(bytes("proof") + byteArrayOf(0)) }
        assertFails { SessionOwnership.decode(bytes("proof").copyOf(100)) }
    }

    @Test fun `recorder delegation permits historical sessions but rejects expired capture`() {
        val proof = proof()
        val h = proof.header
        fun at(time: Long) = SessionHeader(h.sessionId, time, h.totalShards, h.dataShards, h.salt, h.sessionPublicKey, h.wrappedKey)
        assertTrue(proof.wallet.permits(h))
        assertFalse(proof.wallet.permits(at(proof.wallet.validUntil + 1)))
        assertFalse(proof.wallet.permits(at(proof.wallet.issuedAt - 300_001)))
        val recorder = EthKey.fromPrivateKey(ByteArray(32) { 0x22 })
        assertTrue(SessionOwnership.create(h, proof.wallet, recorder).verify())
        assertFails { SessionOwnership.create(h, proof.wallet, EthKey.generate()) }
    }

    @Test fun `challenge is bound to intended service wallet device nonce and expiry`() {
        val a = proof().wallet
        fun validate(message: String = a.message, owner: String = a.owner, recorder: String = a.recorder,
                     origin: String = a.origin, nonce: String = a.nonce, now: Long = a.issuedAt) =
            WalletAuthorization.validateChallenge(message, owner, recorder, origin, nonce, now)
        validate()
        assertFails { validate(owner = a.recorder) }
        assertFails { validate(recorder = a.owner) }
        assertFails { validate(origin = "https://other.example.com") }
        assertFails { validate(nonce = "00".repeat(32)) }
        assertFails { validate(now = a.signInExpires) }
        assertFails { validate(now = a.issuedAt - 300_001) }
        assertFails { validate(message = a.message + "\nExtra: value") }
    }
}
