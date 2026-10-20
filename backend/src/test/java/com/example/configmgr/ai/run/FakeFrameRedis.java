package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件日志的进程内 Redis 替身（B23L1 批① 的单测底座）——<b>不为测试起真实 Redis</b>。
 *
 * <p>
 * 建模面与产品代码实际使用的命令<b>一一对应</b>：
 * <ul>
 * <li>{@code INCR ai:seq} / {@code SET ai:seq}（取号与号回退修复）；</li>
 * <li>{@code XADD key MAXLEN ~ n <id> seq n frame json} —— <b>显式 ID 语义按 Redis 实现</b>：
 * ID 必须严格大于表尾，否则抛错（产品据此走自动 ID 兜底）；{@code MAXLEN} 从<b>命令实参</b>读
 * （不是替身构造参数），因此"删掉实现的 MAXLEN ⇒ 本替身不裁剪"成立；</li>
 * <li>{@code XRANGE key (afterSeq-0 + COUNT n}（ID 区间差量读）与 {@code XREVRANGE key + - COUNT 1}（末号）；</li>
 * <li>{@code EXPIRE}（只记录值）、{@code LRANGE}（兼容期 LIST 只读）。</li>
 * </ul>
 *
 * <p>替身<b>刻意支持乱序落档</b>（显式 ID 被拒 ⇒ 产品用 {@code *} 兜底，替身按自动 ID 追加），
 * 这是 I-2（物理序 ≠ seq 序）用例得以复现的前提。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
final class FakeFrameRedis {

	/** 一条 Stream 记录：ID + 字段。 */
	private static final class Entry {

		final String id;

		final Map<byte[], byte[]> fields;

		Entry(String id, Map<byte[], byte[]> fields) {
			this.id = id;
			this.fields = fields;
		}

		String id() {
			return this.id;
		}

	}

	/** 键 → 计数器（{@code INCR}/{@code SET}）。 */
	final Map<String, Long> counters = new ConcurrentHashMap<>();

	/** 键 → Stream 记录（按 ID 升序）。 */
	final Map<String, List<Entry>> streams = new ConcurrentHashMap<>();

	/** 键 → LIST 原始元素（兼容期只读面）。 */
	final Map<String, List<String>> lists = new ConcurrentHashMap<>();

	/** 键 → 最近一次 EXPIRE 的时长。 */
	final Map<String, Duration> expiries = new ConcurrentHashMap<>();

	/** 自动 ID 的第二段（毫秒段取固定大值，保证 {@code *} 兜底永远排在所有 {@code seq-0} 之后）。 */
	private final AtomicLong autoIds = new AtomicLong();

	/** 置 true = 模拟 {@code INCR} 失败（M-4：取号降级为进程内计数）。 */
	volatile boolean failIncrement;

	/** 置 true = 模拟 {@code XADD} 失败（M-4：落档失败只告警、不中断出帧）。 */
	volatile boolean failXadd;

	/**
	 * 近似裁剪的"多余保留条数"（M′）：真实 {@code MAXLEN ~} 会保留到 窗口 + 宏节点条数（≤ +99），
	 * 默认 0 = 精确裁剪。大于 0 时按 {@code 窗口 + retainExtra} 保留 —— 这是"流内实际条数 &gt; 窗口"
	 * 的唯一可测形态（精确裁剪永远测不到读方向的窗口边界）。
	 */
	volatile int retainExtra;

	final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	final RedisConnection connection = mock(RedisConnection.class);

	FakeFrameRedis() {
		when(this.redis.opsForValue()).thenReturn(this.values);
		when(this.redis.opsForList()).thenReturn(this.listOps);
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			this.expiries.put(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		});
		when(this.values.increment(anyString())).thenAnswer(invocation -> {
			if (this.failIncrement) {
				throw new IllegalStateException("INCR 失败（模拟 Redis 抖动）");
			}
			return this.counters.merge(invocation.getArgument(0), 1L, Long::sum);
		});
		org.mockito.Mockito.doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			Object value = invocation.getArgument(1);
			this.counters.put(key, Long.parseLong(String.valueOf(value)));
			this.expiries.put(key, invocation.getArgument(2));
			return null;
		}).when(this.values).set(anyString(), anyString(), any(Duration.class));
		when(this.values.get(anyString())).thenAnswer(invocation -> {
			Long value = this.counters.get(invocation.getArgument(0));
			return value == null ? null : String.valueOf(value);
		});
		when(this.listOps.range(anyString(), anyLong(), anyLong()))
				.thenAnswer(invocation -> sliceList(invocation.getArgument(0), (Long) invocation.getArgument(1),
						(Long) invocation.getArgument(2)));
		when(this.redis.execute(any(RedisCallback.class)))
				.thenAnswer(invocation -> ((RedisCallback) invocation.getArgument(0)).doInRedis(this.connection));
		when(this.connection.execute(anyString(), any(byte[][].class))).thenAnswer(invocation -> {
			String command = ((String) invocation.getArgument(0)).toUpperCase();
			// Mockito 把 varargs 展开成多个实际参数（getArgument(1) 是第一个元素，不是数组）
			Object[] raw = invocation.getArguments();
			byte[][] args;
			if (raw.length == 2 && raw[1] instanceof byte[][] packed) {
				args = packed;
			}
			else {
				args = new byte[raw.length - 1][];
				for (int i = 1; i < raw.length; i++) {
					args[i - 1] = (byte[]) raw[i];
				}
			}
			if ("XADD".equals(command)) {
				return handleXadd(args);
			}
			throw new UnsupportedOperationException("未建模的原始命令：" + command);
		});
		when(this.connection.xRange(any(byte[].class), any(Range.class), any(Limit.class)))
				.thenAnswer(invocation -> {
					String key = new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8);
					Range<String> range = invocation.getArgument(1);
					Limit limit = invocation.getArgument(2);
					return selectRange(key, range, limit);
				});
		when(this.connection.xRevRange(any(byte[].class), any(Range.class), any(Limit.class)))
				.thenAnswer(invocation -> {
					String key = new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8);
					List<Entry> entries = entries(key);
					List<ByteRecord> reversed = new ArrayList<>();
					Limit limit = invocation.getArgument(2);
					for (int i = entries.size() - 1; i >= 0; i--) {
						if (limit.getCount() > 0 && reversed.size() >= limit.getCount()) {
							break;
						}
						reversed.add(record(entries.get(i)));
					}
					return reversed;
				});
		when(this.connection.expire(any(byte[].class), anyLong())).thenAnswer(invocation -> {
			this.expiries.put(new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8),
					Duration.ofSeconds((Long) invocation.getArgument(1)));
			return true;
		});
	}

	// ── 命令实现 ────────────────────────────────────────────────────────────

	/**
	 * {@code XADD key [MAXLEN|~ n] <id|*> field value ...}：显式 ID 必须大于表尾（Redis 语义），
	 * 否则抛错让产品走自动 ID 兜底；{@code MAXLEN} 取自命令实参（M-5）。
	 */
	private byte[] handleXadd(byte[][] args) {
		if (this.failXadd) {
			throw new IllegalStateException("XADD 失败（模拟 Redis 抖动）");
		}
		String key = str(args[0]);
		int index = 1;
		long maxlen = -1L;
		if (index < args.length && "MAXLEN".equalsIgnoreCase(str(args[index]))) {
			index++;
			if (index < args.length && ("~".equals(str(args[index])) || "=".equals(str(args[index])))) {
				index++;
			}
			maxlen = Long.parseLong(str(args[index]));
			index++;
		}
		String idToken = str(args[index++]);
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		while (index + 1 < args.length) {
			fields.put(args[index], args[index + 1]);
			index += 2;
		}
		List<Entry> entries = entries(key);
		String id;
		synchronized (entries) {
			if ("*".equals(idToken)) {
				id = String.valueOf(9_000_000_000_000L + this.autoIds.incrementAndGet()) + "-0";
			}
			else {
				id = idToken;
				if (!entries.isEmpty()) {
					String top = entries.get(entries.size() - 1).id;
					if (compareIds(id, top) <= 0) {
						throw new IllegalStateException("ERR The ID specified in XADD is equal or smaller than the target stream top item");
					}
				}
			}
			entries.add(new Entry(id, fields));
			entries.sort(Comparator.comparing(Entry::id, FakeFrameRedis::compareIds));
			if (maxlen > 0) {
				long limit = maxlen + Math.max(0, this.retainExtra);
				while (entries.size() > limit) {
					entries.remove(0);
				}
			}
		}
		return id.getBytes(StandardCharsets.UTF_8);
	}

	/** {@code XRANGE key (afterSeq-0 + [COUNT n]}：按 ID 区间取记录（升序）。 */
	private List<ByteRecord> selectRange(String key, Range<String> range, Limit limit) {
		List<Entry> entries = entries(key);
		String lower = "-";
		boolean lowerInclusive = true;
		if (range.getLowerBound().isBounded()) {
			lower = range.getLowerBound().getValue().orElse("-");
			lowerInclusive = range.getLowerBound().isInclusive();
		}
		String upper = "+";
		boolean upperInclusive = true;
		if (range.getUpperBound().isBounded()) {
			upper = range.getUpperBound().getValue().orElse("+");
			upperInclusive = range.getUpperBound().isInclusive();
		}
		List<ByteRecord> out = new ArrayList<>();
		synchronized (entries) {
			for (Entry entry : entries) {
				if (!"-".equals(lower)) {
					int cmp = compareIds(entry.id, lower);
					if (cmp < 0 || (cmp == 0 && !lowerInclusive)) {
						continue;
					}
				}
				if (!"+".equals(upper)) {
					int cmp = compareIds(entry.id, upper);
					if (cmp > 0 || (cmp == 0 && !upperInclusive)) {
						break;
					}
				}
				if (limit.getCount() > 0 && out.size() >= limit.getCount()) {
					break;
				}
				out.add(record(entry));
			}
		}
		return out;
	}

	// ── 取证与语料辅助 ──────────────────────────────────────────────────────

	/** 铺一段遗留 LIST（兼容期语料）。 */
	void seedList(String key, String... jsonFrames) {
		this.lists.computeIfAbsent(key, k -> new ArrayList<>()).addAll(List.of(jsonFrames));
	}

	void seedList(String key, ObjectMapper mapper, List<Map<String, Object>> frames) {
		for (Map<String, Object> frame : frames) {
			try {
				seedList(key, mapper.writeValueAsString(frame));
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	/** 模拟键过期/被删（M-1：{@code ai:seq} 消失后取号不许从 1 重来）。 */
	void dropCounter(String key) {
		this.counters.remove(key);
	}

	/** Stream 记录字段（按物理顺序）。 */
	List<Map<byte[], byte[]>> stream(String key) {
		List<Map<byte[], byte[]>> out = new ArrayList<>();
		for (Entry entry : entries(key)) {
			out.add(entry.fields);
		}
		return out;
	}

	/** Stream 记录 ID（按物理顺序；乱序用例用它断言"物理序 ≠ seq 序"确实出现过）。 */
	List<String> streamIds(String key) {
		List<String> out = new ArrayList<>();
		for (Entry entry : entries(key)) {
			out.add(entry.id);
		}
		return out;
	}

	List<String> list(String key) {
		return this.lists.getOrDefault(key, List.of());
	}

	Duration expiry(String key) {
		return this.expiries.get(key);
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	private List<Entry> entries(String key) {
		return this.streams.computeIfAbsent(key, k -> new ArrayList<>());
	}

	private static ByteRecord record(Entry entry) {
		// ByteRecord 构造器是包内可见的：替身用桩件复刻"读到一条记录"的最小面
		// （getStream/getValue），读取侧只用这两个方法。
		ByteRecord record = mock(ByteRecord.class);
		when(record.getStream()).thenReturn(new byte[0]);
		when(record.getValue()).thenReturn(entry.fields);
		return record;
	}

	/** Redis Stream ID 比较（{@code <ms>-<seq>} 两段数值序）。 */
	static int compareIds(String left, String right) {
		long[] a = parseId(left);
		long[] b = parseId(right);
		int cmp = Long.compare(a[0], b[0]);
		return cmp != 0 ? cmp : Long.compare(a[1], b[1]);
	}

	private static long[] parseId(String id) {
		String[] parts = id.split("-");
		return new long[] { Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : 0L };
	}

	private static String str(byte[] value) {
		return new String(value, StandardCharsets.UTF_8);
	}

	private List<String> sliceList(String key, long start, long stop) {
		List<String> all = this.lists.getOrDefault(key, List.of());
		int size = all.size();
		if (size == 0) {
			return List.of();
		}
		int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
		int to = (int) (stop < 0 ? size + stop : Math.min(stop, size - 1));
		if (to < from || from >= size) {
			return List.of();
		}
		return new ArrayList<>(all.subList(from, to + 1));
	}

	static Map<String, byte[]> asMap(Map<byte[], byte[]> raw) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
			out.put(str(entry.getKey()), entry.getValue());
		}
		return out;
	}

	static boolean isOneOf(String value, String... candidates) {
		return Arrays.asList(candidates).contains(value);
	}

}
