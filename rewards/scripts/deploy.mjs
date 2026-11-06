import { ContractFactory, JsonRpcProvider, Wallet } from 'ethers';
import { compile } from './compile.mjs';
const provider = new JsonRpcProvider(process.env.REWARD_RPC ?? 'https://sepolia.base.org');
if ((await provider.getNetwork()).chainId !== BigInt(process.env.REWARD_CHAIN_ID ?? '84532')) throw Error('Wrong reward chain');
if (!process.env.DEPLOYER_KEY || !process.env.VERIFIER_ADDRESS) throw Error('Set DEPLOYER_KEY and VERIFIER_ADDRESS');
const artifact = await compile();
const contract = await new ContractFactory(artifact.abi, artifact.evm.bytecode.object, new Wallet(process.env.DEPLOYER_KEY, provider)).deploy(process.env.VERIFIER_ADDRESS);
await contract.waitForDeployment();
console.log('UploadRewards:', await contract.getAddress());
