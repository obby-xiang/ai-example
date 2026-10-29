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
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
 * <p><b>版本口径 = 目标部署的 Redis 5.0.14，不是开发机上的 6.x/7.x</b>：排他区间（{@code (id}）
 * 是 6.2 才有的语法，5.0 把 {@code (123-0} 当<b>非法 stream ID 直接报错</b>（真实响应
 * {@code ERR Invalid stream ID specified as stream command argument}）。替身照此报错 ——
 * 于是"实现里用了排他下限"在单测里就表现为<b>一次读失败</b>（产品侧若吞掉异常，就是 0 帧），
 * 这正是线上那条路径的形态。
 *
 * <p>替身<b>刻意支持乱序落档</b>（显式 ID 被拒 ⇒ 产品用 {@code *} 兜底，替身按自动 ID 追加），
 * 这是 I-2（物理序 ≠ seq 序）用例得以复现的前提。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
final class FakeFrameRedis {

	/** 业务活动锚点的单键原子 max + 续期（R1-09 / I-3）。 */
	static final String ACTIVITY_TOUCH_MARK = "ACTIVITY_TOUCH";

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

	/** 置 true = 模拟 {@code XRANGE} 读失败（真实连接层异常，不是"读不到"）。 */
	volatile boolean failRangeRead;

	/** 置 true = 模拟末号探测 {@code XREVRANGE} 读失败。 */
	volatile boolean failTailRead;

	/**
	 * 置 true = <b>只让整段 LIST 读</b>（{@code LRANGE -<窗口> -1}）失败；末帧探测（{@code LRANGE -1 -1}）照常成功。
	 * 与 {@link #failListTailProbe} 分开，是因为产品在 {@code readFrom} 里先探末帧、再决定是否整段读：
	 * 一个"全挂"的开关只会命中末帧探测，永远走不到整段读那条 WARN（I1 假绿的成因）。
	 */
	volatile boolean failListFullRead;

	/** 置 true = <b>只让 LIST 末帧探测</b>（{@code LRANGE -1 -1}）失败；整段读照常成功。 */
	volatile boolean failListTailProbe;

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
				.thenAnswer(invocation -> {
					long start = invocation.getArgument(1);
					long stop = invocation.getArgument(2);
					// 产品先 LRANGE -1 -1 探末帧，有内容才 LRANGE -窗口 -1 整段读
					boolean tailProbe = start == -1L && stop == -1L;
					if (tailProbe ? this.failListTailProbe : this.failListFullRead) {
						throw new IllegalStateException((tailProbe ? "LRANGE -1 -1（末帧探测）" : "LRANGE 整段")
								+ " 失败（模拟连接层异常）");
					}
					return sliceList(invocation.getArgument(0), (Long) invocation.getArgument(1),
							(Long) invocation.getArgument(2));
				});
		when(this.redis.execute(any(RedisCallback.class)))
				.thenAnswer(invocation -> ((RedisCallback) invocation.getArgument(0)).doInRedis(this.connection));
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenAnswer(invocation -> handleScript(invocation.getArgument(0), invocation.getArgument(1),
						trailingArguments(invocation.getArguments())));
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
					if (this.failRangeRead) {
						throw new org.springframework.data.redis.RedisConnectionFailureException("XRANGE 失败（模拟连接层异常）");
					}
					String key = new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8);
					Range<String> range = invocation.getArgument(1);
					Limit limit = invocation.getArgument(2);
					return selectRange(key, range, limit);
				});
		when(this.connection.xRevRange(any(byte[].class), any(Range.class), any(Limit.class)))
				.thenAnswer(invocation -> {
					if (this.failTailRead) {
						throw new org.springframework.data.redis.RedisConnectionFailureException("XREVRANGE 失败（模拟连接层异常）");
					}
					String key = new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8);
					rejectExclusiveBounds(invocation.getArgument(1));
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
			// XADD 语义保证新 ID 严格大于表尾（显式 ID 已校验，自动 ID 取固定大值），追加即在表尾，
			// 不必每笔重排 —— 6000+ 帧的回放用例靠这一点保持毫秒级。
			entries.add(new Entry(id, fields));
			if (maxlen > 0) {
				long limit = maxlen + Math.max(0, this.retainExtra);
				while (entries.size() > limit) {
					entries.remove(0);
				}
			}
		}
		return id.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * {@code XRANGE key <from> + [COUNT n]}：按 ID 区间取记录（升序）。
	 *
	 * <p>先按目标部署的 5.0 口径<b>拒绝排他区间</b>（见 {@link #rejectExclusiveBounds}），
	 * 再按闭区间取数 —— 于是"产品用了 {@code (id} 下限"在单测里必然暴露为一次读失败，
	 * 而不是悄悄少读一条。
	 */
	private List<ByteRecord> selectRange(String key, Range<String> range, Limit limit) {
		rejectExclusiveBounds(range);
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

	/**
	 * Redis 5.0.14 口径：排他区间（{@code (id}）是 6.2 才有，5.0 把 {@code (123-0} 当非法 stream ID
	 * <b>直接报错</b>（{@code ERR Invalid stream ID specified as stream command argument}），
	 * 而不是"少读一条"。真实命令报错经 Spring 转换成 {@code DataAccessException}；
	 * 产品侧若把这个异常吞成空结果，线上表现就是<b>回放 0 帧</b>。
	 */
	private static void rejectExclusiveBounds(Range<String> range) {
		if (range.getLowerBound().isBounded() && !range.getLowerBound().isInclusive()) {
			throw exclusiveRejected("下限", range.getLowerBound().getValue().orElse("?"));
		}
		if (range.getUpperBound().isBounded() && !range.getUpperBound().isInclusive()) {
			throw exclusiveRejected("上限", range.getUpperBound().getValue().orElse("?"));
		}
	}

	private static org.springframework.data.redis.RedisSystemException exclusiveRejected(String bound, String value) {
		return new org.springframework.data.redis.RedisSystemException(
				"ERR Invalid stream ID specified as stream command argument（5.0 不支持排他" + bound + " " + value + "）",
				null);
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

	/**
	 * 脚本语义替身（{@code synchronized} = 复刻 Redis 脚本<b>串行执行</b>）。
	 *
	 * <p>串行化是这些脚本的全部意义所在：取号脚本的"读归档末号 + 抬号 + 自增"若在替身里不串行，
	 * 并发用例就会替产品说谎（假绿）。
	 */
	private synchronized Object handleScript(RedisScript<?> script, List<?> keys, List<Object> args) {
		String body = script.getScriptAsString();
		if (body.contains(RedisFrameStore.SEQ_NEXT_MARK)) {
			return seqNext(keys, args);
		}
		if (!body.contains(ACTIVITY_TOUCH_MARK)) {
			throw new UnsupportedOperationException("未建模的脚本：" + body);
		}
		String key = String.valueOf(keys.get(0));
		long incoming = Long.parseLong(String.valueOf(args.get(0)));
		Duration ttl = Duration.ofMillis(Long.parseLong(String.valueOf(args.get(1))));
		long current = this.counters.getOrDefault(key, 0L);
		if (incoming > current) {
			this.counters.put(key, incoming);
		}
		if (this.counters.containsKey(key)) {
			this.expiries.put(key, ttl);
		}
		return 1L;
	}

	/** 取号脚本（见 {@code RedisFrameStore} 的 {@code AI_SEQ_NEXT}）：返回 {@code [seq, degraded]}。 */
	private List<Long> seqNext(List<?> keys, List<Object> args) {
		if (this.failIncrement) {
			throw new IllegalStateException("取号脚本失败（模拟 Redis 抖动）");
		}
		String seqKey = String.valueOf(keys.get(0));
		String streamKey = String.valueOf(keys.get(1));
		String legacyKey = String.valueOf(keys.get(2));
		long ttlSeconds = Long.parseLong(String.valueOf(args.get(0)));
		long fallbackLast = Long.parseLong(String.valueOf(args.get(1)));
		int probe = Integer.parseInt(String.valueOf(args.get(2)));

		long cur = this.counters.merge(seqKey, 1L, Long::sum);
		long degraded = 0L;
		if (cur == 1L || cur <= fallbackLast) {
			long anchor = fallbackLast;
			if (this.failTailRead) {
				degraded = 1L;
			}
			else {
				List<Entry> entries = entries(streamKey);
				for (int i = entries.size() - 1; i >= 0 && i >= entries.size() - probe; i--) {
					for (Map.Entry<byte[], byte[]> field : entries.get(i).fields.entrySet()) {
						if (RedisFrameStore.FIELD_SEQ.equals(str(field.getKey()))) {
							anchor = Math.max(anchor, Long.parseLong(str(field.getValue())));
						}
					}
				}
			}
			if (this.failListTailProbe) {
				degraded = 1L;
			}
			else {
				List<String> legacy = this.lists.getOrDefault(legacyKey, List.of());
				if (!legacy.isEmpty()) {
					java.util.regex.Matcher matcher = SEQ_FIELD.matcher(legacy.get(legacy.size() - 1));
					if (matcher.find()) {
						anchor = Math.max(anchor, Long.parseLong(matcher.group(1)));
					}
				}
			}
			if (anchor >= cur) {
				cur = cur + (anchor + 1L - cur);
				this.counters.put(seqKey, cur);
			}
		}
		this.expiries.put(seqKey, Duration.ofSeconds(ttlSeconds));
		return List.of(cur, degraded);
	}

	/** 兼容期 LIST 末帧里的 {@code "seq":<n>}（只读一个 {@code seq} 字段，不反序列化整帧）。 */
	private static final java.util.regex.Pattern SEQ_FIELD =
			java.util.regex.Pattern.compile("\"seq\"\\s*:\\s*(\\d+)");

	private static List<Object> trailingArguments(Object[] raw) {
		List<Object> out = new ArrayList<>();
		for (int i = 2; i < raw.length; i++) {
			if (raw[i] instanceof Object[] packed) {
				out.addAll(List.of(packed));
			}
			else {
				out.add(raw[i]);
			}
		}
		return out;
	}

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
