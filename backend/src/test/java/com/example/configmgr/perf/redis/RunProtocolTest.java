package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执/取消的 claim 比较口径：认领表里的值是完整 {@code <epoch>|<instanceId>}，
 * 不是裸实例标识。
 */
class RunProtocolTest {

	@Test
	void claimInstanceIsOnlyTheOwnerView() {
		assertEquals("executor-a", RunProtocol.claimInstance("7|executor-a"));
		assertEquals("executor-a", RunProtocol.claimInstance("executor-a"));
		assertEquals("run|executor-a", RunProtocol.claimInstance("run|executor-a"));
		assertNull(RunProtocol.claimInstance(null));
	}

	@Test
	void receiptValueMustStayTheFullStoredClaim() {
		String stored = "7|executor-a";
		assertNotEquals(stored, RunProtocol.claimInstance(stored),
				"剥出的实例标识不是回执比较值：只传它会拒绝合法 owner");
	}

	@Test
	void nonOwnerValueKeepsTheEpochPrefix() {
		assertEquals("7|executor-b", RunProtocol.withClaimInstance("7|executor-a", "executor-b"));
		assertEquals("executor-b", RunProtocol.withClaimInstance("executor-a", "executor-b"));
		assertEquals("executor-b", RunProtocol.withClaimInstance(null, "executor-b"));
	}

	@Test
	void failureDetailKeepsTheWholeCausedByChainAndStackTrace() {
		Throwable failure = new IllegalStateException("Error in execution",
				new java.io.IOException("command failed",
						new IllegalArgumentException("ERR invalid stream ID specified as stream command argument")));

		String detail = RunProtocol.diagnostic(failure);

		assertTrue(detail.contains("java.lang.IllegalStateException: Error in execution"));
		assertTrue(detail.contains("rootCause=java.lang.IllegalArgumentException: "
				+ "ERR invalid stream ID specified as stream command argument"));
		assertTrue(detail.contains("Caused by: java.io.IOException: command failed"));
		assertTrue(detail.contains("Caused by: java.lang.IllegalArgumentException: "
				+ "ERR invalid stream ID specified as stream command argument"));
		assertTrue(detail.contains("RunProtocolTest"), "完整堆栈必须落到诊断里，远端才能定位调用点");
	}

}
