package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除完成态的单一判据：只有“完成写成功 + 无 errors + 无 deferred”才是 COMPLETE。
 * 失败写、zadd/状态落盘失败、defer 写失败都不能被报成 purged。
 */
public final class PurgeCompletionPolicy {

	public enum State {
		COMPLETE, DEFERRED, FAILED
	}

	public record Decision(boolean complete, State state, List<String> errors) {
	}

	private PurgeCompletionPolicy() {
	}

	public static Decision decide(boolean completeWrite, boolean deferWrite, List<String> errors,
			List<String> deferred) {
		List<String> effective = new ArrayList<>(errors == null ? List.of() : errors);
		if (deferred != null && !deferred.isEmpty()) {
			if (!deferWrite) {
				effective.add("defer write rejected while runs are deferred: " + deferred);
				return new Decision(false, State.FAILED, List.copyOf(effective));
			}
			if (!effective.isEmpty()) {
				return new Decision(false, State.FAILED, List.copyOf(effective));
			}
			return new Decision(false, State.DEFERRED, List.copyOf(effective));
		}
		if (!effective.isEmpty()) {
			return new Decision(false, State.FAILED, List.copyOf(effective));
		}
		if (!completeWrite) {
			effective.add("complete write rejected (lease lost or pending mismatch)");
			return new Decision(false, State.FAILED, List.copyOf(effective));
		}
		return new Decision(true, State.COMPLETE, List.copyOf(effective));
	}

}
