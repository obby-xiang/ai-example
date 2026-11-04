package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSlotsTest {

	@Test
	void crc16MatchesKnownRedisVectors() {
		assertEquals(12739, RedisSlots.crc16("123456789"));
		assertEquals(12182, RedisSlots.slot("foo"));
		assertEquals(5061, RedisSlots.slot("bar"));
		assertEquals(0, RedisSlots.crc16(""));
	}

	@Test
	void hashTagUsesFirstNonEmptyBracePair() {
		assertEquals("user1000", RedisSlots.hashTag("{user1000}.following"));
		assertEquals("user1000", RedisSlots.hashTag("foo{user1000}bar"));
		assertNull(RedisSlots.hashTag("foo{}bar"));
		assertNull(RedisSlots.hashTag("foo{bar"));
		assertEquals("{bar", RedisSlots.hashTag("foo{{bar}}zap"));
	}

	@Test
	void taggedKeysShareSlot() {
		List<String> keys = List.of(
				"ai:run:{1:101:run:r1}",
				"ai:seq:{1:101:run:r1}",
				"ai:xevents:{1:101:run:r1}",
				"ai:xack:t1:{1:101:run:r1}");
		assertTrue(RedisSlots.sameSlot(keys));
		assertFalse(RedisSlots.sameSlot(List.of("ai:run:{1:101:run:r1}", "ai:run:{1:101:run:r2}")));
	}

}
