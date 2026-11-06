# GhostWitness

A recording phone encrypts each 2 s segment, erasure-codes it into 5 shards (any 3 rebuild it), and
spreads the shards to nearby holder phones. When a device is online it also stores ciphertext on Swarm
and writes a chunk index to the witness's Arkiv wallet. See `docs/spec.md` for the full design.

Built at ETHGlobal Mumbai 2026 (from scratch at the event).

## Current flow

Recorder -> nearby receiving witnesses -> Swarm ciphertext + Arkiv index -> owner's online recovery phone.
Witnesses persist received files and upload on suitable internet, including after the receiving screen
closes. The recovery screen reads Arkiv, downloads and verifies Swarm shards, reconstructs/decrypts with
the owner's recovery key, and plays available segments with gaps reported. Optional funded upload rewards
use recorder-signed assignments and an independent verifier; receiving a shard alone earns no reward.

See [online setup, recovery and reward instructions](docs/online-flow.md) for the implementation,
required Bee/RPC/funding configuration and remaining live-device validation. Existing design details in
`docs/spec.md` are superseded by that flow where they describe offline recovery or exclude rewards.

This revision builds locally, but no live upload/recovery/payout is claimed without configured services.
Arkiv writer addresses are generated per device and displayed in **Online setup**; publish the actual
funded demo addresses and entity/reference evidence in `arkiv/` after the demo deployment.

## CROPS: who learns what

Censorship resistance, open source, privacy and security, as built in this repo. The video content, the
content key K and the owner's recovery private key never leave the recorder (K) or the recovery phone
(private key). Everyone else sees ciphertext and metadata only.

| Party | What it learns | What it never gets |
|---|---|---|
| Nearby receiving witnesses | Encrypted shards they hold; the public session header (salt, session public key, wrapped K); manifest entries (segment times and sizes); the owner's wallet address (in each `GWP` record); their own signed upload assignments | Plaintext, K, the recovery private key |
| Bee node operator (Swarm) | Uploading witnesses' IP addresses, upload times, ciphertext sizes and references; the recovery phone's IP and which references it reads | Plaintext or K. Shards are encrypted before upload; references are plain 32-byte content addresses with no key inside |
| Arkiv RPC operator | Callers' IPs and queries (session id, owner wallet), writer transactions | Anything not already public on Arkiv |
| Anyone reading Arkiv | Owner wallet, session id, segment count, start times and sizes, signed segment records, uploader writer addresses, Swarm references; all sessions owned by the same wallet are linkable | Plaintext, K, recovery keys, which phone recorded |
| Reward verifier (optional) | Entity keys submitted for claims and the claimant's IP | Plaintext, K |
| Reward chain, Base Sepolia (optional) | Payouts linking uploader addresses to an owner and session | Plaintext, K |

What runs with no server of ours:

- Recording and spreading shards to nearby witnesses need no internet at all.
- Uploading and recovery need *an* Arkiv RPC and *a* Bee node, but no GhostWitness service: any
  Rabdi RPC and any Bee node work, configured per device in Online setup. There is no indexer;
  recovery queries the Arkiv RPC directly.
- Only the optional rewards depend on a trusted verifier we run, and evidence uploads never wait for it.

Limits, stated plainly: a recording whose shards never reached an online witness cannot be recovered
remotely; recovery today needs internet (direct witness-to-owner transfer is not built); the index
expires after about 30 days unless the owner's wallet extends it; Nearby peers and the Bee operator see
network metadata. The code is open source in this repository.

## Modules

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin/JVM library: segment encryption with a hash-chained AAD, Reed-Solomon(5,3), Merkle commitments, manifest and signed roots, session header and recording session, wire frames, the data-first transfer scheduler, the segment planner (where 2 s cuts go) and the rate controller |
| `app/` | Android app (minSdk 29). Camera2 + MediaCodec H.264/AAC into 2 s MP4 segments, sealed straight into shards on the phone. Plaintext segments live in an in-memory file (memfd) on Android 11+. Witness uploads to Swarm and Arkiv, and owner recovery, are in `online/` |
| `rewards/` | Optional upload reward pool (Solidity), verifier/relayer and deploy/fund scripts |
| `arkiv/` | Arkiv schema, integration notes and the submission evidence checklist |

## Build the app

```bash
./gradlew :app:assembleDebug
```

Set the owner's X25519 recovery public key and wallet in **Online setup** before recording.
`-Pgw.recoveryPub=<64 hex chars>` remains a public-key fallback. Without a key, debug builds run in
test mode and recordings cannot be recovered; release builds refuse capture. In debug builds a
checkbox saves a playable copy of each segment, rebuilt from shards 1, 3 and 4, to
`/sdcard/Android/data/xyz.ghostwitness.app/files/debug-plain/` (`adb pull` it to check playback).

## Run the tests

```bash
./gradlew :core:test :core:koverVerify
```

Requires JDK 17 or newer (bytecode targets 17). The app also needs the Android SDK (platform 34).

## AI usage

See `docs/ai-usage.md`.
