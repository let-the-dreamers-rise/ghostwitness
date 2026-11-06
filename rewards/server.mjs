import { createServer } from 'node:http';
import { Contract, JsonRpcProvider, Wallet, getBytes } from 'ethers';
import { compile } from './scripts/compile.mjs';
import { hash, verifyRecord, verifyShard, verifyTicket } from './proof.mjs';
import { verifyOwnership } from './ownership.mjs';

// Bind behind an HTTPS reverse proxy. No claimant supplies a URL, recipient, amount, or signed tx.
const env = process.env;
for (const key of ['VERIFIER_KEY','REWARD_CONTRACT','ARKIV_RPC','BEE_URL']) if (!env[key]) throw Error(`Set ${key}`);
for (const key of ['ARKIV_RPC','BEE_URL']) if (new URL(env[key]).protocol !== 'https:') throw Error('HTTPS endpoints required');
const provider = new JsonRpcProvider(env.REWARD_RPC ?? 'https://sepolia.base.org');
if ((await provider.getNetwork()).chainId !== BigInt(env.REWARD_CHAIN_ID ?? '84532')) throw Error('Wrong reward chain');
const wallet = new Wallet(env.VERIFIER_KEY, provider);
const contract = new Contract(env.REWARD_CONTRACT, (await compile()).abi, wallet);
if ((await contract.verifier()).toLowerCase() !== wallet.address.toLowerCase()) throw Error('Wrong verifier key');
let rpcId=0;
async function json(url, body) {
  const response = await fetch(url, {method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,headers:{'content-type':'application/json'},signal:AbortSignal.timeout(30000),redirect:'error'});
  if (!response.ok) throw Error(`Remote HTTP ${response.status}`);
  const text = await response.text(); if (text.length > 8*1024*1024) throw Error('Response too large');
  return JSON.parse(text);
}
async function rpc(method, params=[]) {
  const body = await json(env.ARKIV_RPC, {jsonrpc:'2.0',id:++rpcId,method,params});
  if (body.error) throw Error('Arkiv RPC failed');
  return body.result;
}
const attrs = entity => Object.fromEntries(entity.attributes.map(a => [a.name,a.value]));
// A recording's own pool wins. Otherwise the owner's deposit pays, once the owner's wallet-signed session
// proof on Arkiv names the key that signs this recording (so nobody can bill an owner for a fake session).
async function terms(owner, sid) {
  const pool = await contract.pools(await contract.poolId(owner,sid));
  if (pool.owner.toLowerCase() === owner.toLowerCase() && pool.reward > 0n) return pool;
  const [reward, balance, maxSegments] = await Promise.all([contract.DEPOSIT_REWARD(), contract.deposits(owner), contract.DEPOSIT_MAX_SEGMENTS()]);
  if (balance < reward) throw Error('No owner pool or deposit');
  const query = `app = str('ghostwitness') AND kind = str('session') AND sid = str('${sid.slice(2)}') AND $owner = addr(${owner})`;
  const page = await rpc('arkiv_query',[query,{select:{key:true,attributes:true},limit:'0x10'}]);
  for (const session of page.data ?? []) {
    try {
      const proof = verifyOwnership(attrs(session).data, owner);
      if (proof.sid !== sid) continue;
      return { owner, signingKeyHash: proof.signingKeyHash, reward, balance, expires: BigInt(Number.MAX_SAFE_INTEGER), maxSegments };
    } catch { /* not a valid proof for this owner; try the next entity */ }
  }
  throw Error('No wallet-signed session proof');
}
async function verify(entityKey) {
  if (!/^0x[0-9a-f]{64}$/i.test(entityKey)) throw Error('Entity key required');
  const entity = await rpc('arkiv_getEntity',[entityKey]);
  if (!entity) throw Error('Upload entity missing');
  const a = attrs(entity);
  if (a.app !== 'ghostwitness' || a.kind !== 'upload' || !/^[0-9a-f]{32}$/.test(a.sid) || !/^[0-9a-f]{64}$/.test(a.ref)) throw Error('Invalid upload entity');
  if (a.uploader.toLowerCase() !== entity.creator.toLowerCase()) throw Error('Creator mismatch');
  const height = BigInt(await rpc('eth_blockNumber'));
  if (height - BigInt(entity.createdAt) < 3n) throw Error('Awaiting Arkiv confirmations');
  const sid = '0x'+a.sid;
  const pool = await terms(entity.owner, sid);
  const index=Number(BigInt(a.i)), j=Number(BigInt(a.j));
  if (!Number.isSafeInteger(index) || index < 0 || index >= Number(pool.maxSegments) || !Number.isInteger(j) || j < 0 || j > 4) throw Error('Invalid contribution index');
  const id = await contract.claimId(entity.owner,sid,index,j);
  if (await contract.paid(id)) {
    const recipient=await contract.paidTo(id);
    return {state:recipient.toLowerCase()===entity.creator.toLowerCase()?'ALREADY_PAID':'PAID_ELSEWHERE',recipient};
  }
  if (pool.balance < pool.reward) throw Error('Pool funding exhausted');
  if (!/^[0-9a-f]{64}$/.test(a.recordHash)) throw Error('Invalid metadata reference');
  const query = `app = str('ghostwitness') AND kind = str('segment') AND sid = str('${a.sid}') AND recordHash = str('${a.recordHash}') AND $owner = addr(${entity.owner})`;
  const page = await rpc('arkiv_query',[query,{select:{key:true,attributes:true},limit:'0x64'}]);
  if (!page.data.length) throw Error('Signed metadata missing');
  const data = attrs(page.data[0]).data;
  const record = verifyRecord(data, pool.signingKeyHash);
  if (record.sid !== sid || record.owner.toLowerCase() !== pool.owner.toLowerCase() || record.index !== Number(BigInt(a.i)) || record.index >= Number(pool.maxSegments) || hash(record.bytes).toString('hex') !== a.recordHash) throw Error('Metadata mismatch');
  const response = await fetch(env.BEE_URL.replace(/\/$/,'')+'/bytes/'+a.ref,{signal:AbortSignal.timeout(45000),redirect:'error'});
  if (!response.ok) throw Error('Swarm bytes unavailable');
  const chunks=[]; let size=0;
  for await (const chunk of response.body) { size += chunk.length; if (size > 8*1024*1024) throw Error('Shard too large'); chunks.push(chunk); }
  const bytes = Buffer.concat(chunks);
  verifyShard(record,j,bytes);
  if (hash(bytes).toString('hex') !== a.sha256) throw Error('Shard hash mismatch');
  const publicKey='0x'+record.bytes.subarray(6+38,6+103).toString('hex');
  verifyTicket(a.ticket, record, publicKey, entity.creator, j, bytes);
  const block = await provider.getBlock('latest');
  const deadline = Math.min(Number(pool.expires),block.timestamp+600);
  if (deadline <= block.timestamp) throw Error('Pool expired');
  const claim = {owner:entity.owner,sid,index:record.index,shard:j,recipient:entity.creator,swarmRef:'0x'+a.ref,deadline};
  const signature=await wallet.signMessage(getBytes(await contract.digest(claim)));
  // Serialized requests prevent verifier nonce races. Anyone can relay this authorization; payment is bound to recipient.
  const tx=await contract.claim(claim,signature);
  const receipt=await tx.wait(2);
  if (receipt.status !== 1) throw Error('Reward transaction failed');
  return {state:'PAID',transaction:tx.hash,recipient:entity.creator};
}
let serial = Promise.resolve();
let queued = 0;
createServer(async (request,response) => {
  response.setHeader('content-type','application/json');
  try {
    if(request.method !== 'POST' || request.url !== '/claim') {response.writeHead(404);response.end('{}');return;}
    if(queued>=32) {response.writeHead(429);response.end('{"state":"PENDING"}');return;}
    let text=''; for await (const chunk of request) {text+=chunk;if(text.length>1024) throw Error('Request too large');}
    const {entityKey}=JSON.parse(text);
    queued++;
    const run = serial.then(()=>verify(entityKey)); serial=run.catch(()=>{}).finally(()=>{queued--;});
    const result=await run;
    response.end(JSON.stringify(result));
  } catch {response.writeHead(409);response.end(JSON.stringify({state:'PENDING',message:'Contribution verification or funding pending'}));}
}).listen(Number(env.PORT ?? 8787),'127.0.0.1',()=>console.log('Reward verifier listening on loopback; publish via HTTPS reverse proxy'));
