// Independent vector for ArkivCodec.createAndTransfer (readonly create + ownership transfer), built
// with viem exactly as Arkiv SDK 1.0.1 entity/operations.ts and entity/key.ts do.
const v = require(process.argv[2]);
const { encodeAbiParameters, parseAbiParameters, parseAbi, encodeFunctionData, encodePacked, keccak256, toHex } = v;
const ATTR = "(bytes32 name, uint8 typeId, bytes value)[]";
const CREATE = parseAbiParameters(`(uint128 salt, uint64 expiresAt, uint64 minLifetime, uint8 creationFlags, ${ATTR} attributes)`);
const TRANSFER = parseAbiParameters("(bytes32 entityKey, address newOwner)");
const EXECUTE = parseAbi(["function execute((uint8 operation, bytes operationData)[] ops) external returns (bytes32[] keys)"]);
const enc = (s) => toHex(new TextEncoder().encode(s));
const saltHex = "0x0102030405060708090a0b0c0d0e0f10";
const attrs = [
  { name: toHex("app", { size: 32 }), typeId: 0x88, value: enc("ghostwitness") },
  { name: toHex("kind", { size: 32 }), typeId: 0x88, value: enc("upload") },
  { name: toHex("i", { size: 32 }), typeId: 0x83, value: toHex(7n, { size: 32 }) },
  { name: toHex("data", { size: 32 }), typeId: 7, value: "0xdeadbeef" },
].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
const create = encodeAbiParameters(CREATE, [{ salt: BigInt(saltHex), expiresAt: 0n, minLifetime: 1296000n, creationFlags: 1, attributes: attrs }]);
const writer = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23";
const owner = "0x00000000000000000000000000000000000000aa";
const key = keccak256(encodePacked(["uint256", "address", "address", "uint64", "uint128"], [7794095n, "0x4400000000000000000000000000000000000044", writer, 5n, BigInt(saltHex)]));
const transfer = encodeAbiParameters(TRANSFER, [{ entityKey: key, newOwner: owner }]);
const exec = encodeFunctionData({ abi: EXECUTE, functionName: "execute", args: [[{ operation: 1, operationData: create }, { operation: 4, operationData: transfer }]] });
console.log(JSON.stringify({ key, exec }, null, 1));
