package xyz.ghostwitness.core.recovery

import xyz.ghostwitness.core.codec.SegmentCodec
import xyz.ghostwitness.core.crypto.Bytes
import xyz.ghostwitness.core.crypto.ChunkCipher
import xyz.ghostwitness.core.crypto.KeyWrap
import xyz.ghostwitness.core.crypto.RecoveryKeyPair
import xyz.ghostwitness.core.crypto.SegmentAad
import xyz.ghostwitness.core.rs.ReedSolomon
import xyz.ghostwitness.core.rs.ShardCodec

class RecoveredSegment(val plaintext: ByteArray, val ciphertextHash: ByteArray)

object SegmentRecovery {
    fun recover(record: RecoveryRecord, sessionCode: String, recovery: RecoveryKeyPair, shards: Map<Int, ByteArray>, previousHash: ByteArray? = null): RecoveredSegment {
        require(record.verify() && record.sessionCode == sessionCode.lowercase()) { "Untrusted recording or signed proof" }
        val key = KeyWrap.unwrap(record.header.wrappedKey, recovery, record.header.sessionId)
        return try { recoverWithKey(record, sessionCode, key, shards, previousHash) } finally { key.fill(0) }
    }

    /** Key delivered by the wallet-authorized custodian; caller owns and must wipe its copy. */
    fun recoverWithKey(record: RecoveryRecord, sessionCode: String, key: ByteArray, shards: Map<Int, ByteArray>, previousHash: ByteArray? = null): RecoveredSegment {
        require(key.size == 32 && record.verify() && record.sessionCode == sessionCode.lowercase())
        val entry = record.entry
        if (entry.index == 0) require(entry.prevCtHash.contentEquals(SegmentAad.genesisPrevHash()))
        if (previousHash != null) require(entry.prevCtHash.contentEquals(previousHash))
        val valid = shards.filter { (j, bytes) -> entry.shardRootMatches(j, bytes) }
        val rs = ReedSolomon(record.header.dataShards, record.header.totalShards - record.header.dataShards)
        val ct = ShardCodec.join(valid, entry.ctLen, rs)
        val codec = SegmentCodec(ChunkCipher(key, record.header.salt), record.header.sessionId, rs)
        return try { RecoveredSegment(codec.decode(entry, valid), Bytes.sha256(ct)) } finally { codec.wipe() }
    }
}
