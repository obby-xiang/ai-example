package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@Test
	void acquireAdvancesStraightToBeginWithoutASecondRoundTrip() {
		String script = Scripts.PURGE_ACQUIRE.getScriptAsString();
		assertTrue(script.contains("stage = 'BEGIN'"), "fresh lease should already be in BEGIN");
	}

	@Test
	void stageCheckRenewsTheStoredExpiryInsteadOfOnlyPexpire() {
		String script = Scripts.PURGE_STAGE.getScriptAsString();
		assertTrue(script.contains("local renewed"), "stage check must compute a fresh expiry");
		assertTrue(script.contains("redis.call('SET', KEYS[1]"), "stage check must rewrite the lease value");
		assertFalse(script.contains("redis.call('PEXPIRE'"), "PEXPIRE alone leaves the old exp in the lease value");
	}

	@Test
	void heartbeatChecksOwnerEpochAndRewritesTheStoredExpiry() {
		String script = Scripts.PURGE_RENEW.getScriptAsString();
		assertTrue(script.contains("LEASE_EXPIRED"), "heartbeat must reject an expired lease");
		assertTrue(script.contains("STALE_EPOCH"), "heartbeat must verify epoch");
		assertTrue(script.contains("WRONG_OWNER"), "heartbeat must verify owner");
		assertTrue(script.contains("obj['owner']"), "heartbeat must verify the pending owner");
		assertTrue(script.contains("obj['purgeId']"), "heartbeat must verify the pending purgeId");
		assertTrue(script.contains("redis.call('SET', KEYS[1]"), "heartbeat must rewrite the lease value");
		assertTrue(script.contains("'PX'"), "heartbeat must keep a bounded lease TTL");
	}

	@Test
	void sessionGuardScriptMergesLeaseCheckDeleteAndGuardProbe() {
		String script = Scripts.PURGE_SESSION_GUARD.getScriptAsString();
		assertTrue(script.contains("LEASE_EXPIRED"), "session delete must reject an expired lease");
		assertTrue(script.contains("STALE_EPOCH"), "session delete must verify epoch");
		assertTrue(script.contains("WRONG_OWNER"), "session delete must verify owner");
		assertTrue(script.contains("redis.call('DEL', KEYS[i])"), "same-slot session keys must be deleted");
		assertTrue(script.contains("redis.call('EXISTS', KEYS[3])"), "guard presence must block write-back");
		assertFalse(script.contains("should-not-write"), "guard value comes from ARGV, not a hardcode");
	}

	@Test
	void completionScriptKeepsLeaseChecksAndWritesTombstoneAtomically() {
		String script = Scripts.PURGE_COMPLETE.getScriptAsString();
		assertTrue(script.contains("STALE_EPOCH"), "completion must verify epoch");
		assertTrue(script.contains("WRONG_OWNER"), "completion must verify owner");
		assertTrue(script.contains("LEASE_EXPIRED"), "completion must reject an expired lease");
		assertTrue(script.contains("INCR"), "tombstone generation must only grow");
		assertTrue(script.contains("PEXPIRE"), "tombstone must keep a TTL");
		assertTrue(script.contains("COMPLETE"), "success must stay distinguishable");
	}

}
