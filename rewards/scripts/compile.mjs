import solc from 'solc';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
export async function compile() {
  const source = await readFile(new URL('../contracts/UploadRewards.sol', import.meta.url), 'utf8');
  const output = JSON.parse(solc.compile(JSON.stringify({ language: 'Solidity', sources: { 'UploadRewards.sol': { content: source } }, settings: {
    optimizer: { enabled: true, runs: 200 }, evmVersion: 'paris', outputSelection: { '*': { '*': ['abi', 'evm.bytecode.object'] } }
  } })));
  const errors = (output.errors ?? []).filter(e => e.severity === 'error');
  if (errors.length) throw Error(errors.map(e => e.formattedMessage).join('\n'));
  return output.contracts['UploadRewards.sol'].UploadRewards;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const artifact = await compile();
  await mkdir(new URL('../build/', import.meta.url), { recursive: true });
  await writeFile(new URL('../build/UploadRewards.json', import.meta.url), JSON.stringify(artifact, null, 2));
  console.log('Compiled UploadRewards');
}
