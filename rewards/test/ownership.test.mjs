import test from 'node:test';
import assert from 'node:assert/strict';
import { Wallet, SigningKey, keccak256, sha256, getBytes } from 'ethers';
import { hash } from '../proof.mjs';
import { verifyOwnership } from '../ownership.mjs';

const u16 = n => { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b; };

async function proof({ start = 1_700_000_100_000, issued = 1_700_000_000_000, until = 1_700_086_400_000, tamper } = {}) {
  const owner = Wallet.createRandom(), recorder = Wallet.createRandom(), session = new SigningKey('0x'+'07'.repeat(32));
  const message = [
    'GhostWitness Wallet Authorization v1', 'Service: https://keys.example', `Owner: ${owner.address.toLowerCase()}`,
    `Recorder: ${recorder.address.toLowerCase()}`, `Recovery public key: ${'ab'.repeat(32)}`, `Issued at: ${issued}`,
    `Recorder valid until: ${until}`, `Sign-in expires: ${issued + 600_000}`, `Challenge: ${'cd'.repeat(32)}`,
  ].join('\n');
  const signed = tamper === 'message' ? message.replace('keys.example', 'evil.example') : message;
  const walletSig = Buffer.from((await owner.signMessage(message)).slice(2), 'hex');
  const walletBytes = Buffer.concat([u16(Buffer.byteLength(signed)), Buffer.from(signed), walletSig]);
  const header = Buffer.alloc(185); header.write('GWS'); header[3] = 1; Buffer.alloc(16, 9).copy(header, 4);
  header.writeBigUInt64BE(BigInt(start), 20); header[28] = 5; header[29] = 3;
  Buffer.from(session.publicKey.slice(2), 'hex').copy(header, 38); header.writeUInt16BE(80, 103);
  const digest = keccak256(Buffer.concat([Buffer.from('GWSESSION'), header, hash(walletBytes)]));
  const sig = (tamper === 'recorder' ? Wallet.createRandom() : recorder).signingKey.sign(digest);
  const recorderSig = Buffer.concat([Buffer.from(sig.r.slice(2) + sig.s.slice(2), 'hex'), Buffer.from([sig.yParity])]);
  const bytes = Buffer.concat([Buffer.from('GWO1'), u16(header.length), header, u16(walletBytes.length), walletBytes, recorderSig]);
  return { hex: '0x' + bytes.toString('hex'), owner: owner.address, session };
}

test('accepts a session the owner wallet authorized and returns its signing key hash', async () => {
  const p = await proof();
  const result = verifyOwnership(p.hex, p.owner);
  assert.equal(result.sid, '0x' + '09'.repeat(16));
  assert.equal(result.signingKeyHash, sha256(getBytes(p.session.publicKey)));
  assert.equal(verifyOwnership(p.hex, p.owner.toLowerCase()).signingKeyHash, result.signingKeyHash);
});

test('rejects proofs for another owner, edited messages, wrong recorders and out-of-window sessions', async () => {
  const p = await proof();
  assert.throws(() => verifyOwnership(p.hex, Wallet.createRandom().address));
  const edited = await proof({ tamper: 'message' });
  assert.throws(() => verifyOwnership(edited.hex, edited.owner));
  const wrongRecorder = await proof({ tamper: 'recorder' });
  assert.throws(() => verifyOwnership(wrongRecorder.hex, wrongRecorder.owner));
  const late = await proof({ start: 1_700_086_400_001 });
  assert.throws(() => verifyOwnership(late.hex, late.owner));
  const early = await proof({ start: 1_699_999_699_999 });
  assert.throws(() => verifyOwnership(early.hex, early.owner));
  assert.throws(() => verifyOwnership(p.hex.slice(0, -2), p.owner));
  assert.throws(() => verifyOwnership('0x1234', p.owner));
});
