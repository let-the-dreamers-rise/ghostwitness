# GhostWitness

A recording phone encrypts each 2 s segment, erasure-codes it into 5 shards (any 3 rebuild it), and
spreads the shards to nearby holder phones. When a device is online it also stores ciphertext on Swarm
and writes a chunk index owned by the original owner's Arkiv wallet. See `docs/wallet-recovery.md`
for the current flow and `docs/spec.md` for the original protocol design.

Built at ETHGlobal Mumbai 2026 (from scratch at the event).

## Current flow

Recorder -> nearby receiving witnesses -> Swarm ciphertext + Arkiv index -> owner's online recovery phone.
Witnesses persist received files and upload on suitable internet, including after the receiving screen
closes. The recovery screen reads Arkiv, downloads and verifies Swarm shards, reconstructs/decrypts with
wallet-authorized key delivery, and plays available segments with gaps reported. Connect MetaMask on
the recorder before capture, then connect the same wallet on any recovery phone, find recordings by
date and select Recover. No pairing, session-code entry or separate recovery secret is required.
The key service is a trusted custodian; the wallet signs without exporting its private key.
Optional funded upload rewards
use recorder-signed assignments and an independent verifier; receiving a shard alone earns no reward.

See [wallet setup and recovery](docs/wallet-recovery.md) and [upload/reward instructions](docs/online-flow.md) for the implementation,
required Bee/RPC/funding configuration and remaining live-device validation. Existing design details in
`docs/spec.md` are superseded by that flow where they describe offline recovery or exclude rewards.

## Live testnet deployment

Run on real Android phones during the event: a recorder, receiving witnesses and a recovery phone.

| What | Where |
|---|---|
| Arkiv writer addresses (witness devices) | `0xd071978f76f636f74bbbeb36c625d374160f7a93`, `0xc512b28e224972a884d9d02aedb89106d46d6ad5` |
| Owner wallet of every entity | `0xc89363f084f5a16433d50d5958a40b747ff9a395` |
| Arkiv network | Rabdi testnet, chain 7794095, [explorer](https://rabdi.explorer.arkiv.network) |
| Swarm | Bee 2.8.3 light node on the Swarm Sepolia testnet, postage batch `6cb6a8661a2171c5294f4d059c5ca246ed40937dbfa1a6c5f0c473b455a89b21` |
| Upload rewards contract | `0x8ea0AC50532B5F35a2bd9CC325144474C171888F` on Base Sepolia ([basescan](https://sepolia.basescan.org/address/0x8ea0AC50532B5F35a2bd9CC325144474C171888F)) |

One query you can run (lists the uploaded shards indexed for the demo owner):

```bash
curl -s https://rpc.rabdi.db-chain.testnet.arkiv.network -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"arkiv_query","params":["app = str('ghostwitness') AND kind = str('upload') AND $owner = addr(0xc89363f084f5a16433d50d5958a40b747ff9a395)",{"limit":"0x14","select":{"key":true,"creator":true,"attributes":true}}]}'
```

The public RPC has an anonymous query budget per client; if it answers `ANON_COST_LIMITED`, add an
access key from [hub.arkiv.network](https://hub.arkiv.network) as an `X-API-KEY` header. Evidence and
entity keys: `arkiv/schema.md` and `arkiv/swarm.md`.

## CROPS

- **C (censorship resistance):** the point of the app. A seized or smashed phone does not stop the
  recording: encrypted shards already sit on bystanders' phones, then on Swarm, indexed on Arkiv and owned
  by the victim's wallet. No GhostWitness server is in the recording or upload path; any witness with
  internet and a Bee endpoint can publish. What we control that users depend on: the wallet key service
  (needed to unlock a recording on a new phone) and the demo Bee/postage endpoint. If the key service goes
  away, recordings stay stored and indexed but cannot be decrypted; if our Bee endpoint goes away,
  witnesses can point at any other Bee node.
- **O (open source):** MIT licensed (`LICENSE`). Everything needed to build and run it is in this repo:
  Android app, core crypto library, key service, reward contract and verifier.
- **P (privacy):** media is AES-GCM encrypted on the recorder before it leaves the phone. Witnesses,
  Swarm, the Bee operator, the Arkiv RPC and Arkiv readers see ciphertext and metadata only; the table
  below lists exactly what each learns. Trade-off, stated plainly: the key-service operator can derive
  owner recovery keys and could decrypt a recording if it also fetched the ciphertext. No trackers or
  analytics in the app.
- **S (security):** keys stay on devices. MetaMask keys never leave MetaMask (only `personal_sign`);
  device writer keys and recovery keys are encrypted with Android Keystore. Every segment is signed and
  hash-chained, shards are Merkle-committed, and recovery verifies wallet authorization and recorder
  signatures before trusting any public Arkiv entry. Trust assumptions: the key-service operator and its
  master secret; the reward verifier for payouts only.

### Who learns what

Media is encrypted on the recorder. The recorder, recovering owner device and trusted wallet key
service can access content key K. MetaMask private keys stay inside the wallet. The service operator
can decrypt recordings and can block key delivery; wallet ownership alone cannot replace an unavailable
service master.

| Party | What it learns | What it never gets |
|---|---|---|
| Nearby receiving witnesses | Encrypted shards they hold; the public session header (salt, session public key, wrapped K); manifest entries (segment times and sizes); the owner's wallet address (in each `GWP` record); their own signed upload assignments | Plaintext, K, the recovery private key |
| Bee node operator (Swarm) | Uploading witnesses' IP addresses, upload times, ciphertext sizes and references; the recovery phone's IP and which references it reads | Plaintext or K. Shards are encrypted before upload; references are plain 32-byte content addresses with no key inside |
| Arkiv RPC operator | Callers' IPs and queries (session id, owner wallet), writer transactions | Anything not already public on Arkiv |
| Anyone reading Arkiv | Owner wallet, delegated recorder address, public authorization, session id, segment count, start times and sizes, signed segment records, uploader writer addresses, Swarm references; sessions and recorder addresses are linkable | Plaintext, K, private recovery keys |
| Wallet key-service operator | Wallet sign-ins, recorder addresses, requested session headers; derives owner recovery keys and unwraps K | Wallet private keys. The operator can decrypt media if it obtains the ciphertext |
| Reward verifier (optional) | Entity keys submitted for claims and the claimant's IP | Plaintext, K |
| Reward chain, Base Sepolia (optional) | Payouts linking uploader addresses to an owner and session | Plaintext, K |

Service dependencies:

- Recording and spreading shards work offline after initial wallet sign-in, while the cached recorder delegation remains valid.
- Witness uploads need a Rabdi RPC and a Bee node/postage batch, configured per device. They do not need the wallet key service.
- Wallet sign-in and owner key delivery need the deployed HTTPS key service and wallet connection infrastructure. Discovery queries Arkiv directly; media comes from Bee/Swarm.
- Optional rewards depend on a separately configured trusted verifier, and evidence uploads never wait for it.

Limits, stated plainly: a recording whose shards never reached an online witness cannot be recovered
remotely; recovery today needs internet (direct witness-to-owner transfer is not built); the index
expires after about 30 days unless the owner's wallet extends it; Nearby peers and the Bee operator see
network metadata. The code is open source in this repository under the MIT license.

## Modules

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin/JVM library: segment encryption with a hash-chained AAD, Reed-Solomon(5,3), Merkle commitments, manifest and signed roots, session header and recording session, wire frames, the data-first transfer scheduler, the segment planner (where 2 s cuts go) and the rate controller |
| `app/` | Android app (minSdk 29). Camera2 + MediaCodec H.264/AAC into 2 s MP4 segments, sealed straight into shards on the phone. Plaintext segments live in an in-memory file (memfd) on Android 11+. Witness uploads to Swarm and Arkiv, and owner recovery, are in `online/` |
| `rewards/` | Optional upload reward pool (Solidity), verifier/relayer and deploy/fund scripts |
| `arkiv/` | Arkiv schema, integration notes and the submission evidence checklist |
| `key-service/` | Wallet sign-in and custodial key delivery, Node/SQLite. Deploy behind HTTPS with a persistent backed-up master |

## Build the app

```bash
./gradlew :app:assembleDebug
```

Configure the HTTPS key service and public Reown project ID, then **Connect MetaMask and sign in**
before recording. See [service deployment](key-service/README.md).
`-Pgw.recoveryPub=<64 hex chars>` remains a legacy development fallback; wallet-authorized capture
requires wallet login. Without a key, debug builds run in
test mode and recordings cannot be recovered; release builds refuse capture. In debug builds a
checkbox saves a playable copy of each segment, rebuilt from shards 1, 3 and 4, to
`/sdcard/Android/data/xyz.ghostwitness.app/files/debug-plain/` (`adb pull` it to check playback).

## Run the tests

```bash
./gradlew :core:test :core:koverVerify
```

Requires JDK 17 or newer (bytecode targets 17). The app also needs the Android SDK (platform 35).
Run `npm ci` and `npm test` in `key-service/` for wallet sign-in/unlock tests.
Build defaults use one worker and a 1.5 GB JVM with Kotlin compilation in-process to fit 8 GB laptops.

## AI usage

See `docs/ai-usage.md`.
