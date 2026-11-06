# AI usage

ETHGlobal requires AI tools to be attributed. This file lists where they were used.

| Files | Tool | How |
|---|---|---|
| `core/**` (all source and tests) | Claude Code (Claude Opus) | Generated from `docs/spec.md` sections 4 to 6, then reviewed and run by the team |
| `app/**` | Claude Code (Claude Opus) | Generated from `docs/spec.md` section 5; on-device behaviour verified by the team |
| `docs/spec.md` | Claude Code | Drafted from the team's design notes; decisions made by the team |
| Online upload/recovery changes, reward contract/verifier/tests, `context.md`, `docs/online-flow.md`, `arkiv/` | OpenAI Codex | Implemented from the owner's clarified recorder/witness/cloud/recovery flow; local compilation and automated tests performed, live-device/network validation pending |
| Spec-conformance fixes (`OnlineIssue` errors, reserved Arkiv names, viem test vector), Arkiv receipt wait in `ArkivClient.kt`, rewards lockfile fix, owner-deposit rewards (`UploadRewards.sol` deposits, `rewards/ownership.mjs`, deposit tests), README CROPS and live-evidence sections, `arkiv/*.md` evidence, live testnet setup (Bee node, proxy, tunnels, contract deployment) | Claude Code (Claude Opus) | Written with the team during the event; builds, lint and tests run locally; live behaviour checked on Rabdi, Swarm testnet and Base Sepolia |
| Wallet connection/recovery UI, wallet ownership proofs, `key-service/**`, interoperability tests and wallet setup docs | OpenAI Codex | Implemented the owner's chosen MetaMask plus trusted key-service flow; configured endpoints remain user-supplied, local automated validation performed, physical-device/live-service validation pending |
