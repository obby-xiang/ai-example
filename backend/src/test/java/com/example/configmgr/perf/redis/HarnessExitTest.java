package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarnessExitTest {

	@Test
	void laterSuccessDoesNotHideEarlierVerifyOrCleanupFailure() {
		assertEquals(0, HarnessExit.combine(0, 0));
		assertEquals(1, HarnessExit.combine(0, 1));
		assertEquals(1, HarnessExit.combine(1, 0));
		assertEquals(2, HarnessExit.combine(1, 2));
		assertEquals(2, HarnessExit.combine(2, 0));
	}

}
