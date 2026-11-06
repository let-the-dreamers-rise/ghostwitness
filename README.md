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

## Modules

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin/JVM library: segment encryption with a hash-chained AAD, Reed-Solomon(5,3), Merkle commitments, manifest and signed roots, session header and recording session, wire frames, the data-first transfer scheduler, the segment planner (where 2 s cuts go) and the rate controller |
| `app/` | Android app (minSdk 29). Camera2 + MediaCodec H.264/AAC into 2 s MP4 segments, sealed straight into shards on the phone. Plaintext segments live in an in-memory file (memfd) on Android 11+ |

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
