package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyShapesTest {

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	@Test
	void runKeysAreAllInTheSameSlot() {
		List<String> keys = KeyShapes.runBaseKeys(TENANT, USER, "r1");
		assertEquals(13, keys.size());
		assertEquals(KeyShapes.RUN_BASE_KEY_COUNT, keys.size());
		assertTrue(RedisSlots.sameSlot(keys), "13 个 run 键必须同槽");
		assertTrue(RedisSlots.sameSlot(KeyShapes.runDeleteKeys(TENANT, USER, "r1")), "含 xterminal 的删除键必须同槽");
		assertEquals(14, KeyShapes.runDeleteKeys(TENANT, USER, "r1").size());
	}

	@Test
	void runTagContainsIdentityAndRunId() {
		assertEquals("{1:101:run:r1}", KeyShapes.tag(TENANT, USER, "r1"));
		assertEquals("ai:run:{1:101:run:r1}", KeyShapes.runKey("ai:run:", TENANT, USER, "r1"));
		assertThrows(IllegalArgumentException.class, () -> KeyShapes.tag(TENANT, USER, "bad{run"));
	}

	@Test
	void stagingIndexAndStagingKeysShareSlot() {
		String sid = KeyShapes.sessionId("ns", "staging", 0);
		String index = KeyShapes.stagingIndexKey(TENANT, USER, sid);
		String a = KeyShapes.stagingKey(TENANT, USER, sid, "t1");
		String b = KeyShapes.stagingKey(TENANT, USER, sid, "t2");
		assertEquals("ns-staging-0", RedisSlots.hashTag(a));
		assertTrue(RedisSlots.sameSlot(List.of(index, a, b)));
	}

	@Test
	void legacyArchiveKeysHaveNoHashTagAndIndexIsTagged() {
		String sid = KeyShapes.sessionId("ns", "archive", 0);
		String archive = KeyShapes.archiveKey(TENANT, USER, sid, 7);
		String index = KeyShapes.archiveIndexKey(TENANT, USER, sid);
		assertNull(RedisSlots.hashTag(archive), "legacy archive 键形没有 hash tag（不能与索引同槽假设）");
		assertEquals("ns-archive-0", RedisSlots.hashTag(index));
	}

	@Test
	void purgeLeaseGuardPendingShareSessionSlot() {
		String sid = KeyShapes.sessionId("ns", "purge", 0);
		List<String> keys = List.of(
				KeyShapes.purgeGuardKey(TENANT, USER, sid),
				KeyShapes.purgePendingKey(TENANT, USER, sid),
				KeyShapes.purgeLeaseKey(TENANT, USER, sid));
		assertTrue(RedisSlots.sameSlot(keys));
		assertTrue(RedisSlots.sameSlot(List.of(KeyShapes.memoryKey(TENANT, USER, sid),
				KeyShapes.purgeGuardKey(TENANT, USER, sid))), "守卫必须与记忆键同槽才能原子拒绝写回");
	}

	@Test
	void namespaceIsEmbeddedInGeneratedIds() {
		String sid = KeyShapes.sessionId("n1", "runs_1", 2);
		assertEquals("n1-runs_1-2", sid);
		assertEquals("n1-runs_1-2-r3", KeyShapes.runId(sid, 3));
		assertTrue(KeyShapes.sessionKey(TENANT, USER, sid).contains("n1-runs_1-2"));
		assertTrue(KeyShapes.backgroundKey("n1", "b", 5).startsWith("perf:bg:n1:"));
		assertThrows(IllegalArgumentException.class, () -> KeyShapes.sessionId("bad{ns", "x", 0));
	}

}
