// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// Funded per recording (fund) or per owner (send ETH to the contract). A trusted verifier attests
/// retrievable, authenticated Swarm shards. A recording without its own pool is paid from its owner's
/// deposit. First successful claim for a (session, segment, shard) wins; reuploads never earn twice.
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
    uint256 public constant DEPOSIT_REWARD = 0.00002 ether;
    uint32 public constant DEPOSIT_MAX_SEGMENTS = 100000;
    address public immutable verifier;
    mapping(address => uint256) public deposits;
    mapping(bytes32 => Pool) public pools;
    mapping(bytes32 => bool) public paid;
    mapping(bytes32 => address) public paidTo;
    event Funded(bytes16 indexed sid, address indexed owner, bytes32 signingKeyHash);
    event Deposited(address indexed owner, uint256 amount);
    event Withdrawn(address indexed owner, uint256 amount);
    event RewardPaid(bytes16 indexed sid, uint32 index, uint8 shard, address indexed recipient, bytes32 swarmRef, uint256 amount);

    constructor(address verifier_) {
        require(verifier_ != address(0), "verifier required");
        verifier = verifier_;
    }

    /// Plain ETH sent here is the sender's deposit, used for every recording they own that has no pool.
    receive() external payable {
        require(msg.value > 0, "empty deposit");
        deposits[msg.sender] += msg.value;
        emit Deposited(msg.sender, msg.value);
    }

    function withdraw(uint256 amount) external {
        require(amount > 0 && deposits[msg.sender] >= amount, "insufficient deposit");
        deposits[msg.sender] -= amount;
        (bool ok,) = payable(msg.sender).call{value: amount}("");
        require(ok, "withdraw failed");
        emit Withdrawn(msg.sender, amount);
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
        require(c.recipient != address(0) && c.swarmRef != bytes32(0) && c.shard < 5, "invalid contribution");
        require(block.timestamp <= c.deadline, "expired");
        bytes32 id = claimId(c.owner, c.sid, c.index, c.shard);
        require(!paid[id], "already paid");
        requireVerifier(c, signature);
        paid[id] = true;
        paidTo[id] = c.recipient;
        uint256 amount = debit(c);
        (bool ok,) = payable(c.recipient).call{value: amount}("");
        require(ok, "payment failed");
        emit RewardPaid(c.sid, c.index, c.shard, c.recipient, c.swarmRef, amount);
    }

    function debit(Claim calldata c) private returns (uint256) {
        Pool storage pool = pools[poolId(c.owner, c.sid)];
        if (pool.owner == address(0)) {
            require(c.index < DEPOSIT_MAX_SEGMENTS && deposits[c.owner] >= DEPOSIT_REWARD, "unfunded");
            deposits[c.owner] -= DEPOSIT_REWARD;
            return DEPOSIT_REWARD;
        }
        require(c.index < pool.maxSegments && block.timestamp <= pool.expires, "outside pool limits");
        require(pool.reward > 0 && pool.balance >= pool.reward, "unfunded");
        pool.balance -= pool.reward;
        return pool.reward;
    }

    function requireVerifier(Claim calldata c, bytes calldata signature) private view {
        require(signature.length == 65, "invalid signature");
        bytes32 r; bytes32 s; uint8 v;
        assembly { r := calldataload(signature.offset) s := calldataload(add(signature.offset, 32)) v := byte(0, calldataload(add(signature.offset, 64))) }
        require(uint256(s) <= 0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0 && (v == 27 || v == 28), "noncanonical signature");
        bytes32 message = keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", digest(c)));
        require(ecrecover(message, v, r, s) == verifier, "unauthorized");
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
