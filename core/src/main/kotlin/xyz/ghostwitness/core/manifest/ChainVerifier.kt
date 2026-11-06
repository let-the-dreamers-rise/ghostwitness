package xyz.ghostwitness.core.manifest

import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.SegmentAad

/** A problem found while checking that segments form one unbroken, ordered chain. */
sealed interface ChainProblem {
    data class OutOfOrder(val position: Int, val index: Int) : ChainProblem

    data class Gap(val missingIndex: Int) : ChainProblem

    data class BrokenLink(val index: Int) : ChainProblem
}

/**
 * Checks the hash chain in the manifest (spec section 4): entries must be in index order with no gaps,
 * segment 0 must point at the genesis hash, and each `prev_ct_hash` must equal SHA-256 of the previous
 * ciphertext when that ciphertext is known. Reordered or dropped segments are reported, not repaired.
 */
object ChainVerifier {
    fun verify(entries: List<ManifestEntry>, ciphertextHashes: Map<Int, ByteArray>): List<ChainProblem> {
        val problems = mutableListOf<ChainProblem>()
        var previousIndex = -1
        entries.forEachIndexed { position, entry ->
            when {
                entry.index <= previousIndex -> problems += ChainProblem.OutOfOrder(position, entry.index)
                entry.index > previousIndex + 1 ->
                    (previousIndex + 1 until entry.index).forEach { problems += ChainProblem.Gap(it) }
            }
            if (entry.index > previousIndex) previousIndex = entry.index
            if (!linkHolds(entry, ciphertextHashes)) problems += ChainProblem.BrokenLink(entry.index)
        }
        return problems.toList()
    }

    private fun linkHolds(entry: ManifestEntry, ciphertextHashes: Map<Int, ByteArray>): Boolean {
        if (entry.index == 0) return entry.prevCtHash.contentEquals(SegmentAad.genesisPrevHash())
        val previous = ciphertextHashes[entry.index - 1] ?: return true
        return entry.prevCtHash.contentEquals(previous)
    }

    fun ciphertextHash(ciphertext: ByteArray): ByteArray = Bytes.sha256(ciphertext)
}
