import test from 'node:test';
import assert from 'node:assert/strict';
import { Wallet } from 'ethers';
import { keyService } from '../server.mjs';
import { ownership } from '../protocol.mjs';
import { kem,suite,info,recording } from './helpers.mjs';
const instant=1700000000000;
async function login(service,owner,device) {
  const challenge=await service.handle('/challenge',{owner:owner.address.toLowerCase(),recorder:device.address.toLowerCase()});
  return service.handle('/login',{challengeId:challenge.challengeId,signature:await owner.signMessage(challenge.message)});
}
test('wallet login on a different device unlocks the same recording; other wallets and replays fail',async()=>{
  let now=instant;
  const service=keyService({masterHex:'01'.repeat(32),origin:'https://keys.example.test',database:':memory:',now:()=>now});
  try {
    const owner=new Wallet('0x'+'11'.repeat(32)),recorder=new Wallet('0x'+'22'.repeat(32)),phone=new Wallet('0x'+'33'.repeat(32)),stranger=Wallet.createRandom();
    const challenge=await service.handle('/challenge',{owner:owner.address.toLowerCase(),recorder:recorder.address.toLowerCase()});
    await assert.rejects(service.handle('/login',{challengeId:challenge.challengeId,signature:await stranger.signMessage(challenge.message)}));
    const signed={challengeId:challenge.challengeId,signature:await owner.signMessage(challenge.message)};
    const first=await service.handle('/login',signed);
    await assert.rejects(service.handle('/login',signed));
    const second=await login(service,owner,phone);
    assert.equal(first.message.split('\n')[4],second.message.split('\n')[4]);
    const file=await recording(first,recorder);
    assert.equal(ownership(file.proof).wallet.owner,owner.address.toLowerCase());
    const delivery=await kem.generateKeyPair();
    const body={proof:file.proof.toString('hex'),deliveryPublic:Buffer.from(await kem.serializePublicKey(delivery.publicKey)).toString('hex')};
    await assert.rejects(service.handle('/unlock',body));
    const other=await login(service,stranger,phone);
    await assert.rejects(service.handle('/unlock',body,'Bearer '+other.token));
    const response=await service.handle('/unlock',body,'Bearer '+second.token);
    const wrapped=Buffer.from(response.wrappedKey,'hex');
    const ctx=await suite.createRecipientContext({recipientKey:delivery,enc:wrapped.subarray(0,32),info});
    assert.deepEqual(Buffer.from(await ctx.open(wrapped.subarray(32),file.sid)),file.content);
    const damaged=Buffer.from(file.proof);damaged[100]^=1;
    await assert.rejects(service.handle('/unlock',{...body,proof:damaged.toString('hex')},'Bearer '+second.token));
    now+=3600001;
    await assert.rejects(service.handle('/unlock',body,'Bearer '+second.token));
    const renewed=await login(service,owner,phone);
    assert.ok((await service.handle('/unlock',body,'Bearer '+renewed.token)).wrappedKey);
  } finally {service.close();}
});
test('expired login challenges and mismatched recorder signatures fail',async()=>{
  let now=instant; const service=keyService({masterHex:'01'.repeat(32),origin:'https://keys.example.test',database:':memory:',now:()=>now});
  try {
    const owner=Wallet.createRandom(),device=Wallet.createRandom();
    const c=await service.handle('/challenge',{owner:owner.address.toLowerCase(),recorder:device.address.toLowerCase()});
    now+=300001;
    await assert.rejects(service.handle('/login',{challengeId:c.challengeId,signature:await owner.signMessage(c.message)}));
    const logged=await login(service,owner,device);
    const file=await recording(logged,Wallet.createRandom(),{startedAt:now+1000});
    assert.throws(()=>ownership(file.proof));
  } finally {service.close();}
});

test('HTTP API uses bounded authenticated requests and never returns a raw content key',async()=>{
  const service=keyService({masterHex:'01'.repeat(32),origin:'https://keys.example.test',database:':memory:',now:()=>instant});
  await new Promise(resolve=>service.server.listen(0,'127.0.0.1',resolve));
  try {
    const base=`http://127.0.0.1:${service.server.address().port}`;
    const post=(path,body,token)=>fetch(base+path,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});
    const owner=Wallet.createRandom(),device=Wallet.createRandom();
    const challenge=await (await post('/challenge',{owner:owner.address.toLowerCase(),recorder:device.address.toLowerCase()})).json();
    const signed={challengeId:challenge.challengeId,signature:await owner.signMessage(challenge.message)};
    const logged=await (await post('/login',signed)).json();
    assert.match(logged.token,/^[0-9a-f]{64}$/);
    assert.equal((await post('/login',signed)).status,403);
    const file=await recording(logged,device);
    const delivery=await kem.generateKeyPair();
    const body={proof:file.proof.toString('hex'),deliveryPublic:Buffer.from(await kem.serializePublicKey(delivery.publicKey)).toString('hex')};
    assert.equal((await post('/unlock',body)).status,403);
    const result=await (await post('/unlock',body,logged.token)).json();
    assert.deepEqual(Object.keys(result),['wrappedKey']);
    assert.match(result.wrappedKey,/^[0-9a-f]{160}$/);
    assert.equal((await post('/challenge',{padding:'x'.repeat(13000)})).status,403);
    assert.equal((await fetch(base+'/challenge')).status,404);
  } finally {
    await new Promise(resolve=>service.server.close(resolve));
    service.close();
  }
});
