# How GhostWitness uses Arkiv

Receiving witnesses publish signed segment metadata and verified Swarm references after internet
becomes available. Ownership transfers to the original victim's wallet while creator attribution
identifies the publishing witness. Readonly attributes protect the exact publication, while signed
records and a trusted session-key fingerprint authenticate evidence independently of public tags.

The recovery screen queries Arkiv to find available segment records and shard locations. Missing
records/references become visible recovery gaps; Arkiv is an input to a user-facing feature.
The reward verifier also reads upload entities and checks their creator and signed assignment before
authorizing payment on the separate configured EVM reward chain.

Expiry limits the live index's storage period. Availability beyond that period needs owner-authorized
renewal or an index backup. We do not claim that expiry erases prior public copies.

Implementation: `core/arkiv/ArkivCodec.kt`, `app/.../online/ArkivClient.kt`, `UploadEngine.kt`,
`RecoveryEngine.kt`, `rewards/server.mjs`. See `schema.md` and `../docs/online-flow.md`.
Live read/write demonstration remains pending deployment and funded writers.
