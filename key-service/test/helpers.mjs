import { CipherSuite, HkdfSha256, Aes256Gcm } from '@hpke/core';
import { DhkemX25519HkdfSha256 } from '@hpke/dhkem-x25519';
import { keccak256 } from 'ethers';
import { sha } from '../protocol.mjs';
export const kem=new DhkemX25519HkdfSha256();
export const suite=new CipherSuite({kem,kdf:new HkdfSha256(),aead:new Aes256Gcm()});
export const info=Buffer.from('ghostwitness/v2/wrapped-key');
export const u16=n=>{const bytes=Buffer.alloc(2);bytes.writeUInt16BE(n);return bytes;};
export async function recording(login,device,{startedAt=1700000001000,content=Buffer.alloc(32,7),sid=Buffer.alloc(16,9)}={}) {
  const publicHex=login.message.split('\n')[4].slice('Recovery public key: '.length);
  const recipientPublicKey=await kem.deserializePublicKey(Buffer.from(publicHex,'hex'));
  const sender=await suite.createSenderContext({recipientPublicKey,info});
  const ct=await sender.seal(content,sid);
  const header=Buffer.alloc(185);header.write('GWS');header[3]=1;sid.copy(header,4);header.writeBigUInt64BE(BigInt(startedAt),20);header[28]=5;header[29]=3;
  Buffer.from(device.signingKey.publicKey.slice(2),'hex').copy(header,38);header.writeUInt16BE(80,103);
  Buffer.concat([Buffer.from(sender.enc),Buffer.from(ct)]).copy(header,105);
  const message=Buffer.from(login.message);const certificate=Buffer.concat([u16(message.length),message,Buffer.from(login.signature.slice(2),'hex')]);
  const signature=device.signingKey.sign(keccak256(Buffer.concat([Buffer.from('GWSESSION'),header,sha(certificate)])));
  const deviceSig=Buffer.from(signature.r.slice(2)+signature.s.slice(2)+signature.yParity.toString(16).padStart(2,'0'),'hex');
  return {header,proof:Buffer.concat([Buffer.from('GWO1'),u16(header.length),header,u16(certificate.length),certificate,deviceSig]),sid,content};
}
