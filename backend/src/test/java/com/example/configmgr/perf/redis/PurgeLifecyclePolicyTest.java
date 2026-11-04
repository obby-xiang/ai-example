package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurgeLifecyclePolicyTest {

	@Test
	void errorsForceFailedRetryableAndNeverComplete() {
		assertEquals(PurgeLifecyclePolicy.WriteKind.FAILED_RETRYABLE,
				PurgeLifecyclePolicy.writeKind(true, false, true));
	}

	@Test
	void guardFailureIsAlsoFailedRetryable() {
		assertEquals(PurgeLifecyclePolicy.WriteKind.FAILED_RETRYABLE,
				PurgeLifecyclePolicy.writeKind(false, false, false));
	}

	@Test
	void deferredStaysDeferred() {
		assertEquals(PurgeLifecyclePolicy.WriteKind.DEFERRED,
				PurgeLifecyclePolicy.writeKind(false, true, true));
	}

	@Test
	void cleanRunIsTheOnlyCompletePath() {
		assertEquals(PurgeLifecyclePolicy.WriteKind.COMPLETE,
				PurgeLifecyclePolicy.writeKind(false, false, true));
	}

}
