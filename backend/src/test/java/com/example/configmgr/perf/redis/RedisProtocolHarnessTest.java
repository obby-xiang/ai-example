package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实 Redis 的单点入口：默认跳过，只有显式 {@code REDIS_PROTOCOL_EXECUTE=true} 才连接。
 *
 * <p>运行示例（在 backend 目录）：
 * <pre>
 * REDIS_PROTOCOL_EXECUTE=true REDIS_PROTOCOL_COMMAND=doctor \
 *   mvn -Dtest=RedisProtocolHarnessTest test
 * </pre>
 * 若仓库基线存在与本任务无关的测试源码编译错误，使用
 * {@code scripts/perf/redis-protocol/run.sh}（它只编译本包，不跑全仓测试）。
 */
class RedisProtocolHarnessTest {

	@Test
	void runsHarnessOnlyWhenExplicitlyEnabled() {
		Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("REDIS_PROTOCOL_EXECUTE",
				"false")), "set REDIS_PROTOCOL_EXECUTE=true and safety gates to connect to Redis");
		String command = System.getenv().getOrDefault("REDIS_PROTOCOL_COMMAND", "doctor");
		String[] args = command.trim().split("\\s+");
		int exit = HarnessMain.run(args);
		assertEquals(0, exit, "harness exited " + exit + " for command: " + command);
	}

}
