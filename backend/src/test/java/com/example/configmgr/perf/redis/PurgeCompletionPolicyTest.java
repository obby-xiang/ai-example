package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurgeCompletionPolicyTest {

	@Test
	void completeRequiresSuccessfulCompleteWriteAndNoErrorsOrDeferred() {
		PurgeCompletionPolicy.Decision decision = PurgeCompletionPolicy.decide(true, false,
				List.of(), List.of());
		assertTrue(decision.complete());
		assertEquals(PurgeCompletionPolicy.State.COMPLETE, decision.state());
	}

	@Test
	void errorsForceFailedEvenWhenCompleteWriteSucceeded() {
		PurgeCompletionPolicy.Decision decision = PurgeCompletionPolicy.decide(true, false,
				List.of("due zadd failed"), List.of());
		assertFalse(decision.complete());
		assertEquals(PurgeCompletionPolicy.State.FAILED, decision.state());
	}

	@Test
	void failedCompleteWriteCannotBeReportedAsPurged() {
		PurgeCompletionPolicy.Decision decision = PurgeCompletionPolicy.decide(false, false,
				List.of(), List.of());
		assertFalse(decision.complete());
		assertEquals(PurgeCompletionPolicy.State.FAILED, decision.state());
		assertTrue(decision.errors().stream().anyMatch(s -> s.contains("complete")));
	}

	@Test
	void errorsWithDeferredAreFailedNotDeferred() {
		PurgeCompletionPolicy.Decision decision = PurgeCompletionPolicy.decide(false, true,
				List.of("archive type mismatch"), List.of("run-active"));
		assertFalse(decision.complete());
		assertEquals(PurgeCompletionPolicy.State.FAILED, decision.state());
	}

	@Test
	void deferredRequiresSuccessfulDeferWrite() {
		PurgeCompletionPolicy.Decision ok = PurgeCompletionPolicy.decide(false, true,
				List.of(), List.of("run-active"));
		assertFalse(ok.complete());
		assertEquals(PurgeCompletionPolicy.State.DEFERRED, ok.state());

		PurgeCompletionPolicy.Decision failed = PurgeCompletionPolicy.decide(false, false,
				List.of(), List.of("run-active"));
		assertFalse(failed.complete());
		assertEquals(PurgeCompletionPolicy.State.FAILED, failed.state());
		assertTrue(failed.errors().stream().anyMatch(s -> s.contains("defer")));
	}

}
