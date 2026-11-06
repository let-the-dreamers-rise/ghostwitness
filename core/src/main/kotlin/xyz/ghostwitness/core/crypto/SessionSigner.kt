package xyz.ghostwitness.core.crypto

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator

private const val SCALAR_BYTES = 32
const val SIGNATURE_BYTES = 64

private object Secp256k1 {
    private val params = CustomNamedCurves.getByName("secp256k1")
    val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
    val halfOrder: BigInteger = params.n.shiftRight(1)
}

/**
 * The ephemeral session key (secp256k1, the same curve Arkiv transactions use). Signs SHA-256 digests
 * with deterministic RFC 6979 nonces and low-s normalisation. Signature: `r(32) || s(32)`.
 */
class SessionSigner private constructor(private val secret: BigInteger) {
    private val publicBytes: ByteArray = Secp256k1.domain.g.multiply(secret).normalize().getEncoded(false)

    /** Uncompressed SEC1 public key, 65 bytes. */
    val publicKey: ByteArray get() = publicBytes.copyOf()

    fun sign(message: ByteArray): ByteArray {
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(secret, Secp256k1.domain))
        val parts = signer.generateSignature(Bytes.sha256(message))
        val r = parts[0]
        val s = if (parts[1] > Secp256k1.halfOrder) Secp256k1.domain.n - parts[1] else parts[1]
        return Bytes.concat(r.toFixedBytes(), s.toFixedBytes())
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): SessionSigner {
            val order = Secp256k1.domain.n
            var candidate: BigInteger
            do {
                candidate = BigInteger(order.bitLength(), random)
            } while (candidate.signum() == 0 || candidate >= order)
            return SessionSigner(candidate)
        }

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != SIGNATURE_BYTES) return false
            val point = runCatching { Secp256k1.domain.curve.decodePoint(publicKey) }.getOrNull() ?: return false
            val r = BigInteger(1, signature.copyOfRange(0, SCALAR_BYTES))
            val s = BigInteger(1, signature.copyOfRange(SCALAR_BYTES, SIGNATURE_BYTES))
            if (s > Secp256k1.halfOrder) return false
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(point, Secp256k1.domain))
            return runCatching { verifier.verifySignature(Bytes.sha256(message), r, s) }.getOrDefault(false)
        }
    }
}

private fun BigInteger.toFixedBytes(): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == SCALAR_BYTES -> raw
        raw.size > SCALAR_BYTES -> raw.copyOfRange(raw.size - SCALAR_BYTES, raw.size)
        else -> Bytes.concat(Bytes.zeros(SCALAR_BYTES - raw.size), raw)
    }
}
