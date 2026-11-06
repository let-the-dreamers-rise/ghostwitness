# Integration friction

## From the live run on Rabdi

- **Anonymous query budget.** Mid-test, `arkiv_query` started answering
  `{"error":"ANON_COST_LIMITED","message":"unauthenticated query budget exhausted; get an API key at https://hub.arkiv.network ..."}`.
  Expected: a standard JSON-RPC error object (`{"jsonrpc":"2.0","id":1,"error":{"code":...}}`). The
  plain string `error` broke our JSON-RPC client, which reads `error.code`. Phones sharing one Wi-Fi
  share the budget, so a demo can fail for everyone at once. Reproduce: send many `arkiv_query` calls
  without an access key from one IP. Asks: return a JSON-RPC error, and document the quota and the
  `X-API-KEY` header on the main docs page (we found the header name only in the integration skill).
- **Reserved words in attribute names.** We named an attribute `bytes`; it is a reserved query word,
  which we only found from the query grammar. A write-time error for reserved names would save time.
- **Receipt timing.** Blocks arrive about every 2 s. Our uploader first checked for the receipt right
  after `eth_sendRawTransaction`, saw nothing, and ended the Android job, which put every write behind
  growing retry backoff. A short note on expected inclusion time next to the write examples would help.
- **Funding device keys.** Each witness phone has its own generated writer key. The faucet works through
  a connected browser wallet, so every new phone needed an extra hop. A claim-to-address option for
  testnet would help apps with many device keys.

## From the implementation

- The Android project has no native Kotlin SDK, so we implemented the documented JSON-RPC/ABI path.
  A small maintained JVM example covering typed attributes, ownership transfer and receipt readback
  would reduce work and interoperability risk.
- Existing project notes described payload fields and entity-key assumptions that differ from the
  current typed-attribute protocol. We followed the current write/read documentation and added an
  independent ethers ABI fixture. A clearly versioned migration note would help older integrations.
- Uncertain transaction outcomes require explicit journaling and reconciliation. We retain exact
  signed bytes, hashes and predicted keys, check receipt events, and read back attributes/ownership
  before marking work indexed. An Android reference importer would help demonstrate these steps.

Local issue: the Java Gradle wrapper download timed out; downloading the same distribution via curl
allowed local builds. This is not an Arkiv service issue.

No access key, wallet secret or provider URL containing credentials is included here.
