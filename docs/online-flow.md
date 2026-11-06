# Online witness uploads and owner recovery

This is the current product flow. The recorder records and distributes encrypted shards locally. Nearby receiving phones are called witnesses in the product (the source still uses `holder`). Witnesses upload when suitable internet is available. The original owner's recovery phone reads Arkiv, downloads Swarm files, verifies, reconstructs and decrypts locally.

## Run the Android flow

1. Build/install `:app:assembleDebug` on the recorder, receiving witnesses, and recovery phone.
2. On the **recovery phone**, open **Online setup and owner recovery**, create the owner's recovery key, and retain that phone. Copy the displayed **public** key to the recorder. Private keys are encrypted with Android Keystore. A pre-existing `publicHex:privateHex` backup can be imported; keys cannot be silently replaced. There is no QR scanner or key-backup export UI yet.
3. In setup on the **recorder**, save that public key and the original owner's Ethereum wallet address. No wallet private key is needed on the recorder. The wallet address is also the owner used for the optional reward pool on Base Sepolia.
4. On each **receiving witness**, configure an HTTPS Bee API URL, its 64-hex postage batch ID, and the Rabdi RPC URL. Use the device's own access key if desired. Save setup and fund the displayed per-device writer address using the Rabdi faucet. Configure the maximum GLM fee per write; default is 0.01 GLM. Saving configured upload settings enables funded background index writes. The private writer key stays in this device's encrypted vault.
5. Start witness mode on receiving phones and recording on the recorder. Save the full **session code** displayed on the recorder: `session-id:SHA256(session-public-key)`. Transfer that code to the original owner through a trusted channel. The hash pins the recording's signer, preventing a copied Arkiv tag or unsolicited ownership transfer from substituting a recording.
6. Witnesses store shards, independently verifiable segment records (`GWP`), and recorder-signed upload assignments (`GWT`). GWS/GWR/GWE/GW2/GWA formats remain unchanged. A `GWI1` greeting gives the recorder the receiving witness's writer address. The recorder signs the session ID, segment index, shard index, writer address and shard SHA-256 in each assignment. Assignments are repeated with shard retries.
7. Android JobScheduler uploads after a qualifying connection becomes available. **Unmetered internet** is the default proxy for good connectivity; setup can permit metered connections. Jobs survive screen exit and reboot, with periodic catch-up and retry backoff. Force-stop, uninstall, battery restrictions, and erased app storage can still prevent work. Offline Nearby reception never waits for these uploads.
8. The uploader verifies stored bytes, uploads those exact ciphertext shard bytes using Bee `/bytes`, then downloads and compares them. It writes a `segment` entity and an `upload` entity to Arkiv, transferring ownership to the original owner's wallet in the same native transaction. It records the exact signed transaction before broadcast, reconciles receipt/entity readback before progressing, and stops automatic recreation on a reverted receipt. A missing receipt preserves the original transaction for retry/reconciliation.
9. On the **recovery phone**, configure a Bee read endpoint and Rabdi RPC, save setup, enter the owner's address and trusted full session code, and choose **Download and recover**. No postage or funded writer is needed for recovery reads. The app queries Arkiv, verifies signed entry inclusion proofs, tries candidate Swarm references until three distinct valid shard indices are available, unwraps K with the owner's X25519 recovery private key, reconstructs/decrypts and plays MP4s in order. Gaps are reported and later segments continue.

Recovered plaintext MP4s and a report are retained in private `files/recovered/<sid>/`. Playback currently uses platform `VideoView`, with one file after another; Media3 export and share/export UI are not implemented. Recovery reports the known signed history and cannot discover a recording tail whose metadata never reached the network. A crash may also lose the open segment and queued work that was not sealed/persisted.

## What is persisted

- Receiving witness files: `files/holdings/<sid>/shards/<i>-<j>.bin`, `records/<i>.bin`, `tickets/<i>-<j>.bin`.
- Progress: `uploads/<i>-<j>.json` and `uploads/metadata-<i>.json`, written with Android `AtomicFile`. Journals preserve references, raw transactions/hashes, receipt/readback state, entity key and reward state.
- Upload work is discovered from durable records and shard files on disk. A restart does not require recreating a Nearby connection to upload an already received contribution.
- Existing pre-change holdings without GWP/GWT metadata cannot be uploaded by this new path. Record new sessions for the demo.

## Verification and availability

GWP contains the public session header, owner routing address, manifest entry, signed root and Merkle inclusion path. Each segment verifies independently, so a missing earlier entry cannot block authenticating a later uploaded segment. The routing address is not part of the old root signature; authoritative recording identity comes from the pinned session signing key. Reward authorization additionally comes from the recorder-signed GWT assignment.

Three distinct valid shard indices are required for each remotely recoverable segment. Three copies of the same shard do not suffice. Nearby ACKs describe local storage only. Swarm readback confirms bytes observed through the configured Bee endpoint at that moment, not perpetual network custody. The independent reward verifier is a trusted operator, not a decentralized storage-proof protocol.

## Reward path

The `rewards/` package contains a native-token EVM reward pool, deploy/fund scripts and a verifier/relayer. Default reward network is **Base Sepolia (84532)**; Arkiv remains **Rabdi (7794095)** and uses native entity operations. Selecting Base for rewards does not establish eligibility for a Base event bounty.

The original owner funds a pool bound to their wallet, session ID and session-key hash, with a fixed per-shard reward, maximum segment count and expiry. The verifier checks:

- An existing Arkiv upload entity with at least three subsequent blocks, creator equal to its claimed uploader, and ownership matching the funded owner.
- The signed record against the session-key hash registered in that pool.
- Actual downloaded Swarm bytes against the entry's block Merkle commitment and recorded SHA-256.
- The recorder's signed assignment binding these exact bytes and shard to the uploader's wallet.

The verifier signs a contract/chain/owner/session/index/shard/recipient/reference/deadline-bound authorization and relays the payment. Anyone can request verification, but the recipient cannot be changed. A `(owner, session, index, shard)` slot pays at most once; when a shard was reassigned, the first successful valid assigned claimant wins. Reuploads and duplicate entities do not increase rewards. No upload means no qualifying claim. A witness can upload/index successfully while rewards remain pending due to missing deployment/funding.

Setup and operation:

```powershell
cd rewards
npm ci
npm test
npm run compile
# Set DEPLOYER_KEY, VERIFIER_ADDRESS, REWARD_RPC, REWARD_CHAIN_ID locally.
npm run deploy
# Save REWARD_CONTRACT from deployment. On the owner's machine, set OWNER_KEY,
# SESSION_CODE, POOL_ETH, REWARD_ETH and optionally MAX_SEGMENTS/POOL_SECONDS.
node scripts/fund.mjs
# Set VERIFIER_KEY, REWARD_CONTRACT, ARKIV_RPC, BEE_URL and reward RPC locally.
npm run serve
```

The service binds loopback port 8787. Expose `/claim` through an HTTPS reverse proxy with request/rate limits. Set its base URL in witness setup. POST body: `{"entityKey":"0x..."}`. The service reads all URLs from operator configuration; callers cannot select a fetch URL, recipient or reward amount. App journals distinguish pending from verifier-confirmed paid claims. A lost response is retried against the contract's one-time claim state. Verifier payouts require network RPCs, a funded pool and relay gas. No deployment, pool funding, or live payout was performed as part of implementation. The contract and verifier are a demo implementation, not an audited deployment.

## Current external setup and validation

No team Bee node, postage batch, funded Rabdi writer, reward deployment or live entity/reference pair was supplied. Device-to-device and live cloud behavior still require those resources and real phones. Successful local builds/tests are not evidence of a live submission.

Protocol references used for implementation:

- [Arkiv native writes, attribute types, entity-key derivation and events](https://docs.arkiv.network/json-rpc/mutating-entities/).
- [Arkiv query projections and bounded keyset traversal](https://docs.arkiv.network/json-rpc/querying-data/).
- [Bee byte upload and retrieval API](https://docs.ethswarm.org/api/). The app encrypts before uploading and uses normal 32-byte Swarm references; it does not publish a Swarm encrypted reference containing a key.
- [Android JobScheduler constraints and persistence](https://developer.android.com/reference/android/app/job/JobInfo.Builder).
- [Base Sepolia network configuration](https://docs.base.org/get-started/connect-to-base).

The Arkiv Codex plugin was installed locally (`arkiv@arkiv`, 0.5.4). Its tools are not active in the existing chat; local installation is not proof of an MCP connection, wallet funding or app integration. Android uses the native JSON-RPC API, not a guessed TypeScript SDK API.
