# AI usage

ETHGlobal requires AI tools to be attributed. This file lists where they were used.

| Files | Tool | How |
|---|---|---|
| `core/**` (all source and tests) | Claude Code (Claude Opus) | Generated from `docs/spec.md` sections 4 to 6, then reviewed and run by the team |
| `app/**` | Claude Code (Claude Opus) | Generated from `docs/spec.md` section 5; on-device behaviour verified by the team |
| `docs/spec.md` | Claude Code | Drafted from the team's design notes; decisions made by the team |
| Online upload/recovery changes, reward contract/verifier/tests, `context.md`, `docs/online-flow.md`, `arkiv/` | OpenAI Codex | Implemented from the owner's clarified recorder/witness/cloud/recovery flow; local compilation and automated tests performed, live-device/network validation pending |
