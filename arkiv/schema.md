# GhostWitness Arkiv schema

Native JSON-RPC on Rabdi, chain 7794095. Each entity is created readonly and transferred to the
original owner's wallet in the same transaction. `$creator` remains the receiving witness's device
writer. Expiry is 1,296,000 blocks (approximately 30 days); the owner must renew or back up the index
before expiry. Schema tags alone are not authentication; recovery pins the recorder's signing key.

Common indexed attributes: `app` str=`ghostwitness`, `kind` str, `sid` str (32 lowercase hex),
`dedupe` str (64 lowercase hex), `v` u64=3. Attribute cells are sorted by ASCII name.

| Kind | Indexed attributes | Unindexed bytes |
|---|---|---|
| segment | `i` u64, `recordHash` str | `data`: GWP record containing header, entry, signed root and inclusion proof |
| upload | `i`, `j`, `size` u64; `ref`, `sha256`, `uploader`, `recordHash` str | `ticket`: recorder-signed GWT upload assignment |

`ref` is the ordinary 32-byte Swarm reference to an already AES-GCM-encrypted shard, not a Swarm
encrypted reference containing a decryption key. Media, content/recovery private keys and device
writer private keys never go to Arkiv. Timing, sizes, uploader addresses and ownership are public.

Recovery filters `app`, `sid`, `kind` and `$owner`, pages by `$key`, validates signed segment records
against the trusted session code, and downloads candidate references for three distinct shards.
Writers query `dedupe` scoped to creator before writing. Cross-writer duplicates are allowed and
readers validate/group them; no claim of protocol uniqueness is made.

Example after replacing placeholders with a real owner and session:

```text
app = str('ghostwitness') AND kind = str('upload') AND sid = str('<32-hex-session-id>') AND $owner = addr(0x<40-hex-owner>) ORDER BY $key ASC
```

Actual demo writer addresses and entity keys: **pending funded device setup and a live run**.
