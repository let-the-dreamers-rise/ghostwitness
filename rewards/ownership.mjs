import { Signature, getAddress, keccak256, recoverAddress, sha256, verifyMessage } from 'ethers';
import { hash } from './proof.mjs';

// Mirrors core/.../recovery/WalletOwnership.kt. The owner's wallet signs a WalletAuthorization naming a
// recorder key; the recorder signs the session header, which carries the session signing key. A valid chain
// proves the owner stands behind this recording, so their deposit may pay for it.
const PREFIXES = ['Service: ', 'Owner: ', 'Recorder: ', 'Recovery public key: ', 'Issued at: ', 'Recorder valid until: ', 'Sign-in expires: ', 'Challenge: '];
const SKEW_MS = 300_000;

function reader(bytes) {
  let at = 0;
  const take = n => { if (at + n > bytes.length) throw Error('Truncated ownership proof'); return bytes.subarray(at, at += n); };
  return { take, u16: () => take(2).readUInt16BE(), done: () => at === bytes.length };
}

function signature(bytes) {
  const v = bytes[64] < 27 ? bytes[64] + 27 : bytes[64];
  return Signature.from({ r: '0x' + bytes.subarray(0, 32).toString('hex'), s: '0x' + bytes.subarray(32, 64).toString('hex'), v });
}

function parseAuthorization(walletBytes) {
  const r = reader(walletBytes);
  const message = r.take(r.u16()).toString('utf8');
  const sig = r.take(65);
  if (!r.done()) throw Error('Invalid wallet authorization');
  const lines = message.split('\n');
  if (lines.length !== 9 || lines[0] !== 'GhostWitness Wallet Authorization v1' || !PREFIXES.every((p, i) => lines[i + 1].startsWith(p))) throw Error('Invalid wallet authorization');
  const field = i => lines[i + 1].slice(PREFIXES[i].length);
  const auth = { owner: field(1), recorder: field(2), issuedAt: Number(field(4)), validUntil: Number(field(5)) };
  if (![auth.owner, auth.recorder].every(a => /^0x[0-9a-f]{40}$/.test(a)) || !Number.isSafeInteger(auth.issuedAt) || !Number.isSafeInteger(auth.validUntil)) throw Error('Invalid wallet authorization');
  if (verifyMessage(message, signature(sig)).toLowerCase() !== auth.owner) throw Error('Invalid wallet signature');
  return auth;
}

export function verifyOwnership(hex, expectedOwner) {
  if (!/^0x[0-9a-f]+$/i.test(hex) || hex.length > 8194) throw Error('Invalid ownership proof');
  const r = reader(Buffer.from(hex.slice(2), 'hex'));
  if (r.take(4).toString('ascii') !== 'GWO1') throw Error('Invalid ownership proof');
  const header = r.take(r.u16());
  const walletBytes = r.take(r.u16());
  const recorderSig = r.take(65);
  if (!r.done() || header.length < 105 || header.subarray(0, 4).toString('hex') !== '47575301') throw Error('Invalid ownership proof');
  const auth = parseAuthorization(walletBytes);
  if (auth.owner !== getAddress(expectedOwner).toLowerCase()) throw Error('Ownership proof is for another wallet');
  const startedAt = Number(header.readBigUInt64BE(20));
  if (startedAt < auth.issuedAt - SKEW_MS || startedAt > auth.validUntil) throw Error('Session outside wallet authorization');
  const digest = keccak256(Buffer.concat([Buffer.from('GWSESSION'), header, hash(walletBytes)]));
  if (recoverAddress(digest, signature(recorderSig)).toLowerCase() !== auth.recorder) throw Error('Invalid recorder signature');
  return { sid: '0x' + header.subarray(4, 20).toString('hex'), signingKeyHash: sha256(header.subarray(38, 103)), owner: auth.owner };
}
