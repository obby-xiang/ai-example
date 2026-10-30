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
 * ID 必须严格大于表尾，否则抛错（产品因此<b>不得</b>改用自动 ID；Task 2 起也不改号 ——
 * 出帧路径用一次脚本把"定号 + XADD"做成同一拍，手工写口被越过就丢帧）；
 * {@code MAXLEN} 从<b>命令实参</b>读（不是替身构造参数），因此"删掉实现的 MAXLEN ⇒ 本替身不裁剪"成立；</li>
 * <li>脚本 {@code AI_FRAME_APPEND}（原子落档：幂等标记 → INCR → 表尾单调保险 → XADD → 标记/续期）；</li>
 * <li>{@code XRANGE key (afterSeq-0 + COUNT n}（ID 区间差量读）与 {@code XREVRANGE key + - COUNT 1}（末号/表尾）；</li>
 * <li>{@code EXPIRE}（只记录值）、{@code LRANGE}（兼容期 LIST 只读）。</li>
 * </ul>
 *
 * <p><b>版本口径 = 目标部署的 Redis 5.0.14，不是开发机上的 6.x/7.x</b>：排他区间（{@code (id}）
 * 是 6.2 才有的语法，5.0 把 {@code (123-0} 当<b>非法 stream ID 直接报错</b>（真实响应
 * {@code ERR Invalid stream ID specified as stream command argument}）。替身照此报错 ——
 * 于是"实现里用了排他下限"在单测里就表现为<b>一次读失败</b>（产品侧若吞掉异常，就是 0 帧），
 * 这正是线上那条路径的形态。
 *
 * <p>替身仍支持 {@code XADD ... *}（自动 ID，毫秒段取固定大值），但<b>只用于铺陈升级期遗留状态</b>：
 * 产品侧的落档不允许走这条路（显式 ID 恒定批），用例据此复现"表尾已被墙钟 ID 越过"的形态。
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

	/**
	 * <b>只让接下来 N 次原子落档的脚本执行"先写入、再抛错"</b>（{@code XADD} 已生效，回包丢失）。
	 *
	 * <p>这是"XADD 成功但回包失败"的唯一可确定性复现形态：真实故障是连接在回包前断掉，
	 * 客户端只看到异常，服务端已经写进去了。命中一次即递减。
	 */
	volatile int failAppendReplyTimes;

	/** 幂等标记（{@code ai:xack:<token>:<tag>} → seq 文本）：{@code 原子落档脚本} 的幂等面。 */
	final Map<String, String> acks = new ConcurrentHashMap<>();

	/** 最近一次原子落档脚本收到的 {@code KEYS}（键形 / 同槽断言用）。 */
	volatile List<String> lastAppendScriptKeys = List.of();

	/** 原子落档脚本的调用次数（"任何生产帧都走原子落档"的判据：退化路径不会计入）。 */
	volatile int atomicAppendCalls;

	/**
	 * 置 true = 回包丢失时<b>顺手清空所有 TTL</b>（模拟"重试前键的 TTL 已经走完/被清"）。
	 *
	 * <p>用来证明幂等命中与"拒绝交出低号"这两条早退路径<b>也</b>续/补 TTL ——
	 * 否则滑动 TTL 会在重试窗口里倒退，归档可能先于会话过期。
	 */
	volatile boolean clearExpiriesOnLostReply;

	/** 置 true = 模拟 {@code XRANGE} 读失败（真实连接层异常，不是"读不到"）。 */
	volatile boolean failRangeRead;

	/** 置 true = 模拟末号探测 {@code XREVRANGE} 读失败。 */
	volatile boolean failTailRead;

	/**
	 * 只让<b>接下来 N 次</b>末号探测失败（{@code <= 0} = 不额外失败）。
	 *
	 * <p>用来区分"瞬时抖动"（下一拍就恢复 ⇒ 取号必须靠重试自我修复）与"持续不可读"
	 * （降级 + 记录 + 下一次取号重试）。命中一次即递减，{@link #failTailRead} 仍可叠加使用。
	 */
	volatile int failTailReadTimes;

	/**
	 * 取号脚本回包的<b>编解码</b>：{@code "long"}（默认，整数回复）/ {@code "string"}（字符串编解码）/
	 * {@code "bytes"}（原始编解码）。真实部署里同一段脚本会因客户端与序列化器不同而回不同类型，
	 * 产品侧必须都认得（评审 I4）。
	 */
	volatile String seqResultCodec = "long";

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
		when(this.connection.xLen(any(byte[].class))).thenAnswer(invocation -> {
			String key = new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8);
			return (long) entries(key).size();
		});
		when(this.connection.xRevRange(any(byte[].class), any(Range.class), any(Limit.class)))
				.thenAnswer(invocation -> {
					if (this.failTailRead) {
						throw new org.springframework.data.redis.RedisConnectionFailureException("XREVRANGE 失败（模拟连接层异常）");
					}
					String key = new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8);
					Range<String> range = invocation.getArgument(1);
					rejectExclusiveBounds(range);
					List<Entry> entries = entries(key);
					String lower = range.getLowerBound().isBounded()
							? range.getLowerBound().getValue().orElse("-") : "-";
					String upper = range.getUpperBound().isBounded()
							? range.getUpperBound().getValue().orElse("+") : "+";
					// XREVRANGE 的边界语义与 XRANGE 相同（只是倒序返回），COUNT 截的是"倒序的前 N 条"。
					List<Entry> matched = new ArrayList<>();
					synchronized (entries) {
						for (Entry entry : entries) {
							if (!"-".equals(lower) && compareIds(entry.id, lower) < 0) {
								continue;
							}
							if (!"+".equals(upper) && compareIds(entry.id, upper) > 0) {
								continue;
							}
							matched.add(entry);
						}
					}
					List<ByteRecord> reversed = new ArrayList<>();
					Limit limit = invocation.getArgument(2);
					for (int i = matched.size() - 1; i >= 0; i--) {
						if (limit.getCount() > 0 && reversed.size() >= limit.getCount()) {
							break;
						}
						reversed.add(record(matched.get(i)));
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
	 * 否则抛错 —— 产品必须据此丢帧/判幂等（<b>不得</b>改用自动 ID，也不改号）；{@code *} 只用于铺设升级期遗留状态。
	 * {@code MAXLEN} 取自命令实参（M-5）。
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
		if (body.contains(RedisFrameStore.APPEND_MARK)) {
			return atomicAppend(keys, args);
		}
		if (body.contains(RedisFrameStore.XTRIM_MARK)) {
			// 只升不降的低水位标记（GET → max → EXPIRE）。
			String key = String.valueOf(keys.get(0));
			long next = Long.parseLong(String.valueOf(args.get(0)));
			long ttl = Long.parseLong(String.valueOf(args.get(1)));
			long known = this.counters.getOrDefault(key, 0L);
			if (next > known) {
				this.counters.put(key, next);
			}
			this.expiries.put(key, Duration.ofSeconds(ttl));
			return List.of(Math.max(next, known));
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

	/**
	 * 取号脚本（见 {@code RedisFrameStore} 的 {@code AI_SEQ_NEXT}）：返回 {@code [seq, degraded]}。
	 *
	 * <p>逐条镜像脚本语义（{@code synchronized} 由 {@link #handleScript} 提供 = Redis 串行执行）：
	 * {@code INCR} → （键刚重建 / 号落后于降级号 / 调用方要求强制修复）读锚点 → 原子抬号 → {@code EXPIRE}
	 * → 「刚重建且锚点读不出」时<b>拒绝交出</b>该低号（返回 {@code {0, degraded}}）。
	 */
	private List<Object> seqNext(List<?> keys, List<Object> args) {
		if (this.failIncrement) {
			throw new IllegalStateException("取号脚本失败（模拟 Redis 抖动）");
		}
		String seqKey = String.valueOf(keys.get(0));
		String streamKey = String.valueOf(keys.get(1));
		String legacyKey = String.valueOf(keys.get(2));
		long ttlSeconds = Long.parseLong(String.valueOf(args.get(0)));
		long fallbackLast = Long.parseLong(String.valueOf(args.get(1)));
		int probe = Integer.parseInt(String.valueOf(args.get(2)));
		boolean forceRepair = args.size() > 3 && "1".equals(String.valueOf(args.get(3)));

		// 真实裁剪检测（与生产脚本逐字一致）：XADD 前后各看一次幸存首条。
		long cur = this.counters.merge(seqKey, 1L, Long::sum);
		long degraded = 0L;
		if (cur == 1L || cur <= fallbackLast || forceRepair) {
			long anchor = fallbackLast;
			if (this.failTailRead || this.failTailReadTimes > 0) {
				if (this.failTailReadTimes > 0) {
					this.failTailReadTimes--;
				}
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
		if (degraded != 0L && cur == 1L) {
			// 刚重建的计数器 + 锚点读不出 ⇒ 这个 1 无法证明未被用过：拒绝交出（调用方重试/降级）。
			return encodeSeqResult(0L, 1L);
		}
		return encodeSeqResult(cur, degraded);
	}

	/** 按 {@link #seqResultCodec} 把脚本回包编码成真实客户端可能给出的三种类型。 */
	private List<Object> encodeSeqResult(long seq, long degraded) {
		if ("string".equals(this.seqResultCodec)) {
			return List.of(String.valueOf(seq), String.valueOf(degraded));
		}
		if ("bytes".equals(this.seqResultCodec)) {
			return List.of(String.valueOf(seq).getBytes(StandardCharsets.UTF_8),
					String.valueOf(degraded).getBytes(StandardCharsets.UTF_8));
		}
		return List.of(seq, degraded);
	}

	/**
	 * 原子落档脚本（{@code RedisFrameStore} 的 {@code AI_FRAME_APPEND}）：返回 {@code [seq, degraded, idempotent]}。
	 *
	 * <p>逐条镜像脚本语义（{@code synchronized} 由 {@link #handleScript} 提供 = Redis 串行执行脚本）：
	 * 幂等标记（token → seq，按<b>精确 ID</b> 读出并<b>逐字节</b>比对本次帧体）→ {@code INCR} →
	 * 表尾单调保险 / 计数器重建重锚 → {@code XADD <seq>-0} → 落标记 → 续期。
	 *
	 * <p>关节行：{@code failAppendReplyTimes} = "已写入、回包丢失"（服务端成功、客户端只看到异常）；
	 * 表尾 ID 的比较按 Redis 语义（显式 ID 必须严格大于表尾）。
	 */
	private List<Object> atomicAppend(List<?> keys, List<Object> args) {
		if (this.failIncrement) {
			throw new IllegalStateException("原子落档脚本失败（模拟 Redis 抖动）");
		}
		String seqKey = String.valueOf(keys.get(0));
		String streamKey = String.valueOf(keys.get(1));
		String legacyKey = String.valueOf(keys.get(2));
		this.atomicAppendCalls++;
		String ackKeyFromScript = String.valueOf(keys.get(3));
		String trimKey = keys.size() > 4 ? String.valueOf(keys.get(4)) : String.valueOf(keys.get(3));
		this.lastAppendScriptKeys = List.of(seqKey, streamKey, legacyKey, ackKeyFromScript);
		long ttlSeconds = Long.parseLong(String.valueOf(args.get(0)));
		long fallbackLast = Long.parseLong(String.valueOf(args.get(1)));
		int probe = Integer.parseInt(String.valueOf(args.get(2)));
		long window = Long.parseLong(String.valueOf(args.get(3)));
		boolean forceRepair = "1".equals(String.valueOf(args.get(4)));
		long ackTtl = Long.parseLong(String.valueOf(args.get(5)));
		String token = String.valueOf(args.get(6));
		String head = String.valueOf(args.get(7));
		String tail = String.valueOf(args.get(8));
		// 与生产脚本逐字一致：KEYS[4] 已是完整键（含 token），脚本不再拼一次。
		String ackKey = ackKeyFromScript;

		String prev = this.acks.get(ackKey);
		if (prev != null) {
			String expected = head + prev + tail;
			Entry exact = exactEntry(streamKey, prev + "-0");
			if (exact != null && expected.equals(str(field(exact, RedisFrameStore.FIELD_FRAME)))) {
				// 与生产脚本一致：幂等命中也要续 ack/seq/stream 三处 TTL。
				this.expiries.put(ackKey, Duration.ofSeconds(ackTtl));
				this.expiries.put(seqKey, Duration.ofSeconds(ttlSeconds));
				this.expiries.put(streamKey, Duration.ofSeconds(ttlSeconds));
				return List.of(Long.parseLong(prev), 0L, 1L);
			}
		}

		// 真实裁剪检测（与生产脚本逐字一致）：XADD 前后各看一次幸存首条。
		List<Entry> entries = entries(streamKey);
		long headBefore = entries.isEmpty() ? -1L : seqFieldOfEntry(entries.get(0));
		long cur = this.counters.merge(seqKey, 1L, Long::sum);
		long degraded = 0L;
		long tailMs = 0L;
		if (this.failTailRead || this.failTailReadTimes > 0) {
			if (this.failTailReadTimes > 0) {
				this.failTailReadTimes--;
			}
			degraded = 1L;
		}
		else if (!entries.isEmpty()) {
			tailMs = parseId(entries.get(entries.size() - 1).id())[0];
		}
		if (cur == 1L || forceRepair) {
			long anchor = Math.max(fallbackLast, tailMs);
			if (degraded == 0L) {
				synchronized (entries) {
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
			}
			if (anchor >= cur) {
				cur = anchor + 1L;
				this.counters.put(seqKey, cur);
			}
		}
		else if (tailMs >= cur) {
			cur = tailMs + 1L;
			this.counters.put(seqKey, cur);
		}
		this.expiries.put(seqKey, Duration.ofSeconds(ttlSeconds));
		if (degraded != 0L && cur == 1L) {
			// 与生产脚本一致：拒绝返回前已经给刚 INCR 出来的键补了 TTL（见上面的 put）。
			return List.of(0L, 1L, 0L);
		}
		if (this.failXadd) {
			throw new IllegalStateException("XADD 失败（模拟 Redis 抖动）");
		}
		String idToken = cur + "-0";
		String body = head + cur + tail;
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		fields.put(RedisFrameStore.FIELD_SEQ.getBytes(StandardCharsets.UTF_8),
				String.valueOf(cur).getBytes(StandardCharsets.UTF_8));
		fields.put(RedisFrameStore.FIELD_FRAME.getBytes(StandardCharsets.UTF_8),
				body.getBytes(StandardCharsets.UTF_8));
		synchronized (entries) {
			if (!entries.isEmpty() && compareIds(idToken, entries.get(entries.size() - 1).id()) <= 0) {
				throw new IllegalStateException(
						"ERR The ID specified in XADD is equal or smaller than the target stream top item");
			}
			entries.add(new Entry(idToken, fields));
			if (window > 0) {
				long limit = window + Math.max(0, this.retainExtra);
				while (entries.size() > limit) {
					entries.remove(0);
				}
			}
		}
		long headAfter = entries(streamKey).isEmpty() ? -1L : seqFieldOfEntry(entries(streamKey).get(0));
		if (headBefore >= 0L && headAfter > headBefore) {
			long known = this.counters.getOrDefault(trimKey, 0L);
			if (headAfter > known) {
				this.counters.put(trimKey, headAfter);
			}
			this.expiries.put(trimKey, Duration.ofSeconds(ttlSeconds));
		}
		this.acks.put(ackKey, String.valueOf(cur));
		this.expiries.put(ackKey, Duration.ofSeconds(ackTtl));
		this.expiries.put(streamKey, Duration.ofSeconds(ttlSeconds));
		if (this.failAppendReplyTimes > 0) {
			this.failAppendReplyTimes--;
			if (this.clearExpiriesOnLostReply) {
				this.expiries.clear();
			}
			throw new IllegalStateException("原子落档回包丢失（模拟连接在回包前断开，XADD 已生效）");
		}
		return List.of(cur, degraded, 0L);
	}

	/** 一条记录的业务 {@code seq} 字段（缺失/坏值回落 ID 毫秒段）。 */
	private static long seqFieldOfEntry(Entry entry) {
		byte[] seq = field(entry, RedisFrameStore.FIELD_SEQ);
		if (seq != null) {
			try {
				return Long.parseLong(str(seq));
			}
			catch (NumberFormatException ignored) {
				// 坏值 ⇒ 视为无法确认
			}
		}
		// 与生产脚本一致：<b>没有/读不出业务 seq 就不确认</b>，绝不回落到 ID 毫秒段。
		return -1L;
	}

	/** 精确 ID 的记录（{@code XRANGE key id id COUNT 1}）；不存在返回 {@code null}。 */
	private Entry exactEntry(String streamKey, String id) {
		List<Entry> entries = entries(streamKey);
		synchronized (entries) {
			for (Entry entry : entries) {
				if (entry.id().equals(id)) {
					return entry;
				}
			}
		}
		return null;
	}

	/** 一条记录里的某个字段值（不存在返回 {@code null}）。 */
	private static byte[] field(Entry entry, String name) {
		for (Map.Entry<byte[], byte[]> item : entry.fields.entrySet()) {
			if (name.equals(str(item.getKey()))) {
				return item.getValue();
			}
		}
		return null;
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
		// ByteRecord 是接口：替身用桩件复刻"读到一条记录"的最小面
		// （getId/getStream/getValue），读取侧只用这三个方法。
		ByteRecord record = mock(ByteRecord.class);
		when(record.getId()).thenReturn(org.springframework.data.redis.connection.stream.RecordId.of(entry.id));
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
