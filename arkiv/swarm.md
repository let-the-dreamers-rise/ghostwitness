# Swarm integration and evidence checklist

Witnesses upload **existing encrypted shard bytes** to Bee `/bytes`. Arkiv `upload.ref` stores the
ordinary 64-hex reference; `sha256`, shard index and signed commitments support verification.
The recovery phone reads Arkiv then fetches `/bytes/<ref>` and checks three distinct valid shards.

Application AES-GCM encryption happens before upload. `Swarm-Encrypt` is not enabled, so no native
Swarm encrypted reference containing a key is published to Arkiv. The content key remains HPKE-wrapped
to the wallet-specific X25519 key held by the trusted wallet key service. MetaMask signs owner login;
the service rewraps the content key to the recovery phone. See `../docs/wallet-recovery.md`.

Upload/read implementation: `app/src/main/kotlin/xyz/ghostwitness/app/online/BeeClient.kt`, called by
`UploadEngine.kt` and `RecoveryEngine.kt`; reward readback also appears in `rewards/server.mjs`.
Uploads set `Swarm-Postage-Batch-Id` and `Swarm-Deferred-Upload: false`, then download/compare bytes.

For reproduction, follow `../docs/online-flow.md`: configure receiving witness devices with an HTTPS
Bee service and valid postage, fund their Rabdi writer addresses, record/distribute a new session,
allow witness internet, then sign in with the same MetaMask wallet on another phone, find recordings
and select Recover. Configure Reown and the HTTPS key service first.
Expected: Arkiv references appear, at least three distinct shards per segment download and decrypt,
and missing segments are shown as gaps.

## Live evidence

| Item | Value |
|---|---|
| What is on Swarm | AES-GCM encrypted Reed-Solomon shards of each 2 s video segment (raw `/bytes` uploads) |
| What is on Arkiv | `session`, `segment` and `upload` entities; the Swarm reference is the `ref` field of each `upload` entity |
| Arkiv entity | `upload` `0x9e9626dff353302cc524fc65cdf3e9a7cf409dedf5128006477c4255e796e15a` (segment 0, shard 0) |
| Swarm reference it points to | `ac8456bc3340a9724a67b4f0477db3055e920c2ace59cb2e5b3fffa670df2bf8` (130,734 bytes; SHA-256 `208d0ed351445688a621f1042f47242664631a786ac1a671f68e0756af844df6`, equal to the entity's `sha256` field) |
| Link to open | <https://shots-tournament-brought-sufficient.trycloudflare.com/bytes/ac8456bc3340a9724a67b4f0477db3055e920c2ace59cb2e5b3fffa670df2bf8> (our demo Bee node; read-only, ciphertext; the hostname changes if the tunnel restarts) |
| Postage batch | `6cb6a8661a2171c5294f4d059c5ca246ed40937dbfa1a6c5f0c473b455a89b21`, depth 19, bought on the Swarm Sepolia testnet |
| Wallet that bought it | `0x552B3e49b39E9c257AcDb2E94558501c85ffa577` (our Bee node's wallet) |
| Bee node | Our own Bee 2.8.3 light node on the Swarm Sepolia testnet (network 10), Docker. Phones reach it through an allow-list proxy: uploads need a secret path and the team batch ID; `GET /bytes/<ref>` is public |
| Upload and read code | `app/src/main/kotlin/xyz/ghostwitness/app/online/BeeClient.kt` (upload + readback compare), called by `UploadEngine.kt`; reads in `RecoveryEngine.kt`; reward readback in `rewards/server.mjs` |
| Demo video moment | See the submission video: the witness upload status and the recovery screen fetching shards |

To check it yourself: open the link above (or `curl` it) and compare `sha256sum` with the `sha256`
attribute of the Arkiv entity (`arkiv_getEntity`, command in `schema.md`). The bytes are ciphertext by
design; the recovery app needs three distinct shards plus the wallet-authorized key to decrypt.

To run one upload and one read with your own testnet Bee node (needs a usable postage batch):

```bash
REF=$(curl -s -X POST -H "Swarm-Postage-Batch-Id: $BATCH" -H 'Swarm-Deferred-Upload: false' --data-binary @shard.bin http://localhost:1633/bytes | jq -r .reference)
curl -s http://localhost:1633/bytes/$REF | cmp - shard.bin && echo same-bytes
```

## What got in our way with Swarm

- Bee's postage sync needs old event logs. The first public Sepolia RPC we used prunes them (`could not
  get blockchain log: pruned history unavailable`), so the node never became ready. A Tenderly gateway
  worked but rate-limits (HTTP 429), and the initial sync took about 40 minutes.
- Getting sBZZ: it is only on a Uniswap testnet pool, and the token address is easy to misuse (one of us
  sent funds to the token contract itself). A testnet sBZZ faucet would remove the hardest step.
- Stamp sizing is not obvious: depth 17 and 18 hold very little in practice, so we bought depth 19.
- Bee's API mixes data endpoints with wallet, stamp and chequebook endpoints on one port. We had to write
  an allow-list proxy before letting phones reach it.
- There is no public testnet gateway for reads, so a judge cannot open a testnet reference without our
  node being up.
