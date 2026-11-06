# GhostWitness: Engineering Spec v2.1

> Implementation update (2026-11-06): [online-flow.md](online-flow.md) supersedes this document's
> offline-first recovery priority and exclusion of rewards. Product terminology is recorder -> nearby
> witnesses; source `WitnessMesh` means recorder and `HolderNode` means receiving witness. The revised
> implementation adds background Swarm/Arkiv uploads, online owner recovery and optional verified upload
> rewards. Historical acceptance claims below are not fresh live-device or live-network evidence.
> [wallet-recovery.md](wallet-recovery.md) also supersedes the recovery-phone key pairing,
> manual owner-address setup and separate recovery-key trust model. The current implementation uses
> MetaMask signatures and a trusted wallet-authorized key service for recovery on a new device.

Event: ETHGlobal Mumbai 2026. Partner picks: **EF CROPS and Arkiv (Build from scratch + Arkiv/Swarm
bonus)**. Arc and World are not targeted. This replaces `Downloads/GHOSTWITNESS-SPEC.md`, which is left
unchanged for reference.

v2.1 answers a 22-point review of v2. Every point is either fixed here and in the code, or recorded as an
open item with how it gets settled (section 16). Where this spec says "as built", the code in this repo
does exactly that; "planned" means not written yet.

## 0. What changed from v1

| Area | v1 | v2.1 |
|---|---|---|
| Arkiv | Manifest and roster with a TTL | A **chunk index owned by the witness's wallet**: every segment, which holder got which shard, which device put what on Swarm. Writers create entities, then transfer ownership to the witness wallet in the same transaction. `$creator` records who wrote each one. |
| Cloud tier | None | **Swarm**: the recording phone uploads each encrypted segment while it has internet; holders upload their shards once they get internet. |
| Links | Nearby only | Still **Nearby Connections only** (P2P_STAR). It starts on Bluetooth and upgrades to Wi-Fi on its own. There is no separate GATT lane. |
| Transfer speed | Send 5 shards per segment | **Data shards first** (recoverable after 1.0x the segment size of egress instead of 1.67x), oldest unsafe segment first, batched ACKs, rate control on goodput **and** holder count |
| Segments | MediaRecorder, 3 s | MediaCodec through a GL scaler, 2 s, each segment starts on a keyframe, size and frame rate set per tier |
| Chain escrow | Bonds on an L2, World ID seats | **Dropped** (no Arc, no World). Holders are unbonded volunteers. |

## 1. One-liner and problem

A phone records an event. Every 2 s segment is encrypted, erasure-coded into 5 shards (any 3 rebuild it),
and spread to nearby holder phones over Bluetooth and Wi-Fi. When any device reaches the internet, the
ciphertext also goes to Swarm and an index entry goes to Arkiv, owned by the witness's own wallet. The
recording survives if the recording phone is lost, broken or offline.

## 2. Non-goals

- No unlinkable payouts, no deepfake detection, no claim of legal chain of custody.
- No iOS. No long-range mesh: radio range of the recording phone only. No multi-hop relay (section 6.3).
- No new crypto: AES-256-GCM, Reed-Solomon, HPKE (RFC 9180), secp256k1 ECDSA, SHA-256, Keccak-256.
- No bonds, rewards or slashing (post-hackathon).
- Arkiv never stores media. Swarm stores ciphertext only. Neither ever sees K.
- Capture and recovery must work with no internet. Swarm and Arkiv add reach; safety doesn't depend on
  them, and neither ever blocks recording.

## 3. Actors and trust

| Actor | Holds | Trusted for | Not trusted for |
|---|---|---|---|
| Witness phone | Plaintext briefly, K, session key, its device writer key | Capture | Lasting. Assume it can disappear at any time. |
| **Witness wallet** (long-term address) | Owns every Arkiv index entity | Being the one place to look | Being on the phone. Its private key is in the witness's backup, not on the recording phone. The phone needs only the address. |
| Session key (secp256k1, on the witness phone) | Signs running roots | Authenticity of the manifest | Lasting (ephemeral, per session) |
| Device writer key (secp256k1, one per installed app) | Arkiv gas | Paying for and signing index writes | Anything else. It is funded ahead of time (section 8.6). |
| Holder phones (1 to 5, more are allowed) | Shards (ciphertext), entries, roots, the session header | Storage and availability (volunteers) | Confidentiality |
| **Recovery phone** | The X25519 recovery private key | Unwrapping K, rebuilding | Holding shards. It is a separate phone and never acts as a holder in the same session. |
| Arkiv (Rabdi testnet, chain 7794095) | Index entities (public attributes, public or sealed payloads) | Coordination with expiry | Confidentiality of public attributes |
| Swarm (Bee API + postage batch) | Encrypted segments and shards | Cloud copy when online | Anything plaintext. The Bee operator sees IP, timing and sizes. |
| Indexer (optional) | Mirrors the Arkiv index for a dashboard | Convenience | Anything critical. The CROPS demo stops it. |

Threat assumptions: the recording phone and up to 2 of 5 holder phones may be lost. With fewer holders the
tolerance is lower and the UI says so (section 6.2). Holders are honest but lazy.

## 4. Crypto and encodings

All integers are unsigned big-endian. `H` is SHA-256. `||` is concatenation. Lengths are in bytes.

- **IDs**: `session_id` = 16 random bytes. `chunk_id = H(session_id || u32(i))`. `shard_id = H(shard)`.
- **K**: 256-bit, random per session, in memory only, wiped when capture stops.
- **Wrapped K**: HPKE (RFC 9180) base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, to the
  recovery public key, `info = "ghostwitness/v2/wrapped-key"`, `aad = session_id`. `wrapped_K = enc(32) ||
  ct(32 + 16 tag)`, 80 bytes. Library: BouncyCastle `HPKE`. There is no libsodium sealed box anywhere.
- **Salt**: 8 random bytes per session. It is not secret. It travels in the session header (section 6.3),
  which every holder stores and which the `gw.session` entity carries (section 8.3). No salt, no
  decryption, so it goes wherever wrapped K goes.
- **Segment i**: `nonce = salt || u32(i)` (12 bytes). `AAD = session_id || u32(i) || u64(t_start_ms) ||
  prev_ct_hash`, where `prev_ct_hash(0)` = 32 zero bytes and `prev_ct_hash(i) = H(ct_{i-1})`.
  `ct_i = AES-256-GCM(K, nonce, segment_i, AAD)`, tag appended.
- **Erasure code**: systematic RS(n=5, k=3) over GF(256), polynomial 0x11D, Vandermonde matrix times the
  inverse of its top k rows (the Backblaze JavaReedSolomon construction, written in `core/rs`). `ct_i` is
  zero-padded to a multiple of 3. Shards `d0 d1 d2` are slices of the ciphertext; `p0 p1` are parity.
  Shard length = `ceil(ct_len / 3)`.
- **Merkle trees** (RFC 9162 section 2.1, used for both trees below): `leaf = H(0x00 || data)`, `node =
  H(0x01 || left || right)`. For n > 1 leaves, split at the largest power of two below n, left part first.
  One leaf: the root is that leaf hash. Odd counts need no special case, because the split rule never
  pairs a node with a copy of itself. Leaves are in index order.
- **Shard commitment**: `shard_root_ij` = Merkle root over the 1 KiB blocks of the shard (last block may be
  short), each block a leaf.
- **Manifest entry i**: `u32 i | u64 t_start_ms | u16 dur_ms | u32 ct_len | prev_ct_hash(32) | u8 n |
  shard_root(32) x n`, 211 bytes for n = 5. Fixed width, so every verifier hashes the same bytes.
- **Running root**: `root_0..i` = Merkle root whose leaves are `H(0x00 || entry_0) .. H(0x00 || entry_i)`.
  After sealing segment i the witness signs `"GWROOT" || session_id || u32(i) || root_0..i` with the
  session key: secp256k1 ECDSA over the SHA-256 of that message, RFC 6979 nonces, low-s, encoded `r(32) ||
  s(32)`. Holders keep the highest-i root that verifies against the session public key.
- **Arkiv transactions**: EIP-1559 (type 2) transactions signed with the device writer key, Keccak-256,
  RLP and ABI encoding written in `core/eth`.
- **Sealed payloads** (section 8.3): HPKE as for wrapped K, `info = "ghostwitness/v2/sealed"`, `aad =
  session_id || kind`, output `enc(32) || ct`.

## 5. Capture

- **Pipeline (as built)**: Camera2 streams into one SurfaceTexture. A GL scaler draws each camera frame,
  centre-cropped to 16:9, into the input surface of an H.264 MediaCodec encoder at the tier's size, and
  drops frames to reach the tier's frame rate. AAC comes from the mic on the same clock. MediaMuxer writes
  **2 s MP4 segments**; each one starts on a keyframe, so it plays on its own. Encode is live at the target
  size; nothing is recorded large and re-compressed.
- **Tiers (as built)**. Size and frame rate are set explicitly, because a phone's default is 1080p or 4K
  at 12-60 Mbps, and 1080p squeezed into 1.5 Mbps looks worse than 720p at 1.5 Mbps. Sizes are multiples
  of 16 (H.264 macroblocks), so 480p is 848 wide and 240p is 432 wide, both within 1% of 16:9.

| Tier | Video | Bitrate (video + 32 kbps AAC) | Segment (2 s) | Data shards (1.0x) | All 5 shards (1.67x) |
|---|---|---|---|---|---|
| V2 | 1280x720, 30 fps | 1.5 Mbps | ~383 KB | ~383 KB | ~638 KB |
| V1 | 848x480, 30 fps | 0.8 Mbps | ~208 KB | ~208 KB | ~347 KB |
| V1_24 | 848x480, 24 fps | 0.6 Mbps | ~158 KB | ~158 KB | ~263 KB |
| V0 | 432x240, 24 fps | 0.25 Mbps | ~71 KB | ~71 KB | ~118 KB |
| V0_15 | 432x240, 15 fps | 0.2 Mbps | ~58 KB | ~58 KB | ~97 KB |
| V0_10 | 432x240, 10 fps | 0.15 Mbps | ~46 KB | ~46 KB | ~76 KB |
| A | Audio only, AAC-LC 32 kbps mono 44.1 kHz | 0.032 Mbps | ~8 KB | ~8 KB | ~13 KB |

- **Encoder settings (as built)**: H.264 Baseline profile at the highest level the encoder offers for
  Baseline (no B-frames, so every frame can be muxed as soon as it is encoded); constant bitrate where the
  encoder supports `BITRATE_MODE_CBR`, otherwise the encoder default; keyframe interval 2 s; realtime
  priority. A keyframe is also requested 50 ms before each 2 s mark.
- **Camera size (as built)**: from the camera's `StreamConfigurationMap`, take 1280x720 if offered; else
  the smallest 16:9 size at least that large; else the largest size up to 720p. Tiers larger than the
  16:9 crop of that size, or that no H.264 encoder on the phone accepts at that size and frame rate, are
  removed from the ladder. A phone without 720p records 480p or 240p tiers; it never fails setup.
- **When changes apply**: bitrate changes at once (`PARAMETER_KEY_VIDEO_BITRATE`). Frame rate changes when
  the next segment opens. A size change starts a second encoder next to the first; the open segment keeps
  taking frames from the old encoder, the next segment starts on a keyframe from the new one, and the old
  encoder is then released. No gap, no frame rate or size change inside a segment.
- **Tier A**: audio-only segments, while the newest video encoder keeps running at 100 kbps and 5 fps, so
  video comes back at the next boundary.
- **Metadata (as built)**: no location is ever set on the muxer, no Camera2 metadata is passed through, and
  only the orientation hint is written. Before encryption each MP4 is scrubbed: `udta` and `meta` boxes
  (location atom, `com.android.version`, capture fps keys) become `free` boxes of the same size with zeroed
  contents, and creation and modification times in `mvhd`, `tkhd` and `mdhd` become 0. Sizes don't change,
  so chunk offsets stay valid. The segment's wall-clock start lives only in the signed manifest entry.
- **Plaintext lifetime**: segments are written to a memfd (Android 11+) or a private temp file (Android
  10), read once for sealing, then wiped. Plaintext never sits in storage once `ct_i` exists.

## 6. Transfer

### 6.1 Link

- **Nearby Connections, `P2P_STAR`, as built.** The recording phone advertises as the hub
  (`GW-W-<8 hex of session_id>`); holders discover it and connect (`GW-H-<16 hex holder tag>`). Nearby starts
  on Bluetooth and upgrades to Wi-Fi (Direct or hotspot) without app code; `onBandwidthChanged` reports
  LOW (Bluetooth), MEDIUM or HIGH.
- Control frames are BYTES payloads. Shard bodies are FILE payloads. Both ride whatever medium Nearby
  picked, so before the upgrade, "the control lane" is simply Nearby over Bluetooth. There is no separate
  BLE GATT/L2CAP lane: GATT gives about 500-byte writes, far too slow, and two radio stacks would mean two
  builds.
- A link counts as the **bulk lane** when Nearby reports MEDIUM or HIGH, or when measured goodput on it
  is at least 60 KB/s (above what Bluetooth delivers), in case the bandwidth callback never fires.
- `P2P_CLUSTER` is not used. Whether it upgrades to Wi-Fi well enough for video is untested here, so v2.1
  makes no claim about it.

### 6.2 Scheduling: recoverable first (as built)

1. Queues are per segment, **oldest unsafe segment first**.
2. Pass 1 brings every segment to 3 placed shards, data shards (`d0 d1 d2`) first. At 3 ACKs the segment
   is RECOVERABLE, after 1.0x the segment size of egress.
3. Pass 2 places the parity shards. At 5 ACKs it is FULLY SPREAD.
4. Each shard goes to the live holder with a free send slot that has the **fewest shards of this segment**,
   fastest measured goodput first. With 5 or more live holders that is one shard each. With fewer, a holder
   may take up to `ceil(5 / live holders)` shards of a segment, so 1 or 2 holders still reach RECOVERABLE.
5. At most 2 sends in flight per holder. A send not ACKed within 5 s, or to a holder silent for 5 s, goes
   back to the queue and is reassigned by the same rule.
6. Holders are identified by their advertised holder tag, not the Nearby endpoint id, which changes on
   every reconnect.

**Fault tolerance is counted in holders, not shards.** For a segment, `spare` = how many holders can be
lost, worst case (largest holdings first), while 3 ACKed shards remain. When all 5 shards are spread:

| Live holders | Shards per holder | Spare (holder losses tolerated) |
|---|---|---|
| 5 | 1, 1, 1, 1, 1 | 2 |
| 4 | 2, 1, 1, 1 | 1 |
| 3 | 2, 2, 1 | 1 |
| 2 | 3, 2 | 0 |
| 1 | 5 | 0 (single point of failure) |
| 0 | none | LOCAL_ONLY |

The banner reads **OK TO LOSE PHONE** only when every finished segment is RECOVERABLE, followed by
"tolerates N holder losses" (the minimum over segments), or "single holder, tolerates 0 holder losses".
With no holders it reads **LOCAL ONLY**. Lower resolution or frame rate makes spreading faster; it does not
raise the tolerance, and the UI never says it does.

### 6.3 Frames (as built)

Every control frame starts with a 3-byte ASCII magic and a version byte (1). `session_id` is 16 bytes.

| Frame | Layout | Size | Sent |
|---|---|---|---|
| Session header `GWS` | `"GWS" | ver | session_id | u64 start_wall_ms | u8 n | u8 k | salt(8) | session_pub(65, uncompressed SEC1) | u16 len | wrapped_K` | 185 for an 80-byte wrapped K | To each holder on connect |
| Signed root `GWR` | `"GWR" | ver | session_id | u32 upto_i | root(32) | signature(64)` | 120 | To every holder after each segment, and on connect |
| Entry `GWE` | `"GWE" | ver | session_id | manifest entry` | 231 for n = 5 | To a holder before its first shard of that segment |
| Shard header `GW2` | `"GW2" | ver | session_id | u32 i | u8 j | u8 n | u8 k | u32 ct_len | u64 t_start_ms | u16 dur_ms | u32 shard_len | shard_root(32) | u64 payload_id` | 85 | Right before the FILE payload with that id |
| ACK `GWA` | `"GWA" | ver | session_id | u16 count | count x (u32 i | u8 j | shard_sha256(32))` | 22 + 37 per shard | Holder to witness, batched every 300 ms |
| Recovery key `GWK` (planned) | `"GWK" | ver | session_id | recovery_pub(32)` | 52 | To each holder on connect, so holders can seal payloads (section 8.3). Never published. |
| Shard request `GWQ` (planned) | `"GWQ" | ver | session_id | u16 count | count x (u32 i | u8 j)` | 22 + 5 per shard | Recovery phone to holder (section 10) |

- **Header and body ordering (as built)**: the sender creates the FILE payload first, which fixes its
  payload id, then sends the header (BYTES) and the file. Nearby does not promise that BYTES arrives before
  FILE, so the holder buffers whichever half arrives first and matches them by payload id; both orders are
  unit-tested. The holder checks the shard length and its Merkle root against the header and the entry
  before it stores anything or ACKs.
- **ACKs** list each stored shard with its SHA-256; the witness ignores an ACK whose hash does not match
  the shard it sent.
- **No relay.** In a star every holder talks only to the hub, so there is no holder-to-holder path to relay
  over. v2's "one hop, ttl <= 3" is removed. A holder out of range of the witness is not a holder.

### 6.4 Rate control (as built)

Every 2 s window: `tier = min(goodput tier, holder-count tier)`.

- **Goodput tier**: goodput = ACKed bytes over the time each holder link was busy, an EWMA (alpha 0.3) per
  holder, summed. Busy time, not wall time: bytes over wall time would only measure the current tier's own
  rate and could never step up. Pick the best tier whose data-shard rate (table in section 5) fits in 70%
  of goodput. No bulk lane: tier A.
- **Holder-count tier**: 5+ holders V2; 4 V1; 3 V1_24; 2 V0_15; 1 V0_10; 0 tier A (LOCAL_ONLY, so the
  backlog stays small enough to drain when a holder appears).
- A backlog of more than 2 unrecoverable segments steps down at least one tier at once. A lower target is
  applied at once. A higher target is applied after 3 clean windows (no backlog) in a row.
- New links start from a prior (LOW 20 KB/s, MEDIUM 150 KB/s, HIGH 600 KB/s) until real samples exist.
- The user can pin a tier by hand; Auto hands control back.

### 6.5 What not to do

- No stop-and-wait per small chunk.
- No second encryption layer on Nearby links: shards are already AES-GCM ciphertext and Nearby links are
  encrypted. Roots are authenticated by the session-key signature, shards by the signed entry.
- No per-segment handshake. Connections stay open for the session.

## 7. Swarm tier (planned, block 4)

- **Who uploads**: whichever device has internet.
  - The recording phone, while online, uploads each `ct_i` as soon as it is sealed. Any one of those is a
    full copy of the segment.
  - Each holder uploads the shards it holds when it gets internet. Any 3 shards of a segment on Swarm make
    it recoverable with no phone present.
- **How**: `POST /bytes` on a configured Bee node with `Swarm-Postage-Batch-Id` and
  `Swarm-Deferred-Upload: false`, so a 201 means the chunks were pushed to the network, not just stored on
  the node. The 32-byte reference goes into a `gw.upload` entity.
- **Live feed (planned, optional)**: a Swarm feed owned by the session key, topic
  `keccak256("ghostwitness/v2/feed" || session_id)`, index i -> reference of `ct_i`, so a recovery phone
  can follow a recording live. It is a convenience; `gw.upload` entities already list every reference.
- **What it reveals**: content is ciphertext. Anyone who knows the feed owner and topic, and the Bee
  operator, sees how many segments there are, their sizes and upload times. The Arkiv index shows the same
  (section 8.5), so v2.1 makes no claim that Swarm references keep sizes or timing private. References are
  plain attributes.
- **Postage**: Swarm postage batches are bought on Gnosis Chain by whoever runs the Bee node, not by this
  app. Arkiv is the only chain the app writes to. The batch must outlive the demo; its id and owner go in
  `/arkiv/swarm.md`.

## 8. Arkiv index in the witness's wallet (planned, block 4)

### 8.1 Ownership model

- A writer (any device with a funded writer key, section 8.6) sends one `execute()` transaction to the
  Arkiv precompile that **creates** a batch of entities and **transfers ownership** of each to the witness
  wallet. Entity keys are computed ahead from the writer's `entityNonce`, so both operations fit in one
  transaction.
- Result: every entity is **owned by the witness wallet**, and `$creator` permanently records which device
  wrote it, for example which holder uploaded a shard to Swarm. Only the witness wallet can update,
  extend or delete afterwards.
- The witness wallet's address reaches the app through setup (a QR or typed once); its private key never
  does.

### 8.2 Common attributes

Every entity carries these (string values are UTF-8; numbers are `u64`, both indexed):

| Attribute | Type | Value |
|---|---|---|
| `kind` | str | `session`, `chunk`, `placement`, `upload` or `root` |
| `sid` | str | `session_id`, 32 lowercase hex |
| `dedupe` | str | 32 hex: the first 16 bytes of `H("GWIDX" || kind || session_id || u32(i) || u8(j) || fact)`; see 8.4 |
| `v` | u64 | 2 |

### 8.3 Entity types

One entity per fact. No ranged entities. Several entities go in one transaction (8.4).

| Entity | Extra attributes | Payload | Written by | When |
|---|---|---|---|---|
| `gw.session` (`kind = session`) | `started_at`, `seg_ms`, `n`, `k` | The `GWS` session header, public: salt, session public key, wrapped K | Witness, or any holder (holders store `GWS`) | First flush |
| `gw.chunk` | `i`, `t_start`, `ct_len`, `tier` (str) | The 211-byte manifest entry, public | Witness, or any holder that has the entry | Per segment |
| `gw.placement` | `i`, `j`, `holder` (16 hex tag), `shard` (64 hex), `lane` (`bt`/`wifi`), `acked_at` | Sealed: the holder's own label for itself (optional) | Witness from the ACK it received, or the holder itself; one entity either way (8.4) | Per ACKed shard |
| `gw.upload` | `i`, `j` (255 = whole `ct_i`), `bytes`, `ref` (64 hex Swarm reference) | none | The device that uploaded, so `$creator` says who | After each Swarm upload returns 201 |
| `gw.root` | `upto`, `root` (64 hex) | The 120-byte `GWR` frame, **public**, so anyone can check the signature against the session key in `gw.session` | Any device | Newest root at each flush |

- `holder` is the holder's random per-session tag from its endpoint name, never a MAC, IMEI or wallet
  address.
- Sealing (section 4) needs the recovery public key. The witness hands it to holders on the mesh (`GWK`),
  never through Arkiv: publishing it would link every session that uses the same recovery key.
- Attribute names follow Arkiv's rules (`^[A-Za-z][A-Za-z0-9._-]*$`, no reserved words); attributes are
  sorted by name before encoding, as Arkiv requires.
- **Expiry**: 30 days (1,296,000 blocks at 2 s). The Arkiv maximum is 7,884,000 blocks (about 182 days);
  the witness wallet can extend.

### 8.4 Offline journal, flushing and duplicates

- Every device appends each fact it learns to a local journal: the witness from sealing and ACKs, a holder
  from `GWS`, `GWE`, `GWR`, its own stored shards and its own uploads.
- When the device has internet and a funded writer key, it flushes: every 5 segments or 10 s, whichever
  first, as one `execute()` transaction of up to 16 creates plus their ownership transfers.
- **Duplicates**: the same fact can reach Arkiv from several devices (the witness and a holder both know a
  placement). Each fact has a deterministic `dedupe` value (8.2) whose `fact` part is the defining bytes:
  nothing for `session`, the entry for `chunk`, the holder tag for `placement`, the reference for
  `upload`, the root for `root`. Before a flush, the writer queries `sid = "<sid>"` for existing `dedupe`
  values and drops facts already present. Two writers flushing at the same moment can still both write;
  readers group by `dedupe` and keep one, preferring the entity whose `$creator` is the witness's writer.
  The Arkiv create `salt` field is set to the first 16 bytes of the dedupe hash, for traceability only:
  Arkiv derives entity keys from the creator's nonce, so the salt does not stop a second create.

### 8.5 What is public

Anyone can read: that a session exists, when it started, how many segments it has, each segment's start
time and ciphertext size, how many holders took part (as tags), which writer addresses wrote entities,
and the Swarm references. The witness wallet owns everything, which links all sessions owned by that
wallet; use a wallet kept for GhostWitness only. Never public: content, K, the recovery public key, holder
device identities.

### 8.6 Funding (replaces v2's "session key funds holder keys at setup")

v2 had the session key fund ephemeral holder keys at setup, which needs Arkiv gas and an online setup at
the scene. v2.1 drops that:

- Each installed app has one **device writer key**, created on first launch and funded from the Arkiv
  faucet ahead of time, at home, never at the scene.
- At the scene nothing needs internet. Whoever gets internet first, with a funded key, flushes what it
  knows. A holder with an unfunded key keeps its journal and still serves shards; the witness's own
  placements (from ACKs) cover its holdings.
- Cost of the simpler design: a device's writer address is the same across sessions, so `$creator` links
  the sessions one device helped with. Holder tags stay per session.

### 8.7 Read path (user-visible)

The recovery app queries `kind = "chunk" AND sid = "<sid>"`, then `placement`, `upload` and `root` the same
way, verifies, and builds its fetch plan (which holder tag or Swarm reference has which shard) before
fetching. The plan is shown on screen. This is the "feature users see that reads from Arkiv".

## 9. Bonds and payouts: out of scope

No escrow. The per-shard block Merkle roots (section 4) stay in, so custody proofs and bonds can be added
later without changing the data format. Honest limit: holders are volunteers, so nothing but goodwill stops
one from deleting its shards.

## 10. Recovery (planned, block 5)

Recovery runs on the separate recovery phone, which holds the X25519 private key. Two sources, used
together:

- **Local (no internet)**: the recovery phone advertises as `GW-R-<8 hex sid>`; holders in serve mode
  connect, send their `GWS`, entries and newest root, and answer `GWQ` requests with `GW2` + FILE.
- **Remote**: Arkiv (section 8.7) for the index; Swarm for segments and shards by reference.

Steps:

1. Get the session header (from any holder or `gw.session`). Unwrap K with the recovery key.
2. Take the highest signed root that verifies against the session public key. Accept only entries
   `0..upto` whose leaves rebuild that root.
3. Per segment: if a whole `ct_i` is on Swarm, fetch it. Otherwise fetch shards from nearby holders and
   Swarm in parallel; the first 3 that match their `shard_root` win.
4. RS-rebuild, check `H(ct_{i-1})` against `prev_ct_hash`, GCM-decrypt with the AAD. A failure marks the
   segment as a gap; it never stops the rest.
5. **Playback**: a Media3 ExoPlayer playlist with one item per segment MP4. Each item plays on its own
   timeline from 0 (MediaMuxer rebases every file), so no timestamp offsets are needed. A gap is shown as
   a marker with its duration from the signed entry. Wall-clock time on screen comes from `t_start` in the
   signed entry, never from the MP4.
6. **Export**: Media3 Transformer, one `EditedMediaItemSequence` of the recovered segments, which
   concatenates and re-times them itself. Because tiers change size between segments, export sets one
   output size (the largest present) with a `Presentation` effect, scaling smaller segments up.
7. Report: segments recovered/total, source per segment (holder tag or Swarm), gaps, root signature
   status.

## 11. State machines

- Witness: `IDLE -> RECORDING -> STOPPED -> DRAINING -> ALL_RECOVERABLE -> FULLY_SPREAD`. While
  RECORDING, independent flags: `DEGRADED` (no bulk lane: tier A) and `LOCAL_ONLY` (no holders).
- **Setup** happens before the first recording, at home: recovery key pairing (QR from the recovery
  phone), witness wallet address, writer key funding. Release builds enable the record button only with a
  recovery key set; debug builds may record in TEST MODE (no recovery key, clearly marked). Nothing else
  gates recording: no holders, no internet, no Arkiv and no Swarm all still record.
- Segment: `SENDING -> RECOVERABLE -> FULLY_SPREAD`. Separately `INDEXED` (a `gw.chunk` exists) and
  `IN_SWARM` (a whole `ct_i`, or 3 or more shards, uploaded).
- Shard per holder: `PENDING -> SENT -> ACKED`, then `UPLOADED` once the holder has put it on Swarm.

## 12. Demo outline (4 min)

Devices: witness, 3 to 5 holders, and a separate recovery phone: 5 to 7 Android phones. With 3 holders
the UI shows "tolerates 1 holder loss" and the demo switches off one holder, not two.

1. Setup is already done at home (recovery key, wallet address, funded writer keys). Holders open holder
   mode; the witness starts recording and the holders connect.
2. Record 30 s with the witness in airplane mode except Bluetooth and Wi-Fi. The meters show Recoverable
   and Fully spread. Walk a holder out of range: the tier drops with the holder count and the tolerance
   shown drops with it.
3. Stop. Switch off the witness and 2 holders (1 with 3 holders).
4. **Local recovery**: the recovery phone, offline, pulls 3 shards per segment from the remaining holders
   and the video plays.
5. **Flush**: one remaining holder gets internet. It writes `gw.session`, `gw.chunk`, `gw.placement` and
   `gw.root` to Arkiv and uploads its shards to Swarm (`gw.upload`, with that holder as `$creator`). The
   entities appear in the witness wallet in the explorer.
6. **Remote recovery**: switch every holder off. The recovery phone, now online, reads the index from
   Arkiv (fetch plan on screen) and pulls from Swarm. If one holder's 3+ shards are not enough to cover every
   segment, the segments it lacks show as gaps, honestly.
7. **CROPS**: stop the indexer. Step 6 still runs straight against the Arkiv RPC and the Bee node.

Step 5 is what makes step 6 possible when the witness was offline. Had the witness been online while
recording, Swarm would already hold every whole `ct_i` and step 6 would need no holder at all.

## 13. Acceptance tests

- **Unit (as built, `core`, 80% line coverage gate, currently above 99%)**: GCM round trip and tampered AAD;
  RS rebuilds from all 10 subsets of 3 and fails with 2; Merkle roots and inclusion proofs against RFC 9162
  vectors; manifest, frame and header encodings; data-first scheduling reaches RECOVERABLE after exactly 3
  ACKs; 1 to 5 holders place all 5 shards with the spare counts of section 6.2; rate control on goodput and
  holder count; segment planning with no gaps across tier and size changes; frame-rate thinning; MP4 scrub;
  Keccak, RLP, ABI and EIP-1559 signing against published vectors.
- **Transfer gates, measured on devices**:
  - Bulk lane: 5 holders, tier V1, 5 min. p95 time-to-RECOVERABLE under 4 s, zero lost segments after
    retry.
  - Bluetooth only: tier A, 5 min. **Measure first**: record p50/p95 time-to-RECOVERABLE and goodput. Pass
    if p95 is under 4 s. If it is not, tier A over Bluetooth is documented as best-effort with the
    measured numbers, and the demo keeps Wi-Fi on.
  - Record measured per-lane goodput and the tier the controller settles on per holder count in the README.
- **Arkiv**: an entity created by a holder's writer key shows `owner = witness wallet` and `$creator =
  holder writer`. A query by `sid` returns every chunk. A duplicate flush adds no second `dedupe` value.
- **Swarm**: a segment uploaded by the witness plays after a fetch by reference. 3 holder-uploaded shards
  rebuild a segment with no holders present.
- **Privacy**: a recovered MP4 has no `udta`/`meta` box and zero creation times.
- **CROPS**: with the indexer stopped, demo steps 4-7 still pass.

## 14. Residual risks

- Bluetooth throughput is far below video rates; over Bluetooth only, the controller drops to tier A.
  The Bluetooth gate is measured before it is promised.
- The GL scaler, encoder generations and Nearby behaviour are compiled and unit-tested in their pure parts
  only; they need on-device runs (section 16).
- The Bee node is a dependency of the Swarm tier, which is optional for safety. Its operator sees IP,
  timing and sizes.
- Arkiv shows that a session existed, its segment count, timing and sizes, and links sessions through the
  witness wallet and writer addresses (8.5, 8.6).
- Writer keys and the postage batch must be funded ahead.
- With 1 or 2 holders, losing one holder can lose segments. The UI says so; the system cannot fix it.

## 15. Prize mapping

- **EF CROPS**: works with no server; open source; the README CROPS section lists what each party learns
  (Bee operator, Arkiv RPC, Nearby peers).
- **Arkiv Build from scratch + Swarm bonus**: the witness-owned index with expiry, `$creator` provenance
  and a user-visible read, plus Swarm for the bytes. `/arkiv/*.md` and `/arkiv/swarm.md` are required.

## 16. Review of v2: what happened to each point

| # | Point | Resolution |
|---|---|---|
| 1 | Salt has nowhere to live | It was already in the `GWS` header in code; now specified there and in `gw.session` (sections 4, 6.3, 8.3). |
| 2 | HPKE vs sealed box | HPKE everywhere, BouncyCastle; the sealed-box line is gone (section 4). |
| 3 | Root, `chunk_id`, frames undefined | Defined byte for byte (sections 4, 6.3). The ACK is per shard with hashes, not ranges. |
| 4 | `gw.root` signature sealed | Public payload (section 8.3). |
| 5 | One chain vs two | Arkiv is the only chain the app writes; postage on Gnosis is the Bee operator's (section 7). |
| 6 | S1-S16 references | Removed; this document stands alone. |
| 7 | Setup gate undefined | Defined; nothing at the scene gates recording (section 11). |
| 8 | Relay hop count | No relay in a star (section 6.3). |
| 9 | Recovery phone was a holder | Separate phone (sections 3, 12). |
| 10 | Header / FILE ordering | Either order, matched by payload id, unit-tested; on-device check still to do (section 6.3). |
| 11 | BLE lane is two builds | Nearby only (section 6.1). |
| 12 | `P2P_CLUSTER` claim | Claim withdrawn (section 6.1). |
| 13 | Ranged entities | Dropped: one entity per fact (section 8.3). |
| 14 | Duplicate detection | `dedupe` attribute, query before flush, readers group (section 8.4). |
| 15 | Media3 timestamps | Playlist plays each file from 0; Transformer concatenates; one output size (section 10). |
| 16 | Swarm privacy overclaim | Withdrawn; what is public is listed (sections 7, 8.5). |
| 17 | Holder funding needs online setup | Device writer keys funded ahead; any funded device flushes (section 8.6). |
| 18 | Flush missing from the demo | Explicit flush step (section 12). |
| 19 | Swarm empty in step 4 | Local recovery first, remote after the flush (section 12). |
| 20 | Bluetooth tier A gate | Measure first, documented fallback (section 13). |
| 21 | Resolution, fps, metadata | Built: GL scaler, explicit size/fps/bitrate, Baseline, CBR, camera size fallback, MP4 scrub (section 5). |
| 22 | Holder count ignored | Built: multi-shard placement, holder-based spare count, `min(goodput, holder)` tier (sections 6.2, 6.4). |

The review's cut list (drop Transformer export, drop V0, drop ranged entities, drop the indexer) is not
taken, except ranged entities, which are dropped on their merits. Open items that only a device run can
settle: header/FILE ordering on real Nearby, the GL scaler on several phone models, the Bluetooth tier A
gate, and the bulk-lane p95 gate.
