package com.example.configmgr.perf.redis;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 带精确命令计数的 Redis 门面。命令数按实际发出的 Redis 命令计；pipeline 批次单独计数。
 * 字节数只能估算（RESP 帧 + 键/参数长度），输出统一标记 {@code bytesEstimated=true}。
 */
public final class CountingRedis {

	private static final String FIRST_STREAM_ID = "-";

	/** Redis stream ID 的两段都是无符号 64 位（sequence 到 2^64-1 后进位到下一毫秒）。 */
	private static final BigInteger MAX_STREAM_ID_PART = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

	private final StringRedisTemplate template;

	private final Duration commandTimeout;

	private long commands;

	private long pipelineBatches;

	private long scanPages;

	private long slotChecks;

	private long requestBytes;

	private long responseBytes;

	public CountingRedis(StringRedisTemplate template, Duration commandTimeout) {
		this.template = template;
		this.commandTimeout = commandTimeout;
	}

	public StringRedisTemplate template() {
		return this.template;
	}

	public record Snapshot(long commands, long pipelineBatches, long scanPages, long slotChecks,
			long requestBytes, long responseBytes) {
	}

	public Snapshot snapshot() {
		return new Snapshot(this.commands, this.pipelineBatches, this.scanPages, this.slotChecks,
				this.requestBytes, this.responseBytes);
	}

	public static Snapshot delta(Snapshot before, Snapshot after) {
		return new Snapshot(after.commands() - before.commands(),
				after.pipelineBatches() - before.pipelineBatches(),
				after.scanPages() - before.scanPages(),
				after.slotChecks() - before.slotChecks(),
				after.requestBytes() - before.requestBytes(),
				after.responseBytes() - before.responseBytes());
	}

	public String get(String key) {
		request(key);
		String value = this.template.opsForValue().get(key);
		response(value);
		return value;
	}

	public void set(String key, String value, Duration ttl) {
		request(key, value);
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			this.template.opsForValue().set(key, value);
		}
		else {
			this.template.opsForValue().set(key, value, ttl);
		}
	}

	public void setNoTtl(String key, String value) {
		set(key, value, null);
	}

	public long del(Collection<String> keys) {
		request(keys.toArray(String[]::new));
		Long n = this.template.delete(keys);
		response(n);
		return n == null ? 0L : n;
	}

	public boolean exists(String key) {
		request(key);
		Boolean exists = this.template.hasKey(key);
		response(exists);
		return Boolean.TRUE.equals(exists);
	}

	public String type(String key) {
		request(key);
		org.springframework.data.redis.connection.DataType type = this.template.type(key);
		response(type);
		return type == null ? "none" : type.code();
	}

	public String hget(String key, String field) {
		request(key, field);
		Object value = this.template.opsForHash().get(key, field);
		response(value);
		return value == null ? null : String.valueOf(value);
	}

	public void hset(String key, String field, String value) {
		request(key, field, value);
		this.template.opsForHash().put(key, field, value);
	}

	public void hsetnx(String key, String field, String value) {
		request(key, field, value);
		this.template.opsForHash().putIfAbsent(key, field, value);
	}

	public boolean hexists(String key, String field) {
		request(key, field);
		Boolean exists = this.template.opsForHash().hasKey(key, field);
		response(exists);
		return Boolean.TRUE.equals(exists);
	}

	public long hdel(String key, String field) {
		request(key, field);
		Long n = this.template.opsForHash().delete(key, field);
		response(n);
		return n == null ? 0L : n;
	}

	public long sadd(String key, Collection<String> members) {
		request(key, String.join(",", members));
		Long n = this.template.opsForSet().add(key, members.toArray(String[]::new));
		response(n);
		return n == null ? 0L : n;
	}

	public long srem(String key, String... members) {
		request(key, String.join(",", members));
		Long n = this.template.opsForSet().remove(key, (Object[]) members);
		response(n);
		return n == null ? 0L : n;
	}

	public Set<String> smembers(String key) {
		request(key);
		Set<String> members = this.template.opsForSet().members(key);
		response(members);
		return members == null ? Set.of() : members;
	}

	public long scard(String key) {
		request(key);
		Long n = this.template.opsForSet().size(key);
		response(n);
		return n == null ? 0L : n;
	}

	public long zadd(String key, String member, double score) {
		request(key, member);
		Boolean added = this.template.opsForZSet().add(key, member, score);
		response(added);
		return Boolean.TRUE.equals(added) ? 1L : 0L;
	}

	public long zrem(String key, String member) {
		request(key, member);
		Long n = this.template.opsForZSet().remove(key, member);
		response(n);
		return n == null ? 0L : n;
	}

	public Double zscore(String key, String member) {
		request(key, member);
		Double score = this.template.opsForZSet().score(key, member);
		response(score);
		return score;
	}

	public boolean pexpire(String key, long ttlMs) {
		request(key);
		Boolean ok = this.template.expire(key, ttlMs, TimeUnit.MILLISECONDS);
		response(ok);
		return Boolean.TRUE.equals(ok);
	}

	public long ttlSeconds(String key) {
		request(key);
		Long ttl = this.template.getExpire(key, TimeUnit.SECONDS);
		response(ttl);
		return ttl == null ? -2L : ttl;
	}

	public long incr(String key) {
		request(key);
		Long value = this.template.opsForValue().increment(key);
		response(value);
		return value == null ? 0L : value;
	}

	public long xlen(String key) {
		request(key);
		Long len = this.template.opsForStream().size(key);
		response(len);
		return len == null ? 0L : len;
	}

	/**
	 * XRANGE（1 条命令），返回 Spring Data 结构化 Stream 记录。
	 *
	 * <p>不能用通用 {@code connection.execute}：它对该命令回退到 Lettuce {@code ByteArrayOutput}，
	 * 会把嵌套数组整体解成一个 {@code byte[]}，条目字段全部丢失。
	 *
	 * <p>排他区间 {@code (id} 是 Redis 6.2 才有的语法，生产 5.0.14 会回
	 * {@code Error in execution}（{@code redis-cli} 发普通 XRANGE 才正常）。因此这里把
	 * {@code (lastId} 换成语义等价的物理 ID 后继（闭区间），standalone 与 Cluster 走同一条路径。
	 *
	 * @param cursor {@code -}、闭区间起点 ID，或 {@code (lastId} 排他游标
	 */
	public List<ByteRecord> xrange(String key, String cursor, int count) {
		String start = xrangeStart(cursor);
		if (start == null) {
			return List.of();
		}
		request("XRANGE", key, start, "+", "COUNT", String.valueOf(count));
		List<ByteRecord> records = this.template.execute((RedisCallback<List<ByteRecord>>) connection ->
				connection.streamCommands().xRange(key.getBytes(StandardCharsets.UTF_8), xrangeRange(start),
						Limit.limit().count(count)));
		response(records);
		return records == null ? List.of() : records;
	}

	/**
	 * 把分页游标统一成 XRANGE 的闭区间起点：{@code (lastId} → {@code lastId} 的后继。
	 *
	 * @return {@code -}、可直接发给旧版 Redis 的起点 ID；{@code null} 表示已越过可表示的最大 ID
	 * （不存在后继 ⇒ 不可能再有条目）
	 */
	static String xrangeStart(String cursor) {
		if (cursor == null || cursor.isEmpty() || FIRST_STREAM_ID.equals(cursor)) {
			return FIRST_STREAM_ID;
		}
		if (cursor.startsWith("(")) {
			return successorId(cursor.substring(1));
		}
		return cursor;
	}

	/**
	 * 物理 entry ID 的后继：sequence + 1；sequence 到 2^64-1 时进位到「下一毫秒-0」。
	 * 最大 ID（两段都到 2^64-1）没有可表示的后继，返回 {@code null}。
	 */
	static String successorId(String id) {
		if (id == null) {
			throw new IllegalArgumentException("stream id 不能为空");
		}
		String value = id.trim();
		int dash = value.indexOf('-');
		String millisPart = dash < 0 ? value : value.substring(0, dash);
		String sequencePart = dash < 0 ? "0" : value.substring(dash + 1);
		if (millisPart.isEmpty() || sequencePart.isEmpty() || sequencePart.indexOf('-') >= 0) {
			throw new IllegalArgumentException("非法 stream id：" + id);
		}
		BigInteger millis = streamIdPart(millisPart, id);
		BigInteger sequence = streamIdPart(sequencePart, id);
		if (sequence.compareTo(MAX_STREAM_ID_PART) < 0) {
			return millis + "-" + sequence.add(BigInteger.ONE);
		}
		if (millis.compareTo(MAX_STREAM_ID_PART) < 0) {
			return millis.add(BigInteger.ONE) + "-0";
		}
		return null;
	}

	private static BigInteger streamIdPart(String text, String id) {
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch < '0' || ch > '9') {
				throw new IllegalArgumentException("非法 stream id：" + id);
			}
		}
		BigInteger value = new BigInteger(text);
		if (value.compareTo(MAX_STREAM_ID_PART) > 0) {
			throw new IllegalArgumentException("stream id 超出无符号 64 位：" + id);
		}
		return value;
	}

	static Range<String> xrangeRange(String start) {
		if (start == null || start.isEmpty() || FIRST_STREAM_ID.equals(start)) {
			return Range.unbounded();
		}
		return Range.rightUnbounded(Bound.inclusive(start));
	}

	/**
	 * CLUSTER KEYSLOT（1 条命令）。走 Lettuce 专用的 {@code clusterKeyslot}，整数回复由
	 * {@code IntegerOutput} 解码；通用 {@code execute} 的 {@code ByteArrayOutput} 不接受整数，
	 * 会抛 {@code UnsupportedOperationException}。
	 */
	public long clusterKeyslot(String key) {
		request("CLUSTER", "KEYSLOT", key);
		Long slot = this.template.execute((RedisCallback<Long>) connection -> LettuceFutures.awaitOrCancel(
				nativeCommands(connection).clusterKeyslot(key.getBytes(StandardCharsets.UTF_8)),
				this.commandTimeout.toMillis(), TimeUnit.MILLISECONDS));
		response(slot);
		if (slot == null) {
			throw new IllegalStateException("CLUSTER KEYSLOT 未返回整数: " + key);
		}
		return slot;
	}

	private static RedisClusterAsyncCommands<byte[], byte[]> nativeCommands(RedisConnection connection) {
		Object nativeConnection = connection.getNativeConnection();
		if (nativeConnection instanceof RedisClusterAsyncCommands<?, ?> commands) {
			@SuppressWarnings("unchecked")
			RedisClusterAsyncCommands<byte[], byte[]> typed = (RedisClusterAsyncCommands<byte[], byte[]>) commands;
			return typed;
		}
		throw new IllegalStateException("数值命令需要 Lettuce 原生连接来选择输出类型，实际为 "
				+ nativeConnection.getClass().getName());
	}

	/**
	 * 原生命令（1 条命令）。返回类型由 Lettuce 默认 {@code ByteArrayOutput} 决定：bulk 与嵌套数组都会被压成
	 * {@code byte[]}，整数回复会抛 {@code UnsupportedOperationException}。Stream 用 {@link #xrange}，
	 * 数值命令用带数值输出的专用调用（如 {@link #clusterKeyslot}）。
	 */
	public Object raw(String... args) {
		request(args);
		Object result = this.template.execute((RedisCallback<Object>) connection -> {
			byte[][] bytes = new byte[args.length][];
			for (int i = 0; i < args.length; i++) {
				bytes[i] = args[i].getBytes(StandardCharsets.UTF_8);
			}
			return connection.execute(args[0], java.util.Arrays.copyOfRange(bytes, 1, bytes.length));
		});
		response(result);
		return result;
	}

	public <T> T eval(DefaultRedisScript<T> script, List<String> keys, Object... args) {
		requestScript(script.getScriptAsString(), keys, argsAsStrings(args));
		T result = this.template.execute(script, keys, args);
		response(result);
		return result;
	}

	/** 脚本 + KEYS + ARGV + RESP 开销的估算（不是网卡计数）。 */
	public static long estimateRequestBytes(String script, List<String> keys, List<String> args) {
		long bytes = 32L;
		if (script != null) {
			bytes += script.getBytes(StandardCharsets.UTF_8).length;
		}
		for (String key : keys) {
			bytes += key == null ? 0L : key.getBytes(StandardCharsets.UTF_8).length + 4L;
		}
		for (String arg : args) {
			bytes += arg == null ? 0L : arg.getBytes(StandardCharsets.UTF_8).length + 4L;
		}
		return bytes;
	}

	public static long estimateCommandBytes(String[] command) {
		long bytes = 16L;
		for (String part : command) {
			bytes += part == null ? 0L : part.getBytes(StandardCharsets.UTF_8).length + 4L;
		}
		return bytes;
	}

	private static List<String> argsAsStrings(Object... args) {
		List<String> out = new ArrayList<>();
		for (Object arg : args) {
			out.add(String.valueOf(arg));
		}
		return out;
	}

	/**
	 * 有界单键/同槽 Lua pipeline：每条脚本仍是独立命令，但只付一个网络批次。
	 */
	public List<Object> pipelineEval(DefaultRedisScript<?> script, List<List<String>> keysPerCall,
			List<List<String>> argsPerCall) {
		if (keysPerCall.size() != argsPerCall.size()) {
			throw new IllegalArgumentException("pipeline keys/args 数量不一致");
		}
		Snapshot before = snapshot();
		List<Object> results = this.template.executePipelined((RedisCallback<Object>) connection -> {
			for (int i = 0; i < keysPerCall.size(); i++) {
				List<String> keys = keysPerCall.get(i);
				List<String> args = argsPerCall.get(i);
				byte[][] all = new byte[keys.size() + args.size()][];
				int p = 0;
				for (String key : keys) {
					all[p++] = key.getBytes(StandardCharsets.UTF_8);
				}
				for (String arg : args) {
					all[p++] = arg.getBytes(StandardCharsets.UTF_8);
				}
				connection.scriptingCommands().eval(
						script.getScriptAsString().getBytes(StandardCharsets.UTF_8), ReturnType.MULTI,
						keys.size(), all);
			}
			return null;
		});
		this.commands += keysPerCall.size();
		this.pipelineBatches += 1L;
		for (int i = 0; i < keysPerCall.size(); i++) {
			this.requestBytes += estimateRequestBytes(script.getScriptAsString(), keysPerCall.get(i),
					argsPerCall.get(i));
		}
		this.responseBytes += results == null ? 0L : results.toString().length();
		return results == null ? List.of() : results;
	}

	/** 有界原生命令 pipeline（批次内命令数由调用方限制）。 */
	public List<Object> pipelineRaw(List<String[]> commands) {
		Snapshot before = snapshot();
		List<Object> results = this.template.executePipelined((RedisCallback<Object>) connection -> {
			for (String[] command : commands) {
				byte[][] bytes = new byte[command.length][];
				for (int i = 0; i < command.length; i++) {
					bytes[i] = command[i].getBytes(StandardCharsets.UTF_8);
				}
				connection.execute(command[0], java.util.Arrays.copyOfRange(bytes, 1, bytes.length));
			}
			return null;
		});
		this.commands += commands.size();
		this.pipelineBatches += 1L;
		for (String[] command : commands) {
			this.requestBytes += estimateCommandBytes(command);
		}
		this.responseBytes += results == null ? 0L : results.toString().length();
		return results == null ? List.of() : results;
	}

	/** 每个多键/同槽组检查一次；不同槽立即失败（不把 CROSSSLOT 留到运行期）。 */
	public void checkSameSlot(List<String> keys) {
		this.slotChecks++;
		if (!RedisSlots.sameSlot(keys)) {
			throw new IllegalStateException("候选协议键面跨槽：" + RedisSlots.groupBySlot(keys));
		}
	}

	public void recordScanPage() {
		this.scanPages++;
	}

	public long commands() {
		return this.commands;
	}

	public long pipelineBatches() {
		return this.pipelineBatches;
	}

	public long scanPages() {
		return this.scanPages;
	}

	public long slotChecks() {
		return this.slotChecks;
	}

	private void request(String... parts) {
		long bytes = 16L;
		for (String part : parts) {
			bytes += part == null ? 0L : part.getBytes(StandardCharsets.UTF_8).length + 4L;
		}
		this.commands++;
		this.requestBytes += bytes;
	}

	private void requestScript(String script, List<String> keys, List<String> args) {
		this.commands++;
		this.requestBytes += estimateRequestBytes(script, keys, args);
	}

	private void response(Object value) {
		if (value == null) {
			this.responseBytes += 8L;
			return;
		}
		if (value instanceof Collection<?> collection) {
			this.responseBytes += 16L + collection.size() * 16L;
			return;
		}
		this.responseBytes += 16L + String.valueOf(value).length();
	}

	public static Map<String, Object> byteSummary(Snapshot snapshot) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bytesEstimated", true);
		out.put("requestBytes", snapshot.requestBytes());
		out.put("responseBytes", snapshot.responseBytes());
		out.put("estimationMethod", "resp_frame_utf8_length_plus_overhead");
		return out;
	}

	public static <T> List<List<T>> batch(List<T> values, int size) {
		List<List<T>> out = new ArrayList<>();
		for (int i = 0; i < values.size(); i += size) {
			out.add(values.subList(i, Math.min(values.size(), i + size)));
		}
		return out;
	}

}
