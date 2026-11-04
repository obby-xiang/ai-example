package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestBackgroundTest {

	@Test
	void externalBackgroundIsDeclaredButNeverExpanded() {
		Manifest manifest = Manifest.empty("ns", 1, 101, "stress");
		Manifest.Dataset dataset = new Manifest.Dataset("bg_existing_runs_1", 0, "ns-bg_existing-0");
		dataset.declaredBackground = 1_000_000;
		dataset.externalBackground = true;
		dataset.background = 0;
		manifest.add(dataset);

		assertTrue(Manifest.backgroundKeys("ns", dataset).isEmpty(),
				"existing million-key keyspace must not be expanded");
		assertFalse(manifest.allKeys().stream().anyMatch(k -> k.startsWith("perf:bg:")),
				"allKeys must not enumerate an external background");
	}

	@Test
	void generatedBackgroundStillExpandsExactly() {
		Manifest manifest = Manifest.empty("ns", 1, 101, "full");
		Manifest.Dataset dataset = new Manifest.Dataset("bg_10k_runs_1", 0, "ns-bg_10k_runs_1-0");
		dataset.background = 10_000;
		dataset.declaredBackground = 10_000;
		dataset.backgroundBucket = "b10000";
		manifest.add(dataset);

		List<String> keys = Manifest.backgroundKeys("ns", dataset);
		assertTrue(keys.size() == 10_000);
		assertTrue(keys.get(0).startsWith("perf:bg:ns:b10000:"));
	}

}
