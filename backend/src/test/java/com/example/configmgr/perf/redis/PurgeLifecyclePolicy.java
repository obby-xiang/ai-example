package com.example.configmgr.perf.redis;

/** purge 成功路径的唯一判定：errors/守卫失败只能进入 FAILED_RETRYABLE。 */
public final class PurgeLifecyclePolicy {

	public enum WriteKind {
		COMPLETE, DEFERRED, FAILED_RETRYABLE
	}

	private PurgeLifecyclePolicy() {
	}

	public static WriteKind writeKind(boolean hasErrors, boolean hasDeferred, boolean guardBlocked) {
		if (hasErrors || !guardBlocked) {
			return WriteKind.FAILED_RETRYABLE;
		}
		if (hasDeferred) {
			return WriteKind.DEFERRED;
		}
		return WriteKind.COMPLETE;
	}

}
