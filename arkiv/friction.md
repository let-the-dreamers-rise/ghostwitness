# Integration friction

This is implementation feedback, not a report of a live Arkiv failure.

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

No access key, wallet, provider URL containing credentials, or raw transport error is included here.
Live RPC errors and reproducible reports: pending configured-network testing.
