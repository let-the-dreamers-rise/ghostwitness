import { createHash } from 'node:crypto';
import { SigningKey, Signature, sha256, getBytes } from 'ethers';
export const hash = (...parts) => createHash('sha256').update(Buffer.concat(parts)).digest();
const leaf = b => hash(Buffer.from([0]), b);
const node = (a,b) => hash(Buffer.from([1]), a, b);
function root(leaves) {
  if (leaves.length === 1) return leaves[0];
  let k = 1; while (k * 2 < leaves.length) k *= 2;
  return node(root(leaves.slice(0, k)), root(leaves.slice(k)));
}
export function verifyRecord(hex, signingKeyHash) {
  if (!/^0x[0-9a-f]+$/i.test(hex) || hex.length > 8194) throw Error('Invalid record');
  const bytes = Buffer.from(hex.slice(2), 'hex'); let at = 0;
  const take = n => { if (at + n > bytes.length) throw Error('Truncated record'); const b = bytes.subarray(at, at += n); return b; };
  const u16 = () => take(2).readUInt16BE();
  if (take(4).toString('hex') !== '47575001') throw Error('Invalid GWP version');
  const header = take(u16());
  if (header.length !== 185 || header.subarray(0,4).toString('hex') !== '47575301' || header[28] !== 5 || header[29] !== 3) throw Error('Invalid session header');
  const sid = header.subarray(4,20); const publicKey = header.subarray(38,103);
  if (sha256(publicKey).toLowerCase() !== signingKeyHash.toLowerCase()) throw Error('Untrusted recorder key');
  const owner = '0x' + take(20).toString('hex');
  const entry = take(u16());
  if (entry.length !== 211 || entry[50] !== 5) throw Error('Invalid entry');
  const index = entry.readUInt32BE(0); const ctLen = entry.readUInt32BE(14);
  const signed = take(120);
  if (signed.subarray(0,4).toString('hex') !== '47575201' || !signed.subarray(4,20).equals(sid)) throw Error('Invalid root');
  const upto = signed.readUInt32BE(20);
  if (index > upto || upto >= 2147483647) throw Error('Invalid segment index');
  const sig = Signature.from({ r: '0x'+signed.subarray(56,88).toString('hex'), s: '0x'+signed.subarray(88,120).toString('hex'), v: 27 });
  const message = hash(Buffer.from('GWROOT'), sid, signed.subarray(20,56));
  let verified = false;
  for (const v of [27,28]) {
    try { if (SigningKey.recoverPublicKey(message, { r: sig.r, s: sig.s, v }).toLowerCase() === ('0x'+publicKey.toString('hex')).toLowerCase()) verified = true; } catch {}
  }
  if (!verified) throw Error('Invalid root signature');
  const count = take(1)[0]; if (count > 31) throw Error('Proof too long');
  let fn = index, sn = upto, r = leaf(entry);
  for (let i=0;i<count;i++) {
    if (sn === 0) throw Error('Invalid proof');
    const p = take(32);
    if (fn % 2 === 1 || fn === sn) {
      r = node(p,r);
      while (fn % 2 === 0 && fn !== 0) { fn = Math.floor(fn/2); sn = Math.floor(sn/2); }
    } else r = node(r,p);
    fn = Math.floor(fn/2); sn = Math.floor(sn/2);
  }
  if (at !== bytes.length || sn !== 0 || !r.equals(signed.subarray(24,56))) throw Error('Invalid inclusion proof');
  return { sid: '0x'+sid.toString('hex'), owner, index, ctLen, shardRoots: Array.from({length:5}, (_,j) => entry.subarray(51+j*32,83+j*32)), bytes };
}
export function verifyShard(record, shardIndex, bytes) {
  if (!Number.isInteger(shardIndex) || shardIndex < 0 || shardIndex > 4 || bytes.length !== Math.ceil(record.ctLen / 3)) throw Error('Invalid shard size/index');
  const leaves = []; for(let i=0;i<bytes.length;i+=1024) leaves.push(leaf(bytes.subarray(i,i+1024)));
  if (!root(leaves).equals(record.shardRoots[shardIndex])) throw Error('Shard commitment mismatch');
}
export function verifyTicket(hex, record, publicKey, uploader, shardIndex, shardBytes) {
  if (!/^0x[0-9a-f]{282}$/i.test(hex)) throw Error('Missing recorder assignment');
  const b = Buffer.from(hex.slice(2), 'hex');
  if (b.subarray(0,4).toString('hex') !== '47575401' || '0x'+b.subarray(4,20).toString('hex') !== record.sid ||
      b.readUInt32BE(20) !== record.index || b[24] !== shardIndex ||
      ('0x'+b.subarray(25,45).toString('hex')).toLowerCase() !== uploader.toLowerCase() ||
      !b.subarray(45,77).equals(hash(shardBytes))) throw Error('Assignment mismatch');
  const digest = hash(Buffer.from('GWASSIGN'), b.subarray(4,77));
  let valid = false;
  for (const v of [27,28]) {
    try { if (SigningKey.recoverPublicKey(digest, {r:'0x'+b.subarray(77,109).toString('hex'),s:'0x'+b.subarray(109,141).toString('hex'),v}).toLowerCase() === publicKey.toLowerCase()) valid = true; } catch {}
  }
  if (!valid) throw Error('Invalid assignment signature');
}
