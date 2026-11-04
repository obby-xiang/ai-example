package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptsContractTest {

	@Test
	void frontendResultRequiresAnExistingClaim() {
		String script = Scripts.FRONTEND_RESULT.getScriptAsString();
		assertTrue(script.contains("MISSING_CLAIM"), "missing claim must be a distinct rejection");
		assertTrue(script.contains("if not claim"), "script must reject before checking owner");
	}

	@Test
	void failureScriptKeepsPendingAndChecksLeaseOwnerEpoch() {
		String script = Scripts.PURGE_FAIL.getScriptAsString();
		assertTrue(script.contains("FAILED_RETRYABLE"), "failure state must be explicit");
		assertTrue(script.contains("STALE_EPOCH"), "failure write must verify epoch");
		assertTrue(script.contains("WRONG_OWNER"), "failure write must verify owner");
	}

	@Test
	void typeMismatchHasDistinctResult() {
		String script = Scripts.TYPE_DEL.getScriptAsString();
		assertTrue(script.contains("return -1"), "type mismatch must not return a delete count");
	}

}
