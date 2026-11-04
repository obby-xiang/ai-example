package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveIndexPolicyTest {

	@Test
	void deletedAndAlreadyAbsentMembersAreRemovedFromIndex() {
		ArchiveIndexPolicy.BatchResult result = ArchiveIndexPolicy.classifyBatch(
				List.of("0", "1", "2"), List.of(1L, 0L, -1L));
		assertEquals(List.of("0", "1"), result.removableMembers());
		assertEquals(List.of("2"), result.retainedMembers());
		assertEquals(1L, result.deleted());
		assertEquals(1L, result.absent());
		assertEquals(1L, result.typeMismatch());
	}

	@Test
	void typeMismatchMustRetainKeyAndIndexMember() {
		ArchiveIndexPolicy.BatchResult result = ArchiveIndexPolicy.classifyBatch(
				List.of("7"), List.of(-1L));
		assertTrue(result.retainedMembers().contains("7"));
		assertFalse(result.removableMembers().contains("7"));
	}

	@Test
	void indexIsDeletedOnlyWhenNoMembersRemain() {
		assertTrue(ArchiveIndexPolicy.shouldDeleteIndex(0L));
		assertFalse(ArchiveIndexPolicy.shouldDeleteIndex(1L));
	}

}
