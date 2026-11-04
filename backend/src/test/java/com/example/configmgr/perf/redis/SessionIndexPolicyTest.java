package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionIndexPolicyTest {

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private static final String SESSION = "perf-dual_executor-0";

	@Test
	void runIndexIsASetAndActiveIndexIsAZset() {
		String runsKey = KeyShapes.sessionRunsKey(TENANT, USER, SESSION);
		String activeKey = KeyShapes.sessionActiveKey(TENANT, USER, SESSION);
		assertEquals(SessionIndexPolicy.Index.RUNS, SessionIndexPolicy.indexOf(runsKey));
		assertEquals(SessionIndexPolicy.Index.ACTIVE, SessionIndexPolicy.indexOf(activeKey));
		assertEquals("set", SessionIndexPolicy.Index.RUNS.dataType());
		assertEquals("SREM", SessionIndexPolicy.Index.RUNS.removeCommand());
		assertEquals("zset", SessionIndexPolicy.Index.ACTIVE.dataType());
		assertEquals("ZREM", SessionIndexPolicy.Index.ACTIVE.removeCommand());
		assertTrue(runsKey.startsWith(SessionIndexPolicy.Index.RUNS.prefix()));
		assertTrue(activeKey.startsWith(SessionIndexPolicy.Index.ACTIVE.prefix()));
	}

	@Test
	void writeAndRemoveCommandsAgreeOnTheIndexType() {
		for (SessionIndexPolicy.Index index : SessionIndexPolicy.Index.values()) {
			String expectedType = index.writeCommand().startsWith("SADD") ? "set" : "zset";
			assertEquals(expectedType, index.dataType(), index.name());
			assertEquals(index.writeCommand().replace("ADD", "REM"), index.removeCommand(), index.name());
		}
	}

	@Test
	void activeIndexRemovalUsesZremWithAllMembers() {
		String activeKey = KeyShapes.sessionActiveKey(TENANT, USER, SESSION);
		String[] command = SessionIndexPolicy.remove(SessionIndexPolicy.Index.ACTIVE, activeKey,
				List.of("run-1", "run-2"));
		assertArrayEquals(new String[] { "ZREM", activeKey, "run-1", "run-2" }, command);
	}

	@Test
	void runIndexRemovalUsesSremWithAllMembers() {
		String runsKey = KeyShapes.sessionRunsKey(TENANT, USER, SESSION);
		String[] command = SessionIndexPolicy.remove(SessionIndexPolicy.Index.RUNS, runsKey,
				List.of("run-1"));
		assertArrayEquals(new String[] { "SREM", runsKey, "run-1" }, command);
	}

	@Test
	void removalRejectsAKeyFromAnotherIndexInsteadOfWrongTypeCommand() {
		String runsKey = KeyShapes.sessionRunsKey(TENANT, USER, SESSION);
		String activeKey = KeyShapes.sessionActiveKey(TENANT, USER, SESSION);
		assertThrows(IllegalArgumentException.class, () -> SessionIndexPolicy.remove(
				SessionIndexPolicy.Index.ACTIVE, runsKey, List.of("run-1")));
		assertThrows(IllegalArgumentException.class, () -> SessionIndexPolicy.remove(
				SessionIndexPolicy.Index.RUNS, activeKey, List.of("run-1")));
	}

	@Test
	void removalRejectsUnknownKeysAndEmptyMembers() {
		assertThrows(IllegalArgumentException.class,
				() -> SessionIndexPolicy.indexOf("ai:session:perf-unknown"));
		assertThrows(IllegalArgumentException.class, () -> SessionIndexPolicy.remove(
				SessionIndexPolicy.Index.RUNS, KeyShapes.sessionRunsKey(TENANT, USER, SESSION), List.of()));
	}

	@Test
	void runsAndActiveIndexesLiveInDifferentSlotsSoRemovalMustBePipelinedNotLua() {
		assertFalse(RedisSlots.sameSlot(List.of(
				KeyShapes.sessionRunsKey(TENANT, USER, SESSION),
				KeyShapes.sessionActiveKey(TENANT, USER, SESSION))));
	}

}
