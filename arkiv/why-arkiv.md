# Why Arkiv

The intended users are people recovering evidence after the original recording phone disappears.
An independently queryable index lets the owner's recovery app discover shard references published
by several nearby witnesses without relying on the original recorder or a single private index server.
The owner can control entity retention; creator attribution identifies contribution publishers.

A private Postgres replacement could reproduce application queries but would require trusting and
maintaining that database operator for public discovery and wallet-based ownership. This implementation
uses native Arkiv RPC directly for app reads/writes; the optional reward verifier is still a trusted
operator for payout decisions and does not make Arkiv a decentralized proof-of-upload system.

Large encrypted shard bytes remain on Swarm. Wallet private keys remain in the wallet, recovered
plaintext stays on the recovery device, and the trusted key service holds derived recovery keys.
The index exposes session timing, sizes, wallet links and references. See `schema.md` for exact fields.
