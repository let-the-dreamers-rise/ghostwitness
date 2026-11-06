# GhostWitness

A recording phone encrypts each 2 s segment, erasure-codes it into 5 shards (any 3 rebuild it), and
spreads the shards to nearby holder phones. When a device is online it also stores ciphertext on Swarm
and writes a chunk index to the witness's Arkiv wallet. See `docs/spec.md` for the full design.

Built at ETHGlobal Mumbai 2026 (from scratch at the event).

## Modules

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin/JVM library: segment encryption with a hash-chained AAD, Reed-Solomon(5,3), Merkle commitments, manifest and signed roots, session header and recording session, wire frames, the data-first transfer scheduler, the segment planner (where 2 s cuts go) and the rate controller |
| `app/` | Android app (minSdk 29). Camera2 + MediaCodec H.264/AAC into 2 s MP4 segments, sealed straight into shards on the phone. Plaintext segments live in an in-memory file (memfd) on Android 11+ |

## Build the app

```bash
./gradlew :app:assembleDebug
```

Optional: `-Pgw.recoveryPub=<64 hex chars>` sets the recovery holder's X25519 public key. Without it the
app runs in test mode: the pipeline works, but nobody can recover the recordings. In debug builds a
checkbox saves a playable copy of each segment, rebuilt from shards 1, 3 and 4, to
`/sdcard/Android/data/xyz.ghostwitness.app/files/debug-plain/` (`adb pull` it to check playback).

## Run the tests

```bash
./gradlew :core:test :core:koverVerify
```

Requires JDK 17 or newer (bytecode targets 17). The app also needs the Android SDK (platform 34).

## AI usage

See `docs/ai-usage.md`.
