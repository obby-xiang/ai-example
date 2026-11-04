package com.example.configmgr.perf.redis;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Cluster 槽计算（CRC16/XMODEM，与 {@code CLUSTER KEYSLOT} 同算法）。
 *
 * <p>只用于 harness 的本地同槽断言与输出统计；生产实现仍以 Redis 的 MOVED/CROSSSLOT 为准。
 */
public final class RedisSlots {

	private static final int[] TABLE = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			int crc = i << 8;
			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
			}
			TABLE[i] = crc & 0xFFFF;
		}
	}

	private RedisSlots() {
	}

	public static int crc16(String value) {
		int crc = 0;
		for (int i = 0; i < value.length(); i++) {
			crc = ((crc << 8) ^ TABLE[((crc >>> 8) ^ (value.charAt(i) & 0xFF)) & 0xFF]) & 0xFFFF;
		}
		return crc;
	}

	/** Redis 的 hash tag 规则：第一个 {@code &#123;} 与其后第一个非空 {@code &#125;} 之间。 */
	public static String hashTag(String key) {
		int open = key.indexOf('{');
		if (open < 0) {
			return null;
		}
		int close = key.indexOf('}', open + 1);
		if (close <= open + 1) {
			return null;
		}
		return key.substring(open + 1, close);
	}

	public static int slot(String key) {
		String tag = hashTag(key);
		return crc16(tag == null ? key : tag) & 0x3FFF;
	}

	public static boolean sameSlot(Collection<String> keys) {
		if (keys.isEmpty()) {
			return true;
		}
		int first = -1;
		for (String key : keys) {
			int slot = slot(key);
			if (first < 0) {
				first = slot;
			}
			else if (first != slot) {
				return false;
			}
		}
		return true;
	}

	public static Map<Integer, List<String>> groupBySlot(Collection<String> keys) {
		Map<Integer, List<String>> groups = new LinkedHashMap<>();
		for (String key : keys) {
			groups.computeIfAbsent(slot(key), ignored -> new java.util.ArrayList<>()).add(key);
		}
		return groups;
	}

}
