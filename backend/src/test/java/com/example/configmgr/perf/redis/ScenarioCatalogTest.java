package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioCatalogTest {

	@Test
	void activeUnfinalizableIsDeclaredExpectedDeferred() {
		assertTrue(ScenarioCatalog.expectedDeferred("active_unfinalizable"));
		assertFalse(ScenarioCatalog.expectedDeferred("mixed_active"));
		assertFalse(ScenarioCatalog.expectedDeferred("runs_1_terminal"));
	}

}
