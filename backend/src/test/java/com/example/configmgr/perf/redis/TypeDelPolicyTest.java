package com.example.configmgr.perf.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeDelPolicyTest {

	@Test
	void onlyExpectedTypeIsDeleted() {
		assertEquals(TypeDelPolicy.Action.DELETE, TypeDelPolicy.classify(true, "string", "string"));
		assertEquals(TypeDelPolicy.Action.MISSING, TypeDelPolicy.classify(false, "string", "none"));
		assertEquals(TypeDelPolicy.Action.TYPE_MISMATCH, TypeDelPolicy.classify(true, "string", "hash"));
	}

	@Test
	void typeDelScriptCarriesExpectedTypeAndDoesNotDeleteOnMismatch() {
		String script = Scripts.TYPE_DEL.getScriptAsString();
		assertTrue(script.contains("ARGV[1]"), "TYPE_DEL must take the expected type");
		assertTrue(script.contains("~="), "TYPE_DEL must compare actual type with expected");
		assertTrue(script.contains("-1"), "TYPE_DEL must return a distinct mismatch result");
	}

}
