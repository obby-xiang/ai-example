package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stream 物理 ID 分页读取（XRANGE 排他游标），供 purge 终态探测与 B 场景共用。 */
public final class RedisStreams {

	private RedisStreams() {
	}

	public static StreamPager.Result scan(CountingRedis redis, String streamKey, int pageSize, int maxEntries) {
		return StreamPager.scan((cursor, count) -> parseXRange(redis.raw("XRANGE", streamKey, cursor, "+",
				"COUNT", String.valueOf(count))), pageSize, maxEntries);
	}

	@SuppressWarnings("unchecked")
	public static List<StreamPager.Entry> parseXRange(Object raw) {
		List<StreamPager.Entry> out = new ArrayList<>();
		if (!(raw instanceof List<?> rows)) {
			return out;
		}
		for (Object row : rows) {
			if (!(row instanceof List<?> pair) || pair.size() < 2) {
				continue;
			}
			String id = text(pair.get(0));
			Object fieldsRaw = pair.get(1);
			Map<String, String> fields = new LinkedHashMap<>();
			if (fieldsRaw instanceof List<?> fieldsList) {
				for (int i = 0; i + 1 < fieldsList.size(); i += 2) {
					fields.put(text(fieldsList.get(i)), text(fieldsList.get(i + 1)));
				}
			}
			out.add(new StreamPager.Entry(id, fields));
		}
		return out;
	}

	public static String text(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return String.valueOf(value);
	}

}
