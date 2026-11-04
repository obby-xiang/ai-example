package com.example.configmgr.perf.redis;

/**
 * purge 租约/epoch 判定。旧 owner 即使仍在运行，只要租约 epoch 已被推进就必须被拒。
 */
public final class PurgeStateMachine {

	public enum LeaseCheck {
		ALLOWED, STALE_EPOCH, WRONG_OWNER, LEASE_EXPIRED
	}

	private PurgeStateMachine() {
	}

	public static LeaseCheck checkLease(long leaseEpoch, String leaseOwner, long ownerEpoch, String owner,
			long nowMs, long expiresAtMs) {
		if (nowMs >= expiresAtMs) {
			return LeaseCheck.LEASE_EXPIRED;
		}
		if (leaseEpoch != ownerEpoch) {
			return LeaseCheck.STALE_EPOCH;
		}
		if (leaseOwner == null || !leaseOwner.equals(owner)) {
			return LeaseCheck.WRONG_OWNER;
		}
		return LeaseCheck.ALLOWED;
	}

	/** 接管后 epoch 必须超过持久 pending 里记录的 epoch（旧 owner 不能"复用"号）。 */
	public static long nextEpoch(long pendingEpoch, long seenLeaseEpoch) {
		return Math.max(pendingEpoch, seenLeaseEpoch) + 1L;
	}

}
