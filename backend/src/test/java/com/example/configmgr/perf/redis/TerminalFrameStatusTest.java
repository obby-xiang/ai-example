package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerminalFrameStatusTest {

	@Test
	void mapsTerminalTypesToConsistentSnapshotStatus() {
		assertEquals("DONE", RunStateMachine.terminalStatus("{\"type\":\"done\"}"));
		assertEquals("FAILED", RunStateMachine.terminalStatus("{\"type\":\"error\"}"));
		assertEquals("CANCELLED",
				RunStateMachine.terminalStatus("{\"type\":\"done\",\"cancelled\":true}"));
		assertEquals("CANCELLED",
				RunStateMachine.terminalStatus("{\"type\":\"error\",\"cancelled\":true}"));
	}

	@Test
	void unknownOrMalformedTerminalFramesDoNotConverge() {
		assertNull(RunStateMachine.terminalStatus("{\"type\":\"delta\"}"));
		assertNull(RunStateMachine.terminalStatus("not-json"));
		assertNull(RunStateMachine.terminalStatus(null));
	}

}
