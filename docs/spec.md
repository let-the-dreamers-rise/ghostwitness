# GhostWitness: Engineering Spec v2

Deadline: Sat 7 Nov 2026 09:00 IST. Partner picks: **EF CROPS and Arkiv (Build from scratch +
Arkiv/Swarm bonus)**. Arc and World are not targeted. This replaces `Downloads/GHOSTWITNESS-SPEC.md`, which is left
unchanged for reference.

## 0. What changed from v1

| Area | v1 | v2 |
|---|---|---|
| Arkiv | Manifest and roster with a TTL | A full **chunk index owned by the witness's wallet**: every chunk, which holder got which bytes, and which device uploaded what to Swarm. Holders write entries, then transfer ownership to the witness wallet. `$creator` records who wrote each one. |
| Cloud tier | None | **Swarm**: encrypted segments for a live stream (one feed per session), plus encrypted shards uploaded by holders once they have internet. |
| Links | Nearby only | **Two lanes**: BLE for control, ACKs, the index and an audio-only tier; Wi-Fi (Nearby bandwidth upgrade) for video shards. |
| Transfer speed | Send 5 shards per segment | **Data shards first** (recoverable after 1.0x the segment size instead of 1.67x), oldest unsafe segment first, adaptive bitrate, batched ACKs |
| Segments | MediaRecorder, 3 s | MediaCodec, 2 s, a keyframe at each segment start |
| Chain escrow | Bonds on an L2, World ID seats | **Dropped for this weekend** (no Arc, no World). Holders are unbonded volunteers, and Arkiv is the only chain. |
| Fixes | none | Gap-map fixes S1-S16 folded in |

## 1. One-liner and problem

A phone records an event. Every 2 s segment is encrypted, erasure-coded into 5 shards (any 3 rebuild it),
and spread to nearby holder phones over BLE and Wi-Fi. When any device reaches the internet, it also goes to
Swarm. An index in the witness's own Arkiv wallet records where every byte went. The recording survives if
the recording phone is lost, broken or offline.

## 2. Non-goals

- No unlinkable payouts, no deepfake detection, no claim of legal chain of custody.
- No iOS. No long-range mesh: radio range only, with multi-hop only for small control messages.
- No new crypto: AES-256-GCM, Reed-Solomon, HPKE (RFC 9180, the standard form of a sealed box), secp256k1
  ECDSA, SHA-256.
- No bonds, rewards or slashing this weekend (post-hackathon).
- Arkiv never stores media. Swarm stores ciphertext only. Neither ever sees K.
- Capture and recovery must work with no internet. Swarm and Arkiv add reach; safety doesn't depend on them.

## 3. Actors and trust

| Actor | Holds | Trusted for | Not trusted for |
|---|---|---|---|
| Witness phone | Plaintext briefly, K, session key | Capture | Lasting. Assume it can disappear at any time. |
| **Witness wallet** (long-term address) | Owns all Arkiv index entities | Being the single place to look | Holding keys on the phone. Its private key lives in the witness's backup or with the recovery holder, not on the recording phone. The phone needs only the address. |
| Session key (secp256k1, on the witness phone) | Signs manifest roots and holder admissions | Authenticity of the manifest | Lasting (it's ephemeral) |
| Holder phones (5) | One shard per segment (ciphertext), an ephemeral Arkiv key | Storage and availability (volunteers, unbonded) | Confidentiality |
| Recovery key holder | X25519 private key | Unwrapping K | Holding shards |
| Arkiv (Rabdi testnet, chain 7794095) | Index entities (public attributes + sealed bytes) | Coordination with expiry | Confidentiality of public attributes |
| Swarm (Bee API + postage batch) | Encrypted segments and shards, one feed per session | Cloud copy when online | Anything plaintext. The Bee operator sees IP, timing and ciphertext sizes. |
| Indexer (optional) | Mirrors the Arkiv index for a dashboard | Convenience | Anything critical. The CROPS demo stops it. |

Threat assumptions: the recording phone and up to 2 holder phones may be lost. Holders are honest but lazy.

## 4. Crypto pipeline

- **K**: 256-bit, random per session, kept in memory only and wiped after STOPPED. `wrapped_K =
  enc(32) || HPKE.seal(recovery_pubkey, info="ghostwitness/v2/wrapped-key", aad=session_id, K)` (base mode,
  DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM; 80 bytes) is created before recording and replicated to holders, Arkiv and Swarm.
  It's useless without the recovery private key.
- **Segment i**: `nonce = salt(8B random per session) || u32_be(i)`.
  `AAD = session_id(16B) || u32_be(i) || u64_be(t_start_ms) || prev_ct_hash(32B)`, where
  `prev_ct_hash(0) = 32 zero bytes`. `ct_i = AES-256-GCM(K, nonce, segment_i, AAD)`; the tag is included.
- **Erasure code**: systematic RS(n=5, k=3) over `ct_i`, zero-padded to a multiple of 3; `ct_len` is
  recorded. Shards `d0 d1 d2` are slices of the ciphertext, `p0 p1` are parity.
- **Shard commitment**: `shard_root_ij` = Merkle root over 1 KiB blocks of the shard, with `0x00` leaf
  and `0x01` node prefixes, SHA-256.
- **Manifest entry i**: fixed-width big-endian `u32 i | u64 t_start_ms | u16 dur_ms | u32 ct_len |
  prev_ct_hash(32) | u8 n | shard_root(32) x n` (211 bytes for n = 5; no CBOR, so every verifier hashes
  the same bytes). `leaf_i = sha256(0x00 || entry_i)`, the RFC 6962 leaf. **Running root**: after each segment the witness signs
  `(session_id, i, root_0..i)` with the session key. Holders keep the newest one, and any device can publish the
  highest-i signed root to Arkiv as `gw.root`.
- **IDs**: `chunk_id = sha256(session_id || i)`, `shard_id = sha256(shard bytes)`.
- **Libraries (Kotlin)**: `javax.crypto` GCM; Reed-Solomon over GF(256) written in `core/rs` (same
  construction as Backblaze JavaReedSolomon); BouncyCastle for HPKE and secp256k1.

## 5. Capture

- MediaCodec H.264 + AAC into **2 s MP4 segments** via MediaMuxer, with `KEY_I_FRAME_INTERVAL` = segment
  length, so each segment plays on its own and gaps don't break playback.
- **Bitrate tiers**, switched at runtime with `PARAMETER_KEY_VIDEO_BITRATE` from measured goodput (section 6.4):

| Tier | Content | Bitrate | Segment (2 s) | Witness egress per segment, all 5 shards |
|---|---|---|---|---|
| V2 | 720p video + audio | 1.5 Mbps | ~375 KB | ~625 KB |
| V1 | 480p video + audio | 0.8 Mbps | ~200 KB | ~333 KB |
| V0 | 240p video + audio | 0.25 Mbps | ~63 KB | ~104 KB |
| A | Audio only (AAC 32 kbps) | 0.032 Mbps | ~8 KB | ~13 KB |

- Each finished segment goes straight to encrypt, RS and send. Plaintext is deleted as soon as `ct_i` exists.
- **As built (block 2)**: resolution is fixed per session at 640x480, so V1 and V0 differ only in bitrate
  and a tier switch never restarts the encoder. V2 is cut for this weekend. A segment with video starts on
  a keyframe that the planner requests 50 ms before the 2 s mark. Tier A writes audio-only segments while
  the video encoder keeps running, so switching back is instant. Plaintext MP4s are written to a memfd
  (Android 11+) or a private temp file (Android 10).

## 6. Transfer architecture (fast path)

### 6.1 Two lanes

| Lane | Link | Carries | Why |
|---|---|---|---|
| **Control** | BLE (Nearby Connections, or our own GATT/L2CAP) | Admission, ACKs, signed roots, index deltas, tier A shards | Always available and low power, but slow |
| **Bulk** | Wi-Fi via the Nearby `P2P_STAR` bandwidth upgrade (Wi-Fi Direct or hotspot) | Tiers V0-V2 shards | The only link with enough throughput for video to 5 peers |

- If the bulk lane drops, the encoder falls to tier A and the control lane carries audio shards. The UI shows
  DEGRADED.
- `P2P_CLUSTER` is not a fallback for video (Google documents it as lower bandwidth).

### 6.2 Scheduling: recoverable first

1. Queues are per segment, **oldest unsafe segment first**.
2. For each segment, send `d0 d1 d2` to 3 different holders **first**. Once 3 distinct shards are ACKed
   the segment is RECOVERABLE, after 1.0x the segment size of egress instead of 1.67x.
3. Then send `p0 p1` to the remaining holders. At 5 ACKs the segment is FULLY SPREAD (it tolerates 2
   holder losses).
4. Holder choice uses measured per-peer goodput: the fastest peers get the data shards.
5. A peer gone for more than 5 s has its shards reassigned to the fastest peer not already holding a
   shard of that segment.

The UI shows "Recoverable up to T-Xs" and "Fully spread up to T-Ys". **OK TO LOSE PHONE** turns on only
when every finished segment is RECOVERABLE, with the spare count shown (S2).

### 6.3 Frames

- **Header frame** (BYTES, < 32 KB): `"GW2" | ver | session_id | i | j | n | k | ct_len | t_start |
  dur | shard_len | shard_root | payload_id`. The shard body travels as a FILE or STREAM payload with that
  `payload_id` (S5).
- **ACK frame** (cumulative, batched every 300 ms per peer): `"GWA" | session_id | ranges[(i_from,
  i_to, j)] | sha256s of new shards`.
- **Control relay**: ACKs and roots may be relayed one hop through another holder. Envelope = `{msg_id,
  src, dst, ttl<=3}`, with a seen-set dedupe on `msg_id`.

### 6.4 Rate control

Every 2 s: `goodput = ACKed bytes / elapsed` on the bulk lane. Pick the highest tier whose egress for
the data shards alone fits in 70% of goodput. Step down immediately on backlog over 2 segments, and step
up after 3 clean windows.

### 6.5 What not to do (it slows transfer)

- No stop-and-wait per small chunk (see the soradyne comparison).
- No second encryption layer over Nearby links. Shards are already AES-GCM, and Nearby links are encrypted.
  Only control frames need authentication, which comes from the session key signature.
- No per-segment handshake. Connections stay open for the session.

## 7. Swarm tier

- **Who uploads**: whichever device has internet.
  - The witness phone (if online) uploads each encrypted segment `ct_i` and updates the session **feed**
    (owner = session key, index = i). This is the live stream. Only K holders can play it.
  - Each holder uploads its own shards when it gets internet. 3 shards of a segment on Swarm make it
    recoverable with no holder phone present.
- **How**: Bee HTTP API to a configured Bee node with a postage batch (the team runs one for the demo; any
  user can point the app at their own). Use deferred uploads for speed, then confirm sync before marking
  `swarm_synced`.
- References are plain (unencrypted Swarm mode) because the content is already ciphertext. They are still
  stored sealed in Arkiv (section 8), so sizes and timing aren't public.
- Postage: the batch must outlive the demo window. Record its ID and owner in `/arkiv/swarm.md`.

## 8. Arkiv index in the witness's wallet

### 8.1 Ownership model

- Entities are created by the **writer's key** (the session key on the witness phone, or a holder's
  ephemeral per-session Arkiv key). The writer then calls `changeOwnership(entityKey, witness_wallet)`
  in the same batch where possible.
- Result: every entity is **owned by the witness wallet**, and `$creator` permanently records which device
  wrote it, for example who uploaded a shard to Swarm. Only the witness wallet can patch, extend or delete
  afterwards.
- The witness wallet's private key is **not on the recording phone**. Writers need only its address
  (from the setup QR).
- Writers need Arkiv testnet gas (faucet). The session key funds holder ephemeral keys at setup.

### 8.2 Entity types

Public attributes are queryable (32 max, `str` up to 128 B). Sealed fields are `bytes` (128 KiB max per
entity), sealed to the recovery public key.

| Entity | Public attributes | Sealed bytes | Written by | When |
|---|---|---|---|---|
| `gw.session` | `session_id`, `seg_dur_ms`, `n`, `k`, `started_at`, `status` | `wrapped_K`, recovery-key fingerprint, holder pseudonym roster | Witness session key | Setup (if online) or first device online |
| `gw.chunk` | `session_id`, `i`, `t_start`, `ct_len`, `leaf_hash`, `tier` | none | Witness, or any holder relaying the signed manifest | Per segment, batched |
| `gw.placement` | `session_id`, `i`, `j`, `shard_id`, `holder_tag`, `lane` (`ble`/`wifi`), `acked_at` | holder's real device id + byte range of the segment it holds | Holder that ACKed | Per shard, batched |
| `gw.upload` | `session_id`, `i`, `j` (or `full`), `bytes_len`, `swarm_synced` | Swarm reference, Bee node URL | Device that uploaded (`$creator`) | After each Swarm upload |
| `gw.root` | `session_id`, `upto_i`, `root` | session-key signature | Any device | With each new signed root |

- `holder_tag` = a random 16-byte per-session pseudonym, never a MAC address or wallet address, so public
  attributes don't reveal who held what. The mapping from tag to device sits in sealed bytes for the
  recovery holder only.
- **Batching**: one entity can hold a range of segments (`i_from`, `i_to`) to cut transactions.
- **Offline queue**: every device keeps a local append-only log of index entries (a replicated per-device
  journal, like soradyne's FullReplicaFlow). The first device online flushes everything it knows.
  Deterministic salts per `(session_id, type, i, j)` make duplicate writes detectable.
- **Expiry**: default 30 days. The Arkiv maximum is 7,884,000 blocks (about 182.5 days), and the witness
  wallet can extend.
- **Read path (user-visible)**: the recovery app queries `gw.chunk` and `gw.placement` by `session_id`,
  opens the sealed bytes, and builds its fetch plan (which holder or Swarm reference has which shard)
  before fetching. This satisfies "a feature users see reads from Arkiv".

## 9. Bonds and payouts: out of scope

No escrow this weekend. The block-level shard Merkle roots (section 4) stay in, so custody proofs and
bonds can be added later without changing the data format. Honest limit: holders are volunteers, so
nothing but goodwill stops one from deleting its shards.

## 10. Recovery

1. Load the recovery key and query Arkiv for the session. Unseal `wrapped_K`, the roster and the Swarm
   references.
2. Build the fetch plan per segment: nearby holders (BLE/Wi-Fi) and Swarm in parallel; first 3 valid
   shards win.
3. Verify each shard against `shard_root`, RS-rebuild, GCM-decrypt with the AAD, and check the hash chain
   and signed root.
4. Play with a Media3 playlist of segment MP4s, and export one MP4 with Media3 Transformer. Unrecoverable
   segments show as gaps.
5. Report: segments recovered/total, source per segment (holder tag or Swarm), gaps, signature status.

## 11. State machines

- Witness: `IDLE -> SETUP -> RECORDING <-> DEGRADED -> STOPPED -> DRAINING -> ALL_RECOVERABLE ->
  FULLY_SPREAD`, plus `LOCAL_ONLY` (red banner) if the setup gate fails.
- Segment: `ENCODED -> SENDING -> RECOVERABLE -> FULLY_SPREAD`. Separately `INDEXED` (in Arkiv) and
  `IN_SWARM` (3 or more shards, or the full segment, synced).
- Shard per holder: `PENDING -> SENT -> ACKED`, then `UPLOADED` once the holder has put it on Swarm.

## 12. Demo outline (4 min)

1. Setup: 5 holders scan the QR and are admitted; the `gw.session` entity appears in the witness wallet.
2. Record 30 s. The meters show Recoverable and Fully spread. Switch the Wi-Fi lane off mid-way: the tier
   drops to audio and DEGRADED shows honestly.
3. Take the recording phone offline. Switch off 2 holders.
4. The recovery phone reads the session from Arkiv (the index on screen), pulls 3 shards per segment from
   the remaining holders plus Swarm, and the video plays.
5. Stop the indexer. Recovery still runs straight against the Arkiv RPC, the Bee node and the holders.
6. A holder comes back online and uploads its shards to Swarm. A `gw.upload` entry appears in the witness
   wallet with that holder as `$creator`.

Devices: witness + 5 holders + recovery on a holder, so 6 Android phones minimum (S1).

## 13. Acceptance tests

- Unit: GCM round trip; tampered AAD fails; RS rebuilds from all 10 subsets and fails with 2; data-first
  scheduler reaches RECOVERABLE after exactly k ACKs; Merkle roots match the RFC 6962 reference
  vectors and every inclusion proof verifies (no Solidity this weekend).
- **Transfer gates (measured on the devices, at the venue)**:
  - Bulk lane: 5 holders, tier V1 for 5 min. p95 time-to-RECOVERABLE under 4 s, zero lost segments after
    retry.
  - Control lane, BLE only: tier A for 5 min. p95 time-to-RECOVERABLE under 4 s.
  - Record the measured per-lane goodput in the README.
- Arkiv: an entity created by a holder key and transferred shows `owner = witness wallet` and
  `$creator = holder key`. A query by `session_id` returns every chunk.
- Swarm: a segment uploaded by the witness plays from the feed. 3 holder-uploaded shards rebuild a segment
  with no holders present.
- CROPS: with the indexer stopped, steps 3-6 still pass.

## 14. Residual risks

- BLE throughput is far below video rates. The control lane carries video only at tier A or V0, and that
  needs measuring.
- The Bee node is a dependency of the Swarm tier, which is optional for safety. Its operator sees IP,
  timing and sizes.
- Arkiv public attributes still show that a session existed, how many segments it had, and when. Holder
  identities are pseudonymous; the details are sealed.
- Postage batch and Arkiv gas must be pre-funded.
- Six phones, two networks (Arkiv, and Gnosis for postage) and a short window.

## 15. Prize mapping

- **EF CROPS**: works with no server; open source; the README CROPS section lists what each party learns
  (Bee operator, Arkiv RPC).
- **Arkiv Build from scratch + Swarm bonus**: the witness-owned index with expiry, `$creator` provenance and
  a user-visible read, plus Swarm for the bytes. `/arkiv/*.md` and `/arkiv/swarm.md` are required.
