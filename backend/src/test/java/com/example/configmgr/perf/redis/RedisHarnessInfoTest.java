package com.example.configmgr.perf.redis;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisHarnessInfoTest {

	@Test
	void parsesBulkInfoSections() {
		String bulk = "# Server\r\nredis_version:7.2.4\r\nredis_mode:cluster\r\n\r\n"
				+ "# Cluster\r\ncluster_enabled:1\r\n# Keyspace\r\ndb0:keys=3,expires=0\r\n";
		Map<String, String> info = RedisHarness.parseInfo(bulk);

		assertEquals("7.2.4", info.get("redis_version"));
		assertEquals("cluster", info.get("redis_mode"));
		assertEquals("1", info.get("cluster_enabled"));
		assertEquals("keys=3,expires=0", info.get("db0"));
		assertFalse(info.containsKey("# Server"));
	}

	@Test
	void keepsFirstOccurrenceAndIgnoresMalformedLines() {
		Map<String, String> info = RedisHarness.parseInfo("redis_mode:standalone\r\nredis_mode:cluster\r\nno-colon\r\n");

		assertEquals("standalone", info.get("redis_mode"));
		assertFalse(info.containsKey("no-colon"));
	}

	@Test
	void emptyBulkYieldsEmptyInfo() {
		assertTrue(RedisHarness.parseInfo(null).isEmpty());
		assertTrue(RedisHarness.parseInfo("  \r\n").isEmpty());
	}

	@Test
	void flattensClusterNodePrefixedProperties() {
		Properties properties = new Properties();
		properties.setProperty("10.0.0.11:6379.redis_version", "7.2.4");
		properties.setProperty("10.0.0.11:6379.redis_mode", "cluster");
		properties.setProperty("10.0.0.11:6379.cluster_enabled", "1");

		Map<String, String> info = RedisHarness.flattenProperties(properties);

		assertEquals("7.2.4", info.get("redis_version"));
		assertEquals("cluster", info.get("redis_mode"));
		assertEquals("1", info.get("cluster_enabled"));
	}

	@Test
	void derivesClusterModeFromRealInfo() {
		RedisHarness.ServerInfo info = RedisHarness.fromInfo(
				RedisHarness.parseInfo("redis_version:7.2.4\r\nredis_mode:cluster\r\ncluster_enabled:1\r\n"), 12L);

		assertEquals("7.2.4", info.version());
		assertEquals("cluster", info.mode());
		assertEquals(12L, info.dbSize());
		assertTrue(info.clusterEnabled());
	}

	@Test
	void derivesStandaloneModeFromRealInfo() {
		RedisHarness.ServerInfo info = RedisHarness.fromInfo(
				RedisHarness.parseInfo("redis_version:7.2.4\r\nredis_mode:standalone\r\ncluster_enabled:0\r\n"), 0L);

		assertEquals("standalone", info.mode());
		assertFalse(info.clusterEnabled());
	}

	@Test
	void clusterFlagWinsWhenModeIsMissing() {
		RedisHarness.ServerInfo info = RedisHarness.fromInfo(
				RedisHarness.parseInfo("redis_version:6.2.0\r\ncluster_enabled:1\r\n"), null);

		assertTrue(info.clusterEnabled());
		assertEquals(-1L, info.dbSize());
	}

	@Test
	void missingInfoNeverFabricatesAMode() {
		RedisHarness.ServerInfo info = RedisHarness.fromInfo(Map.of(), null);

		assertEquals("unknown", info.version());
		assertEquals("unknown", info.mode());
		assertEquals(-1L, info.dbSize());
		assertFalse(info.clusterEnabled());
	}

}
