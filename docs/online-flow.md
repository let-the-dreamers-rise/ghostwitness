# Online witness uploads and owner recovery

See [wallet login, deployment and complete owner flow](wallet-recovery.md) for the current recovery UI. The owner connects MetaMask on the recorder before going offline. A wallet-signed delegation authorizes offline capture. Any new recovery phone connects the same wallet, queries signed session entries by owner, lists recordings by date/time and recovers a selection. No pairing, manual wallet address, session-code entry or separate recovery secret is needed. The wallet signs; a trusted key service unlocks recording keys. It can decrypt recordings and must retain its master.

## Witness upload setup

1. Install the app on recorder, witnesses and recovery phone. Configure the owner's Reown project ID and HTTPS key-service origin, then sign in on the recorder.
2. On each receiving witness, set an HTTPS Bee API, 64-hex postage batch ID and Rabdi RPC (with its own access key if needed). Fund the displayed device writer on Rabdi. Save connection settings; the maximum per-write fee defaults to 0.01 GLM. A witness does not need an owner wallet login to receive/upload.
3. Start witness mode and record. Nearby transfers repeat GWO1 ownership proofs, GWP segment proofs, GWT recipient-bound upload tickets, GW2 shard headers and FILE bodies. GWI1 announces the witness writer address. Original GWS/GWR/GWE/GW2/GWA encodings remain unchanged.
4. Witnesses persist valid shards before ACK. JobScheduler uploads on qualifying internet (unmetered by default), with retries and periodic catch-up after screen exit/reboot. Android force-stop, battery restrictions and erased storage can stop jobs.
5. UploadEngine verifies bytes/tickets, uploads exact ciphertext via Bee `/bytes`, reads it back and compares it, then publishes session/segment/upload entities to Arkiv and transfers ownership to the owner. Exact signed transactions are journaled before broadcast. Receipts and entity readback must reconcile before progress; uncertain submissions retain their signed bytes, while reverted transactions require manual reconciliation.
6. On the recovery phone, configure Arkiv, a Bee read endpoint and the same key service/Reown project. Connect the original wallet, find recordings and select Recover. No postage or funded writer is required for reads. The app verifies wallet/delegated-recorder authorization, obtains a content key wrapped to a fresh local delivery key, fetches three distinct valid shards per segment, reconstructs, decrypts and plays available files. Gaps continue and are reported.

## Durable state and availability

Witness files are under `files/holdings/<sid>/`: `ownership.bin`, `records/<i>.bin`, `tickets/<i>-<j>.bin` and `shards/<i>-<j>.bin`. Atomic upload journals include `uploads/session.json`, `metadata-<i>.json` and `<i>-<j>.json`; they preserve references, raw transactions, receipts, entity keys and reward state. Late-arriving ownership proofs are published even if a shard was already indexed. Old manually keyed sessions are not migrated to wallet discovery.

Recovery saves plaintext MP4s/report in private `files/recovered/<sid>/` and plays them using VideoView. Export and offline holder serving are unimplemented. Open/unsealed segments and metadata that never reached the network can be lost. Three copies of one shard cannot replace three distinct indices. Nearby ACKs prove local storage only; Bee readback is an observation through that endpoint, not perpetual storage proof. Arkiv index retention is approximately 30 days unless renewed.

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

No Reown project ID, HTTPS key service, team Bee node, postage batch, funded Rabdi writer, reward deployment or live entity/reference pair was supplied. Device-to-device and live cloud behavior still require those resources and real phones. Successful local builds/tests are not evidence of a live submission.

Protocol references used for implementation:

- [Arkiv native writes, attribute types, entity-key derivation and events](https://docs.arkiv.network/json-rpc/mutating-entities/).
- [Arkiv query projections and bounded keyset traversal](https://docs.arkiv.network/json-rpc/querying-data/).
- [Bee byte upload and retrieval API](https://docs.ethswarm.org/api/). The app encrypts before uploading and uses normal 32-byte Swarm references; it does not publish a Swarm encrypted reference containing a key.
- [Android JobScheduler constraints and persistence](https://developer.android.com/reference/android/app/job/JobInfo.Builder).
- [Base Sepolia network configuration](https://docs.base.org/get-started/connect-to-base).

The Android implementation uses native Arkiv JSON-RPC. Installing the Arkiv plugin does not configure app endpoints, fund writers or deploy services.
