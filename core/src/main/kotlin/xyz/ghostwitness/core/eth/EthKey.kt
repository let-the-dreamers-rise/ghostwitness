package xyz.ghostwitness.core.eth

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import xyz.ghostwitness.core.crypto.Bytes

private val CURVE = CustomNamedCurves.getByName("secp256k1")
private val DOMAIN = ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
private val HALF_ORDER: BigInteger = CURVE.n.shiftRight(1)
private const val SCALAR_BYTES = 32

/** A recoverable secp256k1 signature over a 32-byte hash, low-s, as Ethereum transactions carry it. */
class RecoverableSignature(val r: BigInteger, val s: BigInteger, val recId: Int)

/**
 * An Ethereum account key: address derivation, recoverable signing of Keccak hashes and public key
 * recovery. Used for the per-device Arkiv writer key.
 */
class EthKey private constructor(private val secret: BigInteger) {
    private val publicPoint: ECPoint = DOMAIN.g.multiply(secret).normalize()

    /** 20-byte address: the last 20 bytes of Keccak over the 64-byte uncompressed public key. */
    val address: ByteArray = addressOf(publicPoint)

    fun privateKeyBytes(): ByteArray = fixed(secret)

    fun sign(hash: ByteArray): RecoverableSignature {
        require(hash.size == SCALAR_BYTES) { "sign a 32-byte hash" }
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(secret, DOMAIN))
        val (r, rawS) = signer.generateSignature(hash).let { it[0] to it[1] }
        val s = if (rawS > HALF_ORDER) DOMAIN.n - rawS else rawS
        val recId = (0..1).first { recover(hash, r, s, it)?.let { p -> p == publicPoint } == true }
        return RecoverableSignature(r, s, recId)
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): EthKey {
            var candidate: BigInteger
            do {
                candidate = BigInteger(DOMAIN.n.bitLength(), random)
            } while (candidate.signum() == 0 || candidate >= DOMAIN.n)
            return EthKey(candidate)
        }

        fun fromPrivateKey(bytes: ByteArray): EthKey {
            require(bytes.size == SCALAR_BYTES) { "private key must be $SCALAR_BYTES bytes" }
            val secret = BigInteger(1, bytes)
            require(secret.signum() > 0 && secret < DOMAIN.n) { "private key out of range" }
            return EthKey(secret)
        }

        /** The address that produced [signature] over [hash], or null if it does not recover. */
        fun recoverAddress(hash: ByteArray, signature: RecoverableSignature): ByteArray? =
            recover(hash, signature.r, signature.s, signature.recId)?.let { addressOf(it) }

        fun checksumless(address: ByteArray): String = "0x" + Bytes.toHex(address)

        private fun addressOf(point: ECPoint): ByteArray {
            val encoded = point.getEncoded(false)
            return Keccak.hash(encoded.copyOfRange(1, encoded.size)).copyOfRange(SCALAR_BYTES - ADDRESS_BYTES, SCALAR_BYTES)
        }

        /** SEC 1 section 4.1.6 public key recovery, for recId 0 or 1 (x = r). */
        private fun recover(hash: ByteArray, r: BigInteger, s: BigInteger, recId: Int): ECPoint? {
            val n = DOMAIN.n
            if (r.signum() <= 0 || r >= n || s.signum() <= 0 || s >= n || recId !in 0..1) return null
            val prefix = if (recId == 1) 0x03 else 0x02
            val rPoint = runCatching { DOMAIN.curve.decodePoint(byteArrayOf(prefix.toByte()) + fixed(r)) }.getOrNull() ?: return null
            val e = BigInteger(1, hash)
            val rInv = r.modInverse(n)
            val q = ECAlgorithms.sumOfTwoMultiplies(DOMAIN.g, e.negate().mod(n).multiply(rInv).mod(n), rPoint, s.multiply(rInv).mod(n))
            return q.normalize().takeUnless { it.isInfinity }
        }

        private fun fixed(value: BigInteger): ByteArray {
            val raw = Rlp.unsignedBytes(value)
            return Bytes.concat(ByteArray(SCALAR_BYTES - raw.size), raw)
        }
    }
}
