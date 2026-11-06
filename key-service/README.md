# Wallet-authorized key service

Trusted custodian for GhostWitness wallet-only recovery. It never accepts wallet private keys. It verifies `personal_sign`, derives a stable per-owner X25519 recovery key from an operator master, and rewraps an authorized recording's AES key to a fresh delivery public key. The operator can decrypt recordings: see [the trust model and app flow](../docs/wallet-recovery.md).

## Local tests

Requires Node.js 22.13+ with `node:sqlite` support (validated on Node 24).

```powershell
cd key-service
npm ci
npm test
```

Tests cover fresh-device recovery, wallet/device signature checks, challenge replay, expiration, tampered ownership and wrong-owner unlock rejection. `node test/generate-fixture.mjs` regenerates the public test-wallet fixture consumed by Kotlin's cross-language signature/HPKE test. These fixed test keys have no operational use.

## Deploy

1. Choose a permanent canonical HTTPS origin, such as `https://keys.example.com`. Publish a reverse proxy with a valid TLS certificate, request/body/time limits and per-client rate limits. Proxy only POST `/challenge`, `/login` and `/unlock` to loopback port 8790. Disable request-body and Authorization-header logging. The Android app rejects plain HTTP and origins containing a path.
2. Generate a **random 32-byte master**, store it in your deployment secret manager, and back it up offline. Do not commit it, paste it into chat or generate a new master on each restart. Inject it as `KEY_MASTER` (64 hex characters). Set `PUBLIC_ORIGIN` to the exact HTTPS origin; optionally set `PORT` (default 8790).
3. Run `npm ci --omit=dev` and `npm start` under a restricted service account, with a persistent private working directory. `data/keys.sqlite` uses WAL; keep SQLite's directory private and writable. Use a process supervisor for restart/health monitoring. The server binds `127.0.0.1` only.
4. Back up the master and database securely (use SQLite backup facilities or stop the process before copying the database/WAL). The master deterministically derives existing wallet keys. Database persistence retains challenge consumption and token state; losing it invalidates existing logins. Never replace the master to rotate login tokens.
5. Create a Reown project ID and set that ID plus `PUBLIC_ORIGIN` in GhostWitness's Connection settings. Sign in on the recorder, capture a new session, upload via witnesses, then sign in on a different phone with the same wallet and recover it.

Example environment names (supply actual values through your secret manager):

```text
KEY_MASTER=<64 hex characters generated locally with a cryptographic RNG>
PUBLIC_ORIGIN=https://keys.example.com
PORT=8790
```

The service validates owner-address signatures (EOA only), five-minute single-use challenges and one-hour bearer tokens stored only as hashes. Recorder delegation lasts 365 days so capture can happen offline. Historical proofs remain usable after that date if the signed recording time was within the delegation. Recording times are claims by an authorized recorder, not independent timestamps. Sign-out removes local credentials; server tokens expire naturally. There is no operator rotation/revocation or wallet account-recovery mechanism beyond the original wallet yet.

Deployment checklist: persistent master, persistent database, stable origin, HTTPS, restricted host access, proxy rate limits, secret backups, and a real two-device MetaMask/Nearby/Swarm/Arkiv trial. No URL/project ID is bundled and no live deployment has been performed.
