// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// Funded per recording. A trusted verifier attests retrievable, authenticated Swarm shards.
/// First successful claim for a (session, segment, shard) wins; reuploads never earn twice.
contract UploadRewards {
    struct Pool {
        address owner;
        bytes32 signingKeyHash;
        uint256 reward;
        uint256 balance;
        uint64 expires;
        uint32 maxSegments;
    }
    struct Claim {
        address owner;
        bytes16 sid;
        uint32 index;
        uint8 shard;
        address recipient;
        bytes32 swarmRef;
        uint64 deadline;
    }
    address public immutable verifier;
    mapping(bytes32 => Pool) public pools;
    mapping(bytes32 => bool) public paid;
    mapping(bytes32 => address) public paidTo;
    event Funded(bytes16 indexed sid, address indexed owner, bytes32 signingKeyHash);
    event RewardPaid(bytes16 indexed sid, uint32 index, uint8 shard, address indexed recipient, bytes32 swarmRef, uint256 amount);

    constructor(address verifier_) {
        require(verifier_ != address(0), "verifier required");
        verifier = verifier_;
    }

    function fund(bytes16 sid, bytes32 signingKeyHash, uint256 reward, uint64 expires, uint32 maxSegments) external payable {
        bytes32 id = poolId(msg.sender, sid);
        require(pools[id].owner == address(0), "session already funded");
        require(reward > 0 && msg.value >= reward && signingKeyHash != bytes32(0), "invalid funding");
        require(expires > block.timestamp && maxSegments > 0, "invalid limits");
        pools[id] = Pool(msg.sender, signingKeyHash, reward, msg.value, expires, maxSegments);
        emit Funded(sid, msg.sender, signingKeyHash);
    }

    function poolId(address owner, bytes16 sid) public pure returns (bytes32) {
        return keccak256(abi.encode(owner, sid));
    }

    function claimId(address owner, bytes16 sid, uint32 index, uint8 shard) public pure returns (bytes32) {
        return keccak256(abi.encode(owner, sid, index, shard));
    }

    function digest(Claim calldata c) public view returns (bytes32) {
        return keccak256(abi.encode(address(this), block.chainid, c.owner, c.sid, c.index, c.shard, c.recipient, c.swarmRef, c.deadline));
    }

    function claim(Claim calldata c, bytes calldata signature) external {
        Pool storage pool = pools[poolId(c.owner, c.sid)];
        require(c.recipient != address(0) && c.swarmRef != bytes32(0), "invalid contribution");
        require(c.index < pool.maxSegments && c.shard < 5, "invalid shard");
        require(block.timestamp <= c.deadline && block.timestamp <= pool.expires, "expired");
        bytes32 id = claimId(c.owner, c.sid, c.index, c.shard);
        require(!paid[id] && pool.balance >= pool.reward && pool.reward > 0, "paid or unfunded");
        require(signature.length == 65, "invalid signature");
        bytes32 r; bytes32 s; uint8 v;
        assembly { r := calldataload(signature.offset) s := calldataload(add(signature.offset, 32)) v := byte(0, calldataload(add(signature.offset, 64))) }
        require(uint256(s) <= 0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0 && (v == 27 || v == 28), "noncanonical signature");
        bytes32 message = keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", digest(c)));
        require(ecrecover(message, v, r, s) == verifier, "unauthorized");
        paid[id] = true;
        paidTo[id] = c.recipient;
        pool.balance -= pool.reward;
        (bool ok,) = payable(c.recipient).call{value: pool.reward}("");
        require(ok, "payment failed");
        emit RewardPaid(c.sid, c.index, c.shard, c.recipient, c.swarmRef, pool.reward);
    }

    function refund(bytes16 sid) external {
        Pool storage pool = pools[poolId(msg.sender, sid)];
        require(msg.sender == pool.owner && block.timestamp > pool.expires, "not refundable");
        uint256 amount = pool.balance;
        pool.balance = 0;
        (bool ok,) = payable(msg.sender).call{value: amount}("");
        require(ok, "refund failed");
    }
}
