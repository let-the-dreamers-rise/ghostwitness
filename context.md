# GhostWitness codebase context

Updated 2026-11-06 after implementing the owner's clarified flow. Initial review covered all original source/test files and docs at `244acfb`; the working revision adds cloud uploads, online recovery and optional upload rewards. This document describes the current source. See `docs/online-flow.md` for setup and `running.md` for validation.

## Product flow and terminology

**Recorder -> nearby witnesses -> Swarm + Arkiv -> original owner's online recovery phone.**

The source calls the recorder `WitnessMesh`/`WitnessMeshDriver` and nearby receiving witnesses `HolderNode`/holders. Receiving witnesses keep encrypted shards and upload when suitable internet becomes available. Swarm holds ciphertext bytes; Arkiv holds searchable references and signed metadata. The owner downloads, verifies, reconstructs and decrypts on the recovery phone. Optional funded rewards require an actual verified upload; a local ACK alone does not qualify.

The earlier spec's offline recovery priority and exclusion of rewards have been superseded. Direct holder-serving/GWQ remains optional and unimplemented.

## Modules and build

- `core/`: pure Kotlin/JVM crypto, Reed-Solomon, Merkle trees, session/manifest formats, capture planning, scheduling, mesh logic, Ethereum/Arkiv encodings and recovery primitives.
- `app/`: native Android platform-view UI, Camera2/MediaCodec/GL capture, Nearby transport, local stores, persistent upload jobs, network clients, online recovery and playback.
- `rewards/`: Solidity reward pool, Node verifier/relayer, deployment/funding scripts and local contract/proof tests.
- `arkiv/`: schema, integration rationale and submission evidence checklist. Live evidence is pending.
- `docs/spec.md`: original v2.1 protocol design with an override pointing to current flow; `docs/online-flow.md`: current instructions; `docs/ai-usage.md`: attribution.

Gradle 8.10.2, Kotlin 2.0.21, AGP 8.7.2, Java/JVM target 17. Android min SDK 29, compile/target 34, application ID `xyz.ghostwitness.app`, version 0.1.0. Core depends on BouncyCastle 1.78.1. App uses Nearby 19.3.0 and platform HTTP/JSON, JobScheduler, Android Keystore and VideoView. Rewards pin ethers 6.15.0, solc 0.8.30 and Ganache 7.9.2 in a lockfile.

```powershell
.\gradlew.bat :core:test :core:koverVerify :app:assembleDebug :app:lintDebug
cd rewards
npm ci
npm test
npm run compile
```

The core coverage gate is 80%. SDK platform 34 and JDK 17+ are needed. Local Android SDK configuration is ignored; node_modules, build outputs and .env files are ignored. No Android instrumentation suite or CI workflow is present.

## Capture and local transfer

1. Recovery setup happens on the original owner's recovery phone: create an X25519 key pair and copy only its public key to the recorder. Recorder setup also needs the owner's wallet address. The wallet signing key and content recovery key are separate.
2. `MainActivity` selects a camera and starts a single-use `CapturePipeline`. The default tier is V1, clamped to device capabilities. Debug builds without a recovery public key are explicitly unrecoverable TEST MODE; release capture refuses. A build property remains a public-key fallback.
3. Camera2 feeds preview and an external SurfaceTexture. `CameraRenderer` crops/scales frames into H.264 input surfaces. `FrameGate` thins to the tier frame rate. `AudioEncoder` encodes mono 44.1 kHz AAC-LC at 32 kbps.
4. `SegmentPlanner` emits muxing actions around two-second boundaries; video opens on a keyframe. `VideoChain` overlaps encoder generations for size changes and retires the old encoder after cutover. Bitrate changes immediately; fps changes at boundaries. Tier A retains a minimal video encoder but writes audio only.
5. `SegmentSink` uses memfd on Android 11+ or a private temporary file on Android 10. `Mp4Scrubber` neutralizes metadata before sealing; parse failure reports an error and seals original metadata to preserve the evidence.
6. The single sealer encrypts, erasure-codes, commits, signs and writes shards/entry/root before scheduling transfer. Plaintext buffers are cleared. Optional debug copies rebuild from shard indices 1,3,4 and deliberately retain playable plaintext files.
7. Nearby uses `P2P_STAR`, service `xyz.ghostwitness.mesh`; the recorder advertises `GW-W-<sid-prefix>`, witnesses connect with random `GW-H-<tag>` names. BYTES control frames announce FILE shard payload IDs. Nearby chooses Bluetooth/Wi-Fi and handles upgrades. Matching role prefixes are automatically accepted.
8. Receiving phones send `GWI1` with their device writer address. For a shard transfer the recorder sends its GWP signed entry proof, GWT signed upload assignment, GW2 header and FILE body. These repeat with shard retries. Original GWS/GWR/GWE/GW2/GWA encodings remain supported.
9. `HolderLedger` matches header and body in either order, checks length/root and known entry, then `HolderNode` persists before batched ACKs. The new upload path additionally verifies the independently signed record and assignment before publishing.

Stop finishes capture and wipes K after queued sealing; transfer continues while the recorder screen stays active. Leaving/pausing the recorder stops capture/mesh. Witness cloud upload jobs continue independently of the receiving screen. Process death can lose the open segment and unpersisted work. Recorder capture and Nearby transfer do not automatically resume old sessions after restart.

## Cryptographic contracts

- Fresh session ID: 16 bytes; fresh AES content key K: 32 bytes; salt: 8 bytes.
- AES-256-GCM nonce: `salt || u32(index)`; AAD: `sid || u32(index) || u64(start_ms) || prev_ciphertext_hash`. Genesis previous hash is 32 zeros. Ciphertext includes a 16-byte tag.
- K is wrapped with BouncyCastle HPKE base mode X25519/HKDF-SHA256/AES-256-GCM, info `ghostwitness/v2/wrapped-key`, AAD session ID. Wrapped value is 80 bytes.
- Systematic RS(5,3), GF(256), polynomial 0x11D; any three distinct valid shards rebuild ciphertext. Three copies of the same index are insufficient.
- Merkle leaves use SHA-256 `0x00 || bytes`, nodes `0x01 || left || right`, RFC 9162 power-of-two splitting. Shard roots commit to 1 KiB blocks.
- ManifestEntry is 211 bytes for five shards: index, start time, duration, ciphertext length, previous ciphertext hash, n and shard roots.
- Session signatures are deterministic secp256k1/SHA-256, low-s, 64-byte r||s. Public key is 65-byte uncompressed SEC1. Running root signs `GWROOT || sid || uptoIndex || root`.
- GWP embeds GWS header, owner routing address, entry, signed root and inclusion proof. It authenticates a segment without obtaining every earlier entry. Routing address is outside the old signed root; trusted identity comes from the pinned session-key hash.
- GWT signs `GWASSIGN || sid || index || shard || uploader-address || shard-sha256`. This prevents someone from redirecting rewards by copying public references/tickets.
- The full trusted session code is `sidHex:SHA256(session-public-key)`. It must be retained independently by the owner. Public Arkiv tags or unsolicited ownership transfers alone cannot establish authenticity.
- `SegmentRecovery` verifies the proof/pin, checks known chain links, unwraps K, verifies/rebuilds shards and GCM-decrypts. Across missing segments, the unavailable predecessor hash cannot be checked; the signed entry remains independently verifiable.
- Only append from the newest `RecordingSession` state; branching from an older state could reuse the same nonce. K copies are cleared where owned, but comprehensive JVM/provider/signing-key zeroization is not guaranteed.

## Scheduling and adaptive quality

The pure scheduler sends oldest unsafe segments first and reserves three committed slots before filling parity. It prefers data indices, balances per-segment assignments across live witnesses and uses goodput as a tiebreaker. There are two sends per witness in flight; unacknowledged sends expire after five seconds or peer loss. ACKed placements remain recorded after disconnect.

SENDING means fewer than three ACKs, RECOVERABLE means at least three, FULLY_SPREAD means five. The banner requires all finished segments to be recoverable; it excludes the current open segment and does not prove online availability. Worst-case holder-loss tolerance with five shards across 5/4/3/2/1 witnesses is 2/1/1/0/0.

Auto evaluates every two seconds, constrained by 70% of measured busy-time goodput and witness count. Backlog forces immediate degradation; increases need three clean windows. No bulk lane targets audio; initial connection quality is a prior refined by callbacks/ACKs. Manual tier selection bypasses automatic application.

| Tier | Video / bitrate | Holder-count cap |
|---|---|---|
| V2 | 1280x720, 30 fps, 1.5 Mbps | 5+ |
| V1 | 848x480, 30 fps, 800 kbps | 4 |
| V1_24 | 848x480, 24 fps, 600 kbps | 3 |
| V0 | 432x240, 24 fps, 250 kbps | intermediate goodput choice |
| V0_15 | 432x240, 15 fps, 200 kbps | 2 |
| V0_10 | 432x240, 10 fps, 150 kbps | 1 |
| A | audio only | 0 / no bulk |

## Durable uploads and Arkiv

`OnlineSettings` encrypts RPC/Bee credentials, writer and recovery keys with Android Keystore. Each receiving device has its own persistent funded writer address; random Nearby tags are not reward identities. Upload settings include network constraint and maximum per-write GLM fee.

`OnlineStore` discovers contributions from persisted `holdings/<sid>/records`, `tickets` and `shards` files. `uploads/<i>-<j>.json` and `metadata-<i>.json` checkpoint progress via AtomicFile. These are device-local journals; no keys are uploaded.

`UploadService` uses persisted JobScheduler jobs, unmetered internet by default, exponential retries and periodic catch-up. `UploadEngine` verifies files/tickets, uploads the same encrypted shard bytes using Bee `/bytes` with deferred upload disabled, then downloads and compares bytes. It publishes a signed `segment` record followed by an `upload` entity with reference, hashes, uploader and ticket. Reward requests run separately so verifier downtime cannot stall evidence uploads.

`ArkivClient` targets Rabdi 7794095 and native operations at 0x4400000000000000000000000000000000000044. `ArkivCodec` sorts typed attributes and encodes create plus transfer to the original owner's wallet, predicting the key from chain/address/writer/entity-nonce/salt. Creation is readonly with about 30 days lifetime. Writer remains the creator. The original owner can extend/delete/transfer later.

Transactions use explicit legacy gasPrice, unsigned gas estimation, operation floors, half-block and configured cost/balance ceilings, and exclusive per-device writing. Exact raw bytes/hash/predicted key are journaled before broadcast. Missing receipts retain the original transaction; reverts stop recreation for manual reconciliation. Successful receipts must include the expected create event and entity readback must match owner, creator and attributes before indexing is marked complete.

Queries use typed predicates, constrained owner/session/kind, bounded keyset pagination and narrow projections. Every page is a current observation, not an atomic snapshot. Public owner/tag fields are supplemented with signed evidence and a pinned recorder key.

## Online recovery and rewards

`OnlineActivity` offers setup, recovery-key creation/import, upload status and recovery controls. `RecoveryEngine` queries segment records and uploads, discards malformed/untrusted entities, downloads candidates until each segment has three distinct valid shards, reconstructs/decrypts and saves private MP4s/report. `VideoView` plays available files sequentially. Gaps continue; an entirely unuploaded tail is unknowable. Export and QR pairing UI remain future work.

`rewards/contracts/UploadRewards.sol` is an optional funded native-token pool, default deployment target Base Sepolia 84532. Pools are scoped to owner+sid, pin the recorder key, fix reward/expiry/max segments, pay once per owner/sid/index/shard and refund unused funds after expiry. Signatures bind contract, chain, owner, session, index, shard, recipient, reference and deadline. Recipient accounting distinguishes an already-paid contribution from a slot rewarded to another valid assigned witness.

`rewards/server.mjs` is a trusted verifier/relayer. It checks Arkiv creator/ownership/confirmations, authenticated metadata, actual Bee readback, shard commitments and signed recipient assignment before authorizing and relaying payment. Request URLs/amounts/recipients cannot be caller-selected. It binds loopback behind an HTTPS/rate-limited proxy. A copied reference alone is insufficient. Reassigned duplicates compete for one reward slot. This is not decentralized proof of permanent custody.

Deploy with `scripts/deploy.mjs`, fund from the original owner's machine using `scripts/fund.mjs`, then start the verifier and configure its URL on witness devices. No contract deployment, wallet funding, live upload or payout was executed during implementation. Base reward support does not imply event bounty eligibility.

## Thread ownership

Main: activities and tier controls. `gw-capture`: Camera2/video callbacks and muxer. `gw-gl`: EGL/rendering. `gw-audio`: microphone/AAC. `gw-seal`: ordered crypto/store/session updates. `gw-mesh`: witness state and 250 ms scheduler ticks. `gw-holder`: ledger/storage and 100 ms ticks; ACK cadence 300 ms. JobService background executor: serialized upload/index writes. Recovery activity executor: online fetching/decryption. UI listeners post to main.

Preserve single-owner mutable state, ordered session appends, GL detach before codec release, persistence before ACK and receipt reconciliation before new index writes.

## Source navigation

Core packages: `crypto` (Bytes, ChunkCipher, KeyWrap, SessionSecrets, SessionSigner); `rs` (Galois, GfMatrix, ReedSolomon, ShardCodec); `merkle` (Merkle); `manifest` (ManifestEntry, SignedRoot, ChainVerifier); `session` (SessionHeader, RecordingSession); `codec` (SegmentCodec); `capture` (Tier, FrameGate, RateController, SegmentPlanner); `sched` (SchedulerModel, Scheduler, Progress); `mesh` (ControlFrames, WitnessMesh, HolderLedger); `wire` (Frames); `media` (Mp4Scrubber); `eth` (Abi, Rlp, Keccak, EthKey, Eip1559Tx, LegacyTx); `arkiv` (ArkivCodec); `recovery` (RecoveryRecord, UploadTicket, SegmentRecovery).

App: MainActivity, HolderActivity, OnlineActivity, Permissions, RecoveryKeys; `capture` (CapturePipeline, CameraSource, AudioEncoder, VideoEncoder, VideoChain, SegmentMuxer, SegmentSink, gl/EglCore, CameraProgram, CameraRenderer); `mesh` (NearbyLink, WitnessMeshDriver); `holder` (HolderNode); `store` (SessionStore, HoldingStore); `online` (OnlineSettings, OnlineStore, Http, BeeClient, ArkivClient, UploadEngine, UploadService, RecoveryEngine, Numbers).

## Verification and remaining limits

Local verification: core tests and 80% Kover gate, Android debug assembly and lint, Solidity compilation and local EVM/proof tests. Independent ethers fixtures cover Arkiv ABI encoding and legacy signing. Tests include recovery of a later segment despite missing earlier entries, tampered proofs/tickets/shards, duplicate payouts, wrong-recipient authorization and expiry refunds. See running.md for final results.

Real phones, a team Bee service/postage batch, funded Rabdi writers, reward deployment/pool/relay gas and live cloud integration are still required. No measured users, throughput, p95 recovery latency or live entity/reference links are claimed. The plugin is installed locally but MCP connectivity in this chat is not verified.

Known limits: no automatic recorder-session resume; no receiving-witness ledger/identity rehydration for Nearby (disk uploads do resume); incomplete recordings may lose the current segment; no offline recovery server or export UI; a public index expires without renewal; Android background restrictions can delay uploads; long sessions retain in-memory manifest/scheduler data; original stores use rename without a multi-file transaction; debug/plaintext and scrub-fallback privacy conditions remain. Contract/verifier code is a demo implementation and has not been independently audited.
