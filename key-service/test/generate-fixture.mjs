// Test wallets only. Generate the fixture consumed by the Kotlin interoperability tests.
import { writeFileSync, mkdirSync } from 'node:fs';
import { Wallet } from 'ethers';
import { kem, recording } from './helpers.mjs';
const owner = new Wallet('0x' + '11'.repeat(32));
const device = new Wallet('0x' + '22'.repeat(32));
const pair = await kem.deriveKeyPair(Buffer.alloc(32, 7));
const publicHex = Buffer.from(await kem.serializePublicKey(pair.publicKey)).toString('hex');
const privateHex = Buffer.from(await kem.serializePrivateKey(pair.privateKey)).toString('hex');
const message = ['GhostWitness Wallet Authorization v1', 'Service: https://keys.example.com',
  `Owner: ${owner.address.toLowerCase()}`, `Recorder: ${device.address.toLowerCase()}`,
  `Recovery public key: ${publicHex}`, 'Issued at: 1700000000000',
  'Recorder valid until: 1731536000000', 'Sign-in expires: 1700000300000',
  'Challenge: ' + 'ab'.repeat(32)].join('\n');
const signature = await owner.signMessage(message);
const fixture = await recording({message, signature}, device);
const directory = new URL('../../core/src/test/resources/', import.meta.url);
mkdirSync(directory, {recursive: true});
writeFileSync(new URL('wallet.properties', directory),
  `proof=${fixture.proof.toString('hex')}\nprivate=${privateHex}\npublic=${publicHex}\ncontent=${fixture.content.toString('hex')}\nowner=${owner.address.toLowerCase()}\nrecorder=${device.address.toLowerCase()}\n`);
