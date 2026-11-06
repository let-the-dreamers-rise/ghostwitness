import { createServer } from 'node:http';
import { randomBytes, hkdfSync, timingSafeEqual } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CipherSuite, HkdfSha256, Aes256Gcm } from '@hpke/core';
import { DhkemX25519HkdfSha256 } from '@hpke/dhkem-x25519';
import { authorization, ownership, sha } from './protocol.mjs';

/** Trusted key custodian. Deploy independently of Bee/Arkiv/rewards and back up master + database. */
export function keyService({masterHex,origin,database='data/keys.sqlite',now=Date.now}) {
  if(!/^[0-9a-f]{64}$/i.test(masterHex)) throw Error('KEY_MASTER must be 32 random bytes, hex');
  const url=new URL(origin); if(url.protocol!=='https:'||url.origin!==origin) throw Error('PUBLIC_ORIGIN must be an HTTPS origin');
  const master=Buffer.from(masterHex,'hex');
  const db=new DatabaseSync(database); db.exec('PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY, message TEXT NOT NULL, expires INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0); CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY, owner TEXT NOT NULL, expires INTEGER NOT NULL);');
  const kem=new DhkemX25519HkdfSha256();
  const suite=new CipherSuite({kem,kdf:new HkdfSha256(),aead:new Aes256Gcm()});
  const info=Buffer.from('ghostwitness/v2/wrapped-key');
  async function ownerKey(owner) {
    const seed=Buffer.from(hkdfSync('sha256',master,Buffer.alloc(0),Buffer.from('ghostwitness/wallet-key/v1/'+owner),32));
    try { return await kem.deriveKeyPair(seed); } finally {seed.fill(0);}
  }
  const validAddress=value=>typeof value==='string'&&/^0x[0-9a-f]{40}$/.test(value)&&!/^0x0{40}$/.test(value);
  async function handle(path,body,bearer) {
    db.prepare('DELETE FROM challenges WHERE expires < ?').run(now()-86400000);
    db.prepare('DELETE FROM tokens WHERE expires < ?').run(now());
    if(path==='/challenge') {
      const {owner,recorder}=body; if(!validAddress(owner)||!validAddress(recorder)) throw Error('Invalid address');
      if(db.prepare('SELECT count(*) AS n FROM challenges WHERE expires > ?').get(now()).n>=10000) throw Error('Busy');
      const key=await ownerKey(owner); const pub=Buffer.from(await kem.serializePublicKey(key.publicKey)).toString('hex');
      const issued=now(), expires=issued+300000, until=issued+365*86400000, nonce=randomBytes(32).toString('hex');
      const message=`GhostWitness Wallet Authorization v1\nService: ${origin}\nOwner: ${owner}\nRecorder: ${recorder}\nRecovery public key: ${pub}\nIssued at: ${issued}\nRecorder valid until: ${until}\nSign-in expires: ${expires}\nChallenge: ${nonce}`;
      db.prepare('INSERT INTO challenges(id,message,expires) VALUES(?,?,?)').run(nonce,message,expires);
      return {challengeId:nonce,message};
    }
    if(path==='/login') {
      const {challengeId,signature}=body;
      if(typeof challengeId!=='string'||!/^0x[0-9a-f]{130}$/i.test(signature??'')) throw Error('Invalid login');
      const row=db.prepare('SELECT * FROM challenges WHERE id=?').get(challengeId);
      if(!row||row.used||row.expires<=now()) throw Error('Challenge expired or used');
      const auth=authorization(row.message,signature);
      const token=randomBytes(32).toString('hex');
      db.exec('BEGIN IMMEDIATE');
      try {
        if(db.prepare('UPDATE challenges SET used=1 WHERE id=? AND used=0').run(challengeId).changes!==1) throw Error('Challenge used');
        db.prepare('INSERT INTO tokens(hash,owner,expires) VALUES(?,?,?)').run(sha(Buffer.from(token)).toString('hex'),auth.owner,now()+3600000);
        db.exec('COMMIT');
      } catch(error) {db.exec('ROLLBACK');throw error;}
      return {token,expiresAt:now()+3600000,message:row.message,signature};
    }
    if(path==='/unlock') {
      if(typeof bearer!=='string'||!/^Bearer [0-9a-f]{64}$/.test(bearer)) throw Error('Sign in required');
      const token=db.prepare('SELECT * FROM tokens WHERE hash=?').get(sha(Buffer.from(bearer.slice(7))).toString('hex'));
      if(!token||token.expires<=now()) throw Error('Sign in required');
      if(typeof body.proof!=='string'||!/^([0-9a-f]{2}){1,4096}$/i.test(body.proof)||! /^[0-9a-f]{64}$/i.test(body.deliveryPublic??'')) throw Error('Invalid unlock request');
      const proof=ownership(Buffer.from(body.proof,'hex'));
      if(proof.wallet.owner!==token.owner||proof.wallet.origin!==origin) throw Error('Wrong owner or key service');
      const key=await ownerKey(token.owner);
      const pub=Buffer.from(await kem.serializePublicKey(key.publicKey));
      if(!timingSafeEqual(pub,Buffer.from(proof.wallet.publicKey,'hex'))) throw Error('Recovery key mismatch');
      const h=proof.header;
      if(h.readUInt16BE(103)!==80||h[28]!==5||h[29]!==3) throw Error('Unsupported recording header');
      const wrapped=h.subarray(105);
      const recipient=await suite.createRecipientContext({recipientKey:key,enc:wrapped.subarray(0,32),info});
      const contentKey=Buffer.from(await recipient.open(wrapped.subarray(32),proof.sid));
      try {
        if(contentKey.length!==32) throw Error('Invalid recording key');
        const delivery=await kem.deserializePublicKey(Buffer.from(body.deliveryPublic,'hex'));
        const sender=await suite.createSenderContext({recipientPublicKey:delivery,info});
        const ct=Buffer.from(await sender.seal(contentKey,proof.sid));
        return {wrappedKey:Buffer.concat([Buffer.from(sender.enc),ct]).toString('hex')};
      } finally {contentKey.fill(0);}
    }
    throw Error('Unknown operation');
  }
  let active=0;
  const server=createServer(async(req,res)=>{
    res.setHeader('content-type','application/json'); res.setHeader('cache-control','no-store');
    if(req.method!=='POST'||!['/challenge','/login','/unlock'].includes(req.url)) {res.writeHead(404);res.end('{}');return;}
    if(active>=16) {res.writeHead(429);res.end('{}');return;}
    active++;
    try {
      let size=0;const chunks=[];
      for await(const chunk of req) {size+=chunk.length;if(size>12000) throw Error('Request too large');chunks.push(chunk);}
      const result=await handle(req.url,JSON.parse(Buffer.concat(chunks).toString('utf8')),req.headers.authorization);
      res.end(JSON.stringify(result));
    } catch {res.writeHead(403);res.end('{"error":"Wallet authorization or key unlock failed"}');}
    finally {active--;}
  });
  server.requestTimeout=30000;server.headersTimeout=10000;
  return {server,handle,close:()=>{db.close();master.fill(0);}};
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  mkdirSync('data',{recursive:true,mode:0o700});
  const service=keyService({masterHex:process.env.KEY_MASTER??'',origin:process.env.PUBLIC_ORIGIN??''});
  service.server.listen(Number(process.env.PORT??8790),'127.0.0.1',()=>console.log('Wallet key service listening on loopback; publish through HTTPS with rate limits'));
}
