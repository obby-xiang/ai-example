package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.connection.stream.ByteRecord;

/** Stream 物理 ID 分页读取（XRANGE 排他游标），供 purge 终态探测与 B 场景共用。 */
public final class RedisStreams {

	private RedisStreams() {
	}

	public static StreamPager.Result scan(CountingRedis redis, String streamKey, int pageSize, int maxEntries) {
		return StreamPager.scan((cursor, count) -> toEntries(redis.xrange(streamKey, cursor, count)),
				pageSize, maxEntries);
	}

	/** Spring Data 结构化 {@link ByteRecord} → {@link StreamPager.Entry}。 */
	public static List<StreamPager.Entry> toEntries(List<ByteRecord> records) {
		List<StreamPager.Entry> out = new ArrayList<>();
		if (records == null) {
			return out;
		}
		for (ByteRecord record : records) {
			if (record == null || record.getId() == null) {
				continue;
			}
			Map<String, String> fields = new LinkedHashMap<>();
			Map<byte[], byte[]> rawFields = record.getValue();
			if (rawFields != null) {
				for (Map.Entry<byte[], byte[]> field : rawFields.entrySet()) {
					fields.put(text(field.getKey()), text(field.getValue()));
				}
			}
			out.add(new StreamPager.Entry(record.getId().getValue(), fields));
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
