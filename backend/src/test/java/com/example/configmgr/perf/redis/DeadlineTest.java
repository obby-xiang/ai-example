package com.example.configmgr.perf.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineTest {

	@Test
	void deadlineExpiresAtMonotonicBudget() {
		AtomicLong clock = new AtomicLong(1_000_000L);
		Deadline d = new Deadline(Duration.ofMillis(100).toNanos(), clock::get);
		assertFalse(d.expired());
		assertEquals(100L, d.remainingMillis());
		clock.addAndGet(60_000_000L);
		assertEquals(40L, d.remainingMillis());
		clock.addAndGet(40_000_000L);
		assertTrue(d.expired());
		assertEquals(0L, d.remainingMillis());
	}

	@Test
	void checkThrowsBudgetExceededWithPhase() {
		AtomicLong clock = new AtomicLong();
		Deadline d = new Deadline(Duration.ofSeconds(1).toNanos(), clock::get);
		clock.set(Duration.ofSeconds(1).toNanos());
		BudgetExceededException ex = assertThrows(BudgetExceededException.class,
				() -> d.check("delete-archives"));
		assertEquals("delete-archives", ex.phase());
	}

	@Test
	void scenarioDeadlineIsCappedByTotalRemaining() {
		AtomicLong clock = new AtomicLong();
		Budget budget = new Budget(Duration.ofSeconds(100), Duration.ofSeconds(60), clock::get);
		clock.set(Duration.ofSeconds(90).toNanos());
		Deadline scenario = budget.startScenario("s1");
		assertEquals(10_000L, scenario.remainingMillis(), "场景预算不得越过总预算剩余");
		assertEquals(10_000L, budget.totalRemainingMillis());
	}

	@Test
	void totalBudgetIsSharedAcrossSequentialScenarios() {
		AtomicLong clock = new AtomicLong();
		Budget budget = new Budget(Duration.ofSeconds(100), Duration.ofSeconds(80), clock::get);
		clock.set(Duration.ofSeconds(70).toNanos());
		Deadline first = budget.startScenario("first");
		assertEquals(30_000L, first.remainingMillis());
		clock.set(Duration.ofSeconds(90).toNanos());
		Deadline second = budget.startScenario("second");
		assertEquals(10_000L, second.remainingMillis(),
				"second scenario must reuse the same total deadline");
		clock.set(Duration.ofSeconds(100).toNanos());
		assertTrue(second.expired());
		assertTrue(budget.totalExhausted());
	}

	@Test
	void zeroBudgetMeansUnlimitedForThatScope() {
		AtomicLong clock = new AtomicLong();
		Budget budget = new Budget(Duration.ZERO, Duration.ZERO, clock::get);
		Deadline scenario = budget.startScenario("s1");
		clock.set(Duration.ofHours(5).toNanos());
		assertFalse(scenario.expired());
		assertFalse(budget.totalExhausted());
	}

}
