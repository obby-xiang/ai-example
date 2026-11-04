package com.example.configmgr.perf.redis;

/** TYPE_DEL 的语义判定（Lua 脚本与之同口径；用于单测和文档）。 */
public final class TypeDelPolicy {

	public enum Action {
		DELETE, MISSING, TYPE_MISMATCH
	}

	private TypeDelPolicy() {
	}

	public static Action classify(boolean exists, String expected, String actual) {
		if (!exists || actual == null || "none".equals(actual)) {
			return Action.MISSING;
		}
		if (expected == null || expected.isBlank() || !expected.equals(actual)) {
			return Action.TYPE_MISMATCH;
		}
		return Action.DELETE;
	}

}
