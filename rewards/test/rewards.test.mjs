import test from 'node:test';
import assert from 'node:assert/strict';
import ganache from 'ganache';
import { BrowserProvider, ContractFactory, Wallet, getBytes, SigningKey, sha256 } from 'ethers';
import { compile } from '../scripts/compile.mjs';
import { hash, verifyRecord, verifyShard, verifyTicket } from '../proof.mjs';

test('funded contract pays only assigned authorization once and refunds expired balance', async () => {
  const engine = ganache.provider({ logging:{quiet:true}, chain:{chainId:1337} });
  const provider = new BrowserProvider(engine);
  try {
    const funder = await provider.getSigner(0), recipient = await provider.getSigner(1);
    const oracle = Wallet.createRandom();
    const artifact = await compile();
    const contract = await new ContractFactory(artifact.abi,artifact.evm.bytecode.object,funder).deploy(oracle.address);
    await contract.waitForDeployment();
    const sid='0x'+'11'.repeat(16), key='0x'+'22'.repeat(32);
    const now=(await provider.getBlock('latest')).timestamp;
    await (await contract.fund(sid,key,1000,now+1000,100,{value:3000})).wait();
    const c={owner:await funder.getAddress(),sid,index:7,shard:2,recipient:await recipient.getAddress(),swarmRef:'0x'+'33'.repeat(32),deadline:now+500};
    const sig=await oracle.signMessage(getBytes(await contract.digest(c)));
    const before = await provider.send('eth_getBalance',[c.recipient,'latest']);
    await (await contract.claim(c,sig)).wait();
    const after = await provider.send('eth_getBalance',[c.recipient,'latest']);
    assert.equal(BigInt(after)-BigInt(before),1000n);
    assert.equal(await contract.paid(await contract.claimId(c.owner,sid,7,2)),true);
    assert.equal(await contract.paidTo(await contract.claimId(c.owner,sid,7,2)),c.recipient);
    await assert.rejects(contract.claim(c,sig));
    await assert.rejects(contract.claim({...c,index:8},sig));
    const other=Wallet.createRandom();
    await assert.rejects(contract.claim({...c,index:8},await other.signMessage(getBytes(await contract.digest({...c,index:8})))));
    await assert.rejects(contract.connect(recipient).refund(sid));
    await engine.request({method:'evm_increaseTime',params:[1001]});
    await engine.request({method:'evm_mine',params:[]});
    await (await contract.refund(sid,{gasLimit:100000})).wait();
    assert.equal((await contract.pools(await contract.poolId(c.owner,sid))).balance,0n);
  } finally { await engine.disconnect(); }
});

test('verifier rejects substituted metadata, shards, signing keys and payout assignments', () => {
  const signer=new SigningKey('0x'+'01'.repeat(32));
  const sid=Buffer.alloc(16,1), owner=Buffer.alloc(20,2), uploader=Buffer.alloc(20,3);
  const shards=Array.from({length:5},(_,j)=>Buffer.alloc(4,j));
  const roots=shards.map(b=>hash(Buffer.from([0]),b));
  const entry=Buffer.alloc(211);entry.writeUInt16BE(2000,12);entry.writeUInt32BE(12,14);entry[50]=5;roots.forEach((r,j)=>r.copy(entry,51+32*j));
  const header=Buffer.alloc(185);header.write('GWS');header[3]=1;sid.copy(header,4);header[28]=5;header[29]=3;Buffer.from(signer.publicKey.slice(2),'hex').copy(header,38);header.writeUInt16BE(80,103);
  const signed=Buffer.alloc(120);signed.write('GWR');signed[3]=1;sid.copy(signed,4);hash(Buffer.from([0]),entry).copy(signed,24);
  const signature=signer.sign(hash(Buffer.from('GWROOT'),sid,signed.subarray(20,56)));
  Buffer.from(signature.r.slice(2)+signature.s.slice(2),'hex').copy(signed,56);
  const length=n=>{const b=Buffer.alloc(2);b.writeUInt16BE(n);return b;};
  const bytes=Buffer.concat([Buffer.from('47575001','hex'),length(header.length),header,owner,length(entry.length),entry,signed,Buffer.from([0])]);
  const hex='0x'+bytes.toString('hex');
  const record=verifyRecord(hex,sha256(getBytes(signer.publicKey)));
  verifyShard(record,3,shards[3]);
  assert.throws(()=>verifyShard(record,3,shards[4]));
  assert.throws(()=>verifyRecord(hex,'0x'+'00'.repeat(32)));
  const ticket=Buffer.alloc(141);ticket.write('GWT');ticket[3]=1;sid.copy(ticket,4);ticket[24]=3;uploader.copy(ticket,25);hash(shards[3]).copy(ticket,45);
  const auth=signer.sign(hash(Buffer.from('GWASSIGN'),ticket.subarray(4,77)));
  Buffer.from(auth.r.slice(2)+auth.s.slice(2),'hex').copy(ticket,77);
  verifyTicket('0x'+ticket.toString('hex'),record,signer.publicKey,'0x'+uploader.toString('hex'),3,shards[3]);
  assert.throws(()=>verifyTicket('0x'+ticket.toString('hex'),record,signer.publicKey,'0x'+owner.toString('hex'),3,shards[3]));
  const damaged=Buffer.from(bytes);damaged[250]^=1;
  assert.throws(()=>verifyRecord('0x'+damaged.toString('hex'),sha256(getBytes(signer.publicKey))));
});
