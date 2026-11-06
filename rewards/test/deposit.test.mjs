import test from 'node:test';
import assert from 'node:assert/strict';
import ganache from 'ganache';
import { BrowserProvider, ContractFactory, Wallet, getBytes, parseEther } from 'ethers';
import { compile } from '../scripts/compile.mjs';

async function setup() {
  const engine = ganache.provider({ logging:{quiet:true}, chain:{chainId:1337} });
  const provider = new BrowserProvider(engine);
  const owner = await provider.getSigner(0), witness = await provider.getSigner(1);
  const oracle = Wallet.createRandom();
  const artifact = await compile();
  const contract = await new ContractFactory(artifact.abi, artifact.evm.bytecode.object, owner).deploy(oracle.address);
  await contract.waitForDeployment();
  const now = (await provider.getBlock('latest')).timestamp;
  const claim = { owner: await owner.getAddress(), sid: '0x'+'44'.repeat(16), index: 3, shard: 1,
    recipient: await witness.getAddress(), swarmRef: '0x'+'55'.repeat(32), deadline: now+500 };
  const sign = async c => oracle.signMessage(getBytes(await contract.digest(c)));
  return { engine, provider, owner, witness, contract, claim, sign };
}

test('a plain transfer becomes the sender deposit and pays any of their recordings', async () => {
  const { engine, provider, owner, contract, claim, sign } = await setup();
  try {
    await (await owner.sendTransaction({ to: await contract.getAddress(), value: parseEther('0.001') })).wait();
    assert.equal(await contract.deposits(claim.owner), parseEther('0.001'));
    const reward = await contract.DEPOSIT_REWARD();
    const before = BigInt(await provider.send('eth_getBalance', [claim.recipient, 'latest']));
    await (await contract.claim(claim, await sign(claim))).wait();
    const after = BigInt(await provider.send('eth_getBalance', [claim.recipient, 'latest']));
    assert.equal(after - before, reward);
    assert.equal(await contract.deposits(claim.owner), parseEther('0.001') - reward);
    await assert.rejects(contract.claim(claim, await sign(claim)));
    const other = { ...claim, sid: '0x'+'66'.repeat(16) };
    await (await contract.claim(other, await sign(other))).wait();
    assert.equal(await contract.deposits(claim.owner), parseEther('0.001') - 2n*reward);
  } finally { await engine.disconnect(); }
});

test('deposit claims need the verifier signature and enough deposit', async () => {
  const { engine, owner, witness, contract, claim, sign } = await setup();
  try {
    await assert.rejects(contract.claim(claim, await sign(claim)));
    const reward = await contract.DEPOSIT_REWARD();
    await (await owner.sendTransaction({ to: await contract.getAddress(), value: reward })).wait();
    const forged = await Wallet.createRandom().signMessage(getBytes(await contract.digest(claim)));
    await assert.rejects(contract.claim(claim, forged));
    await assert.rejects(contract.connect(witness).withdraw(1n));
    await (await contract.claim(claim, await sign(claim))).wait();
    const next = { ...claim, index: 4 };
    await assert.rejects(contract.claim(next, await sign(next)));
  } finally { await engine.disconnect(); }
});

test('the owner can withdraw unused deposit at any time', async () => {
  const { engine, owner, contract, claim } = await setup();
  try {
    await (await owner.sendTransaction({ to: await contract.getAddress(), value: 5000n })).wait();
    await assert.rejects(contract.withdraw(5001n));
    await (await contract.withdraw(2000n)).wait();
    assert.equal(await contract.deposits(claim.owner), 3000n);
    await (await contract.withdraw(3000n)).wait();
    assert.equal(await contract.deposits(claim.owner), 0n);
    await assert.rejects(owner.sendTransaction({ to: await contract.getAddress(), value: 0n }));
  } finally { await engine.disconnect(); }
});
