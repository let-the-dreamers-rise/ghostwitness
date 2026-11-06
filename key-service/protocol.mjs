import { createHash } from 'node:crypto';
import { verifyMessage, recoverAddress, keccak256, Signature } from 'ethers';
export const sha = b => createHash('sha256').update(b).digest();
export function authorization(message, signature) {
  const lines=message.split('\n');
  const prefixes=['Service: ','Owner: ','Recorder: ','Recovery public key: ','Issued at: ','Recorder valid until: ','Sign-in expires: ','Challenge: '];
  if(lines.length!==9 || lines[0]!=='GhostWitness Wallet Authorization v1' || !prefixes.every((p,i)=>lines[i+1].startsWith(p))) throw Error('Invalid wallet authorization');
  const [origin,owner,recorder,publicKey,issued,until,expiry,nonce]=prefixes.map((p,i)=>lines[i+1].slice(p.length));
  if(!/^0x[0-9a-f]{40}$/.test(owner)||!/^0x[0-9a-f]{40}$/.test(recorder)||!/^([0-9a-f]{64})$/.test(publicKey)||! /^[0-9a-f]{64}$/.test(nonce)) throw Error('Invalid authorization fields');
  if(verifyMessage(message,signature).toLowerCase()!==owner) throw Error('Wrong wallet signature');
  const issuedAt=Number(issued), validUntil=Number(until), signInExpires=Number(expiry);
  if(![issuedAt,validUntil,signInExpires].every(Number.isSafeInteger)||issuedAt<0||validUntil<=issuedAt||validUntil-issuedAt>366*86400000||signInExpires<=issuedAt||signInExpires-issuedAt>600000) throw Error('Invalid authorization time');
  const url=new URL(origin); if(url.protocol!=='https:'||url.origin!==origin) throw Error('Invalid service origin');
  return {origin,owner,recorder,publicKey,issuedAt,validUntil,signInExpires,nonce};
}
export function ownership(bytes) {
  if(bytes.length>4096) throw Error('Proof too large');
  let offset=0;
  const take=n=>{if(offset+n>bytes.length) throw Error('Truncated proof');return bytes.subarray(offset,offset+=n);};
  const length=()=>take(2).readUInt16BE();
  if(take(4).toString()!=='GWO1') throw Error('Invalid proof');
  const header=take(length()); const cert=take(length()); const deviceSig=take(65);
  if(offset!==bytes.length||header.length!==185||header.subarray(0,4).toString('hex')!=='47575301') throw Error('Invalid header');
  const messageLength=cert.readUInt16BE(); if(messageLength>2048||cert.length!==2+messageLength+65) throw Error('Invalid certificate');
  const message=cert.subarray(2,2+messageLength).toString('utf8');
  const wallet=authorization(message,'0x'+cert.subarray(2+messageLength).toString('hex'));
  const startedAt=Number(header.readBigUInt64BE(20));
  if(!Number.isSafeInteger(startedAt)||startedAt<wallet.issuedAt-300000||startedAt>wallet.validUntil) throw Error('Recorder authorization expired');
  if(deviceSig[64]>1) throw Error('Invalid device signature');
  const digest=keccak256(Buffer.concat([Buffer.from('GWSESSION'),header,sha(cert)]));
  const sig=Signature.from({r:'0x'+deviceSig.subarray(0,32).toString('hex'),s:'0x'+deviceSig.subarray(32,64).toString('hex'),v:27+deviceSig[64]});
  if(recoverAddress(digest,sig).toLowerCase()!==wallet.recorder) throw Error('Invalid recorder signature');
  return {header,wallet,sid:header.subarray(4,20)};
}
