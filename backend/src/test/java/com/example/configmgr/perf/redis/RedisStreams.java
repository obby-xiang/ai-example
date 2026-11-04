package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.connection.stream.ByteRecord;

/**
 * Stream 物理 ID 分页读取，供 purge 终态探测与 B 场景共用。
 *
 * <p>分页游标是 {@code (lastId} 排他语义，但 {@link CountingRedis#xrange} 会把它换成
 * 物理 ID 后继的闭区间再发命令：Redis 5.0.14 不认识 {@code (id}（6.2+ 才有），
 * standalone 与 Cluster 因此走同一条命令路径。
 */
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
