package com.example.configmgr.perf.redis;

/** all() 的退出码聚合：任一场景 verify/cleanup 失败或 unknown 都不能被后续成功覆盖。 */
public final class HarnessExit {

	private HarnessExit() {
	}

	public static int combine(int current, int next) {
		if (next == 0) {
			return current;
		}
		if (current == 0) {
			return next;
		}
		if (current == 2 || next == 2) {
			return 2;
		}
		return 1;
	}

}
