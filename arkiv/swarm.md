# Swarm integration and evidence checklist

Witnesses upload **existing encrypted shard bytes** to Bee `/bytes`. Arkiv `upload.ref` stores the
ordinary 64-hex reference; `sha256`, shard index and signed commitments support verification.
The recovery phone reads Arkiv then fetches `/bytes/<ref>` and checks three distinct valid shards.

Application AES-GCM encryption happens before upload. `Swarm-Encrypt` is not enabled, so no native
Swarm encrypted reference containing a key is published to Arkiv. The content key remains HPKE-wrapped
to the original owner's X25519 recovery key.

Upload/read implementation: `app/src/main/kotlin/xyz/ghostwitness/app/online/BeeClient.kt`, called by
`UploadEngine.kt` and `RecoveryEngine.kt`; reward readback also appears in `rewards/server.mjs`.
Uploads set `Swarm-Postage-Batch-Id` and `Swarm-Deferred-Upload: false`, then download/compare bytes.

For reproduction, follow `../docs/online-flow.md`: configure receiving witness devices with an HTTPS
Bee service and valid postage, fund their Rabdi writer addresses, record/distribute a new session,
allow witness internet, then recover on the owner's phone using the original key and session code.
Expected: Arkiv references appear, at least three distinct shards per segment download and decrypt,
and missing segments are shown as gaps.

Required live evidence (not yet available; do not submit placeholders as successful integration):

| Item | Status |
|---|---|
| Bee node/service URL | Pending team configuration |
| Postage stamp batch ID and buyer/provider wallet | Pending |
| Actual funded Arkiv writer addresses | Pending device setup |
| Arkiv entity key and openable reference link | Pending one live upload/read |
| Demo video timestamp showing Swarm | Pending live demo |
| Real Swarm friction and measurements | Pending trial |

A team-selected open-source license is also still needed for the event's repository requirement.
