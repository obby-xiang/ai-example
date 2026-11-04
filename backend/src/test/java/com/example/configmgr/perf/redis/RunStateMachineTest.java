package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunStateMachineTest {

	@Test
	void terminalSnapshotNeedsNoConvergence() {
		assertEquals(RunStateMachine.ConvergeAction.NONE,
				RunStateMachine.convergence("DONE", true, true, true));
		assertEquals(RunStateMachine.ConvergeAction.NONE,
				RunStateMachine.convergence("CANCELLED", false, true, true));
	}

	@Test
	void runningSnapshotWithTerminalFrameConvergesFromFrame() {
		assertEquals(RunStateMachine.ConvergeAction.CONVERGE_FROM_TERMINAL,
				RunStateMachine.convergence("RUNNING", true, false, false));
		assertEquals(RunStateMachine.ConvergeAction.CONVERGE_FROM_TERMINAL,
				RunStateMachine.convergence("SUSPENDED", true, true, true));
	}

	@Test
	void missingTerminalFrameAppendsOnlyWhenLeaseAndFinalizeAllowed() {
		assertEquals(RunStateMachine.ConvergeAction.APPEND_TERMINAL_AND_CONVERGE,
				RunStateMachine.convergence("RUNNING", false, true, true));
		assertEquals(RunStateMachine.ConvergeAction.DEFER,
				RunStateMachine.convergence("RUNNING", false, false, true));
		assertEquals(RunStateMachine.ConvergeAction.DEFER,
				RunStateMachine.convergence("RUNNING", false, true, false));
	}

	@Test
	void unknownStatusDefers() {
		assertEquals(RunStateMachine.ConvergeAction.DEFER,
				RunStateMachine.convergence("WEIRD", true, true, true));
	}

	@Test
	void claimRejectsOldEpochAndExistingClaim() {
		assertEquals(RunStateMachine.Claim.STALE_EPOCH, RunStateMachine.claim(1, 2, false, "APPROVED"));
		assertEquals(RunStateMachine.Claim.ALREADY_CLAIMED, RunStateMachine.claim(2, 2, true, "APPROVED"));
		assertEquals(RunStateMachine.Claim.WON, RunStateMachine.claim(2, 2, false, "APPROVED"));
		assertEquals(RunStateMachine.Claim.CONFLICT_STATUS, RunStateMachine.claim(2, 2, false, "PENDING"));
	}

	@Test
	void cancelNeverOverwritesClaimedSideEffectOrDecision() {
		assertEquals(RunStateMachine.Cancel.CONFLICT_CLAIMED,
				RunStateMachine.cancel(true, true, null, "APPROVED"));
		assertEquals(RunStateMachine.Cancel.CONFLICT_CLAIMED,
				RunStateMachine.cancel(false, true, null, "APPROVED"));
		assertEquals(RunStateMachine.Cancel.CONFLICT_DECIDED,
				RunStateMachine.cancel(false, false, "APPROVED", "APPROVED"));
		assertEquals(RunStateMachine.Cancel.CANCELLED,
				RunStateMachine.cancel(false, false, null, "PENDING"));
	}

	@Test
	void decisionRespectsClaimAndExistingGuard() {
		assertEquals(RunStateMachine.Decision.APPLIED,
				RunStateMachine.decide("PENDING", null, "APPROVED", false, false));
		assertEquals(RunStateMachine.Decision.IDEMPOTENT,
				RunStateMachine.decide("APPROVED", "APPROVED", "APPROVED", false, false));
		assertEquals(RunStateMachine.Decision.CONFLICT_DECIDED,
				RunStateMachine.decide("APPROVED", "REJECTED", "APPROVED", false, false));
		assertEquals(RunStateMachine.Decision.CONFLICT_CLAIMED,
				RunStateMachine.decide("APPROVED", null, "REJECTED", true, true));
		assertEquals(RunStateMachine.Decision.MISSING,
				RunStateMachine.decide(null, null, "APPROVED", false, false));
	}

	@Test
	void purgeLeaseChecksEpochOwnerAndExpiry() {
		assertEquals(PurgeStateMachine.LeaseCheck.ALLOWED,
				PurgeStateMachine.checkLease(2, "b", 2, "b", 1000, 2000));
		assertEquals(PurgeStateMachine.LeaseCheck.STALE_EPOCH,
				PurgeStateMachine.checkLease(2, "b", 1, "a", 1000, 2000));
		assertEquals(PurgeStateMachine.LeaseCheck.WRONG_OWNER,
				PurgeStateMachine.checkLease(2, "b", 2, "a", 1000, 2000));
		assertEquals(PurgeStateMachine.LeaseCheck.LEASE_EXPIRED,
				PurgeStateMachine.checkLease(2, "b", 2, "b", 3000, 2000));
	}

	@Test
	void takeoverAlwaysAdvancesEpochPastDurablePending() {
		assertEquals(1, PurgeStateMachine.nextEpoch(0, 0));
		assertEquals(3, PurgeStateMachine.nextEpoch(2, 2));
		assertEquals(6, PurgeStateMachine.nextEpoch(2, 5));
	}

}
