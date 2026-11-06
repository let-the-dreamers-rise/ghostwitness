# GhostWitness Arkiv schema

Native JSON-RPC on Rabdi, chain 7794095. Each entity is created readonly and transferred to the
original owner's wallet in the same transaction. `$creator` remains the receiving witness's device
writer. Expiry is 1,296,000 blocks (approximately 30 days); the owner must renew or back up the index
before expiry. Schema tags alone are not authentication; recovery verifies the wallet authorization
and delegated recorder signature to pin the session header.

Common indexed attributes: `app` str=`ghostwitness`, `kind` str, `sid` str (32 lowercase hex),
`dedupe` str (64 lowercase hex), `v` u64=3. Attribute cells are sorted by ASCII name.

| Kind | Indexed attributes | Unindexed bytes |
|---|---|---|
| session | `startedAt` u64, `recorder` str | `data`: GWO1 header, wallet-signed delegation and recorder signature |
| segment | `i` u64, `recordHash` str | `data`: GWP record containing header, entry, signed root and inclusion proof |
| upload | `i`, `j`, `size` u64; `ref`, `sha256`, `uploader`, `recordHash` str | `ticket`: recorder-signed GWT upload assignment |

`ref` is the ordinary 32-byte Swarm reference to an already AES-GCM-encrypted shard, not a Swarm
encrypted reference containing a decryption key. Media, content/recovery private keys and device
writer private keys never go to Arkiv. Timing, sizes, uploader addresses and ownership are public.

Discovery filters `app`, `kind=session` and `$owner`, pages by `$key`, and validates wallet and
recorder signatures. Recovery then filters by the authenticated `sid` and validates signed segment
records against that header, downloading candidate references for three distinct shards.
Writers query `dedupe` scoped to creator before writing. Cross-writer duplicates are allowed and
readers validate/group them; no claim of protocol uniqueness is made.

Query used by recovery, with the demo owner and a real session:

```text
app = str('ghostwitness') AND kind = str('upload') AND sid = str('fa6f49a0fa59f0e6b699bf4bd659b044') AND $owner = addr(0xc89363f084f5a16433d50d5958a40b747ff9a395) ORDER BY $key ASC
```

## Live entities (Rabdi testnet)

Writers (witness device keys, `$creator`): `0xd071978f76f636f74bbbeb36c625d374160f7a93`,
`0xc512b28e224972a884d9d02aedb89106d46d6ad5`. Owner of every entity (`$owner`, the recorder's
MetaMask wallet): `0xc89363f084f5a16433d50d5958a40b747ff9a395`.

| Kind | Entity key |
|---|---|
| session | `0x86191b20628043225f71ac48f93e0d539f46a619799b0ecda767cbcd81b37555` |
| segment (i=0) | `0xf4b22b38b40723d648c9eb93821f0103e4b88e4b4984bb9b61abca1d23be1e50` |
| upload (i=0, j=0) | `0x9e9626dff353302cc524fc65cdf3e9a7cf409dedf5128006477c4255e796e15a` |

Read one back:

```bash
curl -s https://rpc.rabdi.db-chain.testnet.arkiv.network -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"arkiv_getEntity","params":["0x9e9626dff353302cc524fc65cdf3e9a7cf409dedf5128006477c4255e796e15a"]}'
```
