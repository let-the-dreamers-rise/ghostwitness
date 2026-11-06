# GhostWitness: running log

What is built, what is verified, and what is left. Updated as work lands. The design is in
`docs/spec.md` (v2.1); "as built" there matches the code here.

Last updated: 2026-11-06.

## Online-flow revision

The owner's clarified flow is implemented in the working tree; see `docs/online-flow.md` for current
setup and limits. The historical block table below describes the original baseline, not this revision.
Added: signed per-segment inclusion records and upload assignments; witness disk queue; persistent
network-constrained jobs; Bee upload/readback; typed Arkiv create + ownership transfer, transaction
journaling and entity readback; owner-key setup/import; internet recovery and sequential playback;
optional Base Sepolia reward pool and verifier/relayer. Native wallet signing keys stay on each device.
Public Arkiv reads are part of the recovery UI. Offline holder serving is optional future work.

Fresh local checks for this revision: `:core:test :core:koverVerify :app:assembleDebug :app:lintDebug`
pass on Windows/JDK 17 using Gradle 8.10.2. Core tests include independent ethers ABI/signing fixtures,
gap-tolerant segment recovery and tampered upload assignments. `rewards/npm test` passes the funded
local EVM payout/replay/refund test and signed proof/shard/recipient validation test; Solidity compilation
and Node syntax checks pass. Android lint has zero errors; remaining warnings include prototype UI
localization, target/dependency versions and synchronous preference persistence. Device/network results
are still pending.

Review fixes: dynamic ABI offsets after multiword static tuples; Android 10 integer API compatibility;
signed recipient-bound upload assignments; reward retry/duplicate accounting; reward-service downtime
cannot block subsequent evidence uploads; malformed public index values are filtered before recovery.

No live Bee node/postage batch, funded Rabdi writers, reward deployment, or real-phone run is available
yet. Do not report the integrated flow as device/network verified. Submission evidence remains pending
in `arkiv/`. Existing compile/test results are updated after the final local checks.

Conformance audit against `docs/online-flow.md` (2026-11-06): the code follows the flow. Fixes from it:
- The Arkiv create + transfer encoding now has an independent viem vector test
  (`core/src/test/resources/codec-vector.*`, `ArkivCodecTest`).
- Attribute names are checked against Arkiv's reserved query words; the upload attribute `bytes`
  (reserved) is now `size`.
- Setup and RPC failures (wrong chain, unfunded writer, pending writer tx, fee ceiling, HTTP errors)
  raise `OnlineIssue`: they are retried and their message is shown in upload status and recovery,
  instead of reading as a corrupt stored file.
- README has a "CROPS: who learns what" section.
- The public Rabdi RPC accepts the query forms the app sends (`str()`, `addr()`, `$owner`,
  `$key > key()`, `ORDER BY $key ASC`); chain id 7794095 confirmed. Read-only check, no writes.

Checks after the audit: `:core:test :core:koverVerify :app:assembleDebug :app:lintDebug` pass;
core line coverage 99.43%. `rewards/npm test` not re-run (no `node_modules`; rewards code does not
use the renamed attribute).

## Status at a glance

| Block | What | State |
|---|---|---|
| 0 | Scaffold: Gradle, Kotlin 2.0.21, AGP 8.7.2, Kover 80% gate | Done |
| 1 | Core crypto: AES-GCM with chained AAD, HPKE key wrap, RS(5,3), RFC 9162 Merkle, manifest, signed roots | Done, unit-tested |
| 2 | Android capture: Camera2 -> GL scaler -> H.264 + AAC -> 2 s MP4 -> scrub -> seal into shards | Done, compiles and lints; not run on a phone yet |
| 3 | Mesh: data-first scheduler, rate control, Nearby P2P_STAR witness and holder mode, ACKs | Done, pure logic unit-tested; Nearby not run on phones yet |
| 3b | Review fixes (22 points, see spec section 16) | Done in spec and code |
| 4 | Arkiv index + Swarm uploads | Started: Ethereum layer (Keccak, RLP, ABI, EIP-1559 signing) done and tested. Arkiv ops, entity builders, JSON-RPC, flusher, Bee uploader left |
| 5 | Recovery app: Arkiv read, holder serve mode, Swarm fetch, rebuild, Media3 playback and export | Not started |
| 6 | Docs: README CROPS section, `/arkiv/*.md`, `/arkiv/swarm.md`, measured numbers | Not started |

Latest verification (Docker, JDK 21): `:core:test :core:koverVerify :app:assembleDebug :app:lintDebug`
all pass. Core line coverage 99.55%. No secrets or debug logging in sources.

## Done, in order

1. `chore`: Gradle scaffold, spec copy, AI usage notes.
2. `feat(core)`: segment crypto, Reed-Solomon, Merkle tree, manifest.
3. `feat(core)`: wire frames (`GW2` shard header, `GWA` ACK) and the data-first scheduler.
4. `feat(core)`: segment planner, rate controller, recording session and `GWS` session header.
5. `feat(app)`: capture pipeline into sealed shards, with a debug self-check that rebuilds from shards 1, 3, 4.
6. `feat(core)`: pure mesh logic for witness and holders (`GWE`, `GWR`, control codec, holder ledger).
7. `feat(app)`: Nearby P2P_STAR witness hub and holder mode.
8. `feat(core)`: Keccak, RLP, ABI and EIP-1559 signing for Arkiv writes.
9. `fix`: review points 21 and 22:
   - Scheduler places several shards per holder when there are fewer than 5. Before, 2 holders never
     made a segment recoverable.
   - Spare count is in holder losses. Holders are keyed by tag, not by Nearby endpoint.
   - Tier is `min(goodput tier, holder-count tier)`, on a 7-step ladder from 720p30 down to audio only.
   - A GL scaler sets size and fps per tier, size changes cut over at a segment boundary with two encoder
     generations, H.264 Baseline + CBR, camera size fallback, and an MP4 metadata scrub before
     encryption.
10. `docs`: spec v2.1 answering all 22 review points. Also: holder tags are now 8 random bytes (were 16
    bits), and release builds refuse to record without a recovery key.

## Left to do

### Block 4: Arkiv and Swarm

- [ ] `core/arkiv`: attribute encoding (typed, sorted, name rules), create + transfer-ownership ops,
      `execute()` calldata, entity-key prediction from `entityNonce`.
- [ ] Index entity builders for `session`, `chunk`, `placement`, `upload`, `root` with `dedupe` values
      (spec 8.2-8.4), and a sealed-payload helper (HPKE, `ghostwitness/v2/sealed`).
- [ ] `GWK` frame: recovery public key to holders on connect, never published.
- [ ] App: JSON-RPC client (`eth_chainId`, nonce, gas, `eth_call entityNonce`, `eth_sendRawTransaction`,
      receipt, `arkiv_query`), device writer key store, offline journal, flusher (every 5 segments or
      10 s, up to 16 creates per transaction, query-before-flush dedupe).
- [ ] App: Bee uploader (`POST /bytes`, postage batch header, `Swarm-Deferred-Upload: false`) for the
      witness's whole segments and holders' shards; `gw.upload` entities.
- [ ] Optional: Swarm feed per session (SOC signed with the session key).

### Block 5: Recovery

- [ ] Recovery key pairing by QR (replaces the `-Pgw.recoveryPub` build property).
- [ ] Holder serve mode and `GWQ` shard requests; recovery phone as `GW-R-<sid>` hub.
- [ ] Arkiv read path and on-screen fetch plan; Swarm fetch by reference.
- [ ] Verify root and chain, RS-rebuild, decrypt; gaps never stop the rest.
- [ ] Media3 playlist playback and Transformer export at one output size; recovery report.

### Block 6: Docs and measurements

- [ ] README CROPS section (what the Bee operator, Arkiv RPC and Nearby peers each learn).
- [ ] `/arkiv/*.md` and `/arkiv/swarm.md` (batch id and owner).
- [ ] Measured per-lane goodput, p95 time-to-RECOVERABLE, tier per holder count.

### Needs a real phone (none of this has run on a device yet)

- [ ] 60 s capture check with the debug self-check: segments play, about 2 s each, no gaps.
- [ ] GL scaler on at least two phone models; a tier switch that changes size mid-recording.
- [ ] Nearby: header/FILE arrive in either order, Wi-Fi upgrade happens, ACK round trips.
- [ ] Transfer gates: bulk lane p95 under 4 s at V1 with 5 holders; Bluetooth-only tier A measured.

### Needs the owner

- [ ] Git author name: commits show author "unknown" because `user.name` is not set.
- [ ] Arkiv access key (Rabdi testnet) and faucet funding for each device writer address.
- [ ] Witness wallet address.
- [ ] Bee node URL and postage batch id.
- [ ] Recovery public key for builds until QR pairing lands.
