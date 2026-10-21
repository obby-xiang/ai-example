package com.example.configmgr.ai.run;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 租约（B23L1 批②a）的单测底座 ——<b>不为测试起真实 Redis</b>，与
 * {@code FakeFrameRedis}（批①）同口径：建模面 = 产品代码实际发出的命令。
 *
 * <h2>建模的命令面</h2>
 * <ul>
 * <li>{@code redis.execute(RedisScript, keys, args)} —— <b>按脚本正文里的标记分派</b>
 * （{@code -- LEASE_ACQUIRE} / {@code -- LEASE_RENEW} / {@code -- LEASE_RELEASE} /
 * {@code -- LEASE_FENCE_SET} / {@code -- IDEM_PUSH}），在 Java 侧逐行复刻脚本语义（GET 比对 /
 * INCR 取号 / SET PX / PEXPIRE / DEL / fence 单调 / LLEN+RPUSH+HSETNX）。<b>测试断言的是
 * "脚本语义"，不是"脚本原文"</b>：</li>
 * <li>{@code opsForValue().get/set} 与 {@code opsForHash().get/entries/put/putIfAbsent}
 * —— RunStore 的键面（快照 JSON、待决条目、claim/req/decided/idem 四个 HASH）；</li>
 * <li>{@code opsForList().rightPush/range/index/trim/size} —— 台账 LIST（B23L1 批③a；
 * {@code scriptPushes} 与 {@code templatePushes} 分开计数，"台账与索引同一条 Lua"由此可断言）；</li>
 * <li>{@code EXPIRE}（记录时长 + 截止时刻）、{@code DEL}（记录次数，L7 用它证明"释放不走裸 DEL"）、
 * {@code PING}（{@code leaseOk()} 探针）。</li>
 * </ul>
 *
 * <h2>TTL 到期模拟（批③a）</h2>
 * {@link #advanceTime(Duration)} 推进一个<b>可控时钟</b>并清掉到点的键：租约键到点后别的 owner /
 * 实例可以重新取号（S4 资源锁、批③b S9 的前提）。时钟不随真实时间走 —— 用例不该用 sleep 等 TTL。
 * {@link #nowMs()} 是可观测的当前钟面。</li>
 *
 * <p>
 * <b>M-2 窗口</b>：{@link #afterPendingGet} 是"读到待决条目之后、返回之前"的并发注入点 ——
 * 让用例可以在 {@code markRequestEmitted} 的读与写之间插入一次他方决策（
 * {@code RunStoreRequestEmittedAtomicTest} 用它复现"整条回写覆盖决策"）。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public final class FakeLeaseRedis {

	/** 脚本分派标记（必须与 {@link LeaseService} 脚本正文里的注释一致）。 */
	static final String ACQUIRE_MARK = "LEASE_ACQUIRE";

	static final String RENEW_MARK = "LEASE_RENEW";

	static final String VERIFY_MARK = "LEASE_VERIFY";

	static final String RELEASE_MARK = "LEASE_RELEASE";

	static final String FENCE_MARK = "LEASE_FENCE_SET";

	/** 台账 + 幂等索引的同一原子步（B23L1 批③a）。 */
	static final String IDEM_PUSH_MARK = "IDEM_PUSH";

	/** STRING 键面（快照 JSON、租约现值、全局 epoch 计数）。 */
	final Map<String, String> strings = new ConcurrentHashMap<>();

	/** SET 键面（{@code ai:runs} 索引；deleteRun 的清理面）。 */
	final Map<String, Set<String>> setsByKey = new ConcurrentHashMap<>();

	/** HASH 键面（待决条目、claim、req、decided、idem）。 */
	final Map<String, Map<String, String>> hashTables = new ConcurrentHashMap<>();

	/** LIST 键面（台账 {@code ai:ledger:}；批③a）。 */
	final Map<String, List<String>> lists = new ConcurrentHashMap<>();

	/** 键 → 最近一次设置的 TTL（{@code SET PX} / {@code EXPIRE} 共用）。 */
	final Map<String, Duration> expiries = new ConcurrentHashMap<>();

	/** 键 → 到期时刻（可控时钟口径；{@link #advanceTime(Duration)} 据此清键）。 */
	final Map<String, Long> deadlines = new ConcurrentHashMap<>();

	/**
	 * 可控时钟（毫秒）。<b>不跟随真实时间</b>：TTL 到期由 {@link #advanceTime(Duration)} 显式推进，
	 * 用例因此不必 sleep 真实秒数。
	 */
	final AtomicLong clockMs = new AtomicLong(1_000_000L);

	/** 全局 epoch 计数器（{@code INCR ai:lease:epoch} 的替身）。 */
	final AtomicLong epochCounter = new AtomicLong();

	/** 产品代码走过的 Lua 脚本次数（L7：写路径只能走脚本）。 */
	public final AtomicInteger scriptCalls = new AtomicInteger();

	/** 脚本内 {@code SET} 次数（L3：可重入只能有一次 SET）。 */
	final AtomicInteger sets = new AtomicInteger();

	/** 脚本内 {@code PEXPIRE} 次数（L10：markDriving 的续租次数）。 */
	public final AtomicInteger renewCalls = new AtomicInteger();

	/** 脚本内 {@code DEL} 次数（L6：释放由脚本内的比对 + DEL 完成）。 */
	public final AtomicInteger scriptDeletes = new AtomicInteger();

	/** {@code -- LEASE_VERIFY} 次数（I3：归属校验走一次往返）。 */
	public final AtomicInteger verifyCalls = new AtomicInteger();

	/** {@code opsForValue().get} 次数（"{@link RunStore#touchActiveRun(RunSnapshot)}
	 * 的省一次读"断言用）。 */
	final AtomicInteger valueGets = new AtomicInteger();

	/** 逐键的 {@code opsForValue().get} 次数（用来分辨"读快照"与"读会话索引"）。 */
	final Map<String, Integer> getCounts = new ConcurrentHashMap<>();

	/** 脚本内 {@code RPUSH} 次数（I3：台账落库走同一条 Lua）。 */
	public final AtomicInteger scriptPushes = new AtomicInteger();

	/** 模板级 {@code rightPush} 次数（I3：脚本路径之外不得再推一次 —— 两条命令之间没有窗口）。 */
	public final AtomicInteger templatePushes = new AtomicInteger();

	/** 模板级 {@code HSET} 次数（I3：幂等索引不得在脚本外单独写）。 */
	final AtomicInteger templateHashPuts = new AtomicInteger();

	/** 台账 LIST 的 {@code LRANGE} 次数（I2：索引命中不得回落全表扫描）。 */
	public final AtomicInteger listRangeCalls = new AtomicInteger();

	/** 台账 LIST 的 {@code LINDEX} 次数（I2：索引命中的读口）。 */
	public final AtomicInteger listIndexCalls = new AtomicInteger();

	/**
	 * 最近一次交给 {@code redis.execute} 的脚本<b>原文</b>（I3：脚本内部的
	 * {@code LLEN/RPUSH/HSETNX/PEXPIRE} 组合不能只靠"按标记分派"的语义替身来证明 ——
	 * 替身按标记复刻语义，改坏脚本正文它照样通过）。
	 */
	volatile String lastScriptBody;

	/** 最近一次脚本调用的 KEYS（I3：台账键 + 索引键，顺序即脚本约定）。 */
	volatile List<Object> lastScriptKeys = List.of();

	String lastScriptBody() {
		return this.lastScriptBody;
	}

	List<Object> lastScriptKeys() {
		return this.lastScriptKeys;
	}

	/** 模板级 {@code DEL} 次数（L7 断言 {@code never()}：释放不得走裸 DEL）。 */
	final AtomicInteger templateDeletes = new AtomicInteger();

	/** 置 true = 模拟 Redis 宕机（所有命令抛出，租约降级为进程内互斥）。 */
	volatile boolean down;

	/**
	 * 读 HASH 字段的并发注入点（读到之后、返回之前调用一次）。
	 * 参数 = (hash 键, field)。仅 M-2/R1、R6 两条用例使用。
	 */
	volatile BiConsumer<String, String> afterPendingGet;

	public final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	private final RedisConnection connection = mock(RedisConnection.class);

	public FakeLeaseRedis() {
		when(this.redis.opsForValue()).thenReturn(this.values);
		when(this.redis.opsForHash()).thenReturn(this.hashOps);
		when(this.redis.opsForSet()).thenReturn(this.setOps);
		when(this.redis.opsForList()).thenReturn(this.listOps);
		when(this.listOps.rightPush(anyString(), any())).thenAnswer(invocation -> {
			checkAlive();
			List<String> list = list((String) invocation.getArgument(0));
			synchronized (list) {
				list.add(String.valueOf((Object) invocation.getArgument(1)));
				this.templatePushes.incrementAndGet();
				return (long) list.size();
			}
		});
		when(this.listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
			checkAlive();
			this.listRangeCalls.incrementAndGet();
			return range((String) invocation.getArgument(0), (Long) invocation.getArgument(1),
					(Long) invocation.getArgument(2));
		});
		when(this.listOps.index(anyString(), anyLong())).thenAnswer(invocation -> {
			checkAlive();
			this.listIndexCalls.incrementAndGet();
			List<String> list = list((String) invocation.getArgument(0));
			long index = (Long) invocation.getArgument(1);
			synchronized (list) {
				if (index < 0L) {
					index += list.size();
				}
				return index < 0L || index >= list.size() ? null : list.get((int) index);
			}
		});
		when(this.listOps.size(anyString())).thenAnswer(invocation -> {
			checkAlive();
			return (long) list((String) invocation.getArgument(0)).size();
		});
		doAnswer(invocation -> {
			checkAlive();
			List<String> list = list((String) invocation.getArgument(0));
			synchronized (list) {
				int from = clamp((Long) invocation.getArgument(1), list.size());
				int to = clamp((Long) invocation.getArgument(2), list.size());
				if (from > to) {
					list.clear();
					return null;
				}
				List<String> kept = new ArrayList<>(list.subList(from, to + 1));
				list.clear();
				list.addAll(kept);
				return null;
			}
		}).when(this.listOps).trim(anyString(), anyLong(), anyLong());
		when(this.setOps.remove(anyString(), any(Object[].class))).thenAnswer(invocation -> {
			checkAlive();
			Set<String> members = this.setsByKey.computeIfAbsent((String) invocation.getArgument(0),
					ignored -> ConcurrentHashMap.newKeySet());
			long removed = 0L;
			Object[] raw = invocation.getArguments();
			Object[] values;
			if (raw.length == 2 && raw[1] instanceof Object[] packed) {
				values = packed;
			}
			else {
				values = new Object[raw.length - 1];
				for (int i = 1; i < raw.length; i++) {
					values[i - 1] = raw[i];
				}
			}
			for (Object value : values) {
				if (members.remove(String.valueOf(value))) {
					removed++;
				}
			}
			return removed;
		});
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			touchTtl(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		});
		when(this.redis.hasKey(anyString()))
				.thenAnswer(invocation -> this.strings.containsKey((String) invocation.getArgument(0))
						|| this.hashTables.containsKey((String) invocation.getArgument(0))
						|| this.lists.containsKey((String) invocation.getArgument(0)));
		when(this.redis.delete(anyString())).thenAnswer(invocation -> {
			this.templateDeletes.incrementAndGet();
			return Boolean.TRUE.equals(remove((String) invocation.getArgument(0)));
		});
		when(this.redis.delete(anyList())).thenAnswer(invocation -> {
			this.templateDeletes.incrementAndGet();
			List<String> keys = invocation.getArgument(0);
			long removed = 0L;
			for (String key : keys) {
				if (Boolean.TRUE.equals(remove(key))) {
					removed++;
				}
			}
			return removed;
		});
		when(this.values.get(anyString())).thenAnswer(invocation -> {
			this.valueGets.incrementAndGet();
			this.getCounts.merge((String) invocation.getArgument(0), 1, Integer::sum);
			checkAlive();
			return this.strings.get((String) invocation.getArgument(0));
		});
		doAnswer(invocation -> {
			checkAlive();
			String key = invocation.getArgument(0);
			this.strings.put(key, invocation.getArgument(1));
			touchTtl(key, invocation.getArgument(2));
			return null;
		}).when(this.values).set(anyString(), anyString(), any(Duration.class));
		when(this.hashOps.get(anyString(), any())).thenAnswer(invocation -> {
			checkAlive();
			String key = invocation.getArgument(0);
			String field = String.valueOf((Object) invocation.getArgument(1));
			String raw = table(key).get(field);
			BiConsumer<String, String> hook = this.afterPendingGet;
			if (hook != null && raw != null) {
				hook.accept(key, field);
			}
			return raw;
		});
		when(this.hashOps.entries(anyString())).thenAnswer(invocation -> {
			checkAlive();
			return new LinkedHashMap<Object, Object>(table((String) invocation.getArgument(0)));
		});
		doAnswer(invocation -> {
			checkAlive();
			this.templateHashPuts.incrementAndGet();
			table((String) invocation.getArgument(0)).put(String.valueOf((Object) invocation.getArgument(1)),
					String.valueOf((Object) invocation.getArgument(2)));
			return null;
		}).when(this.hashOps).put(anyString(), any(), any());
		when(this.hashOps.putIfAbsent(anyString(), any(), any())).thenAnswer(invocation -> {
			checkAlive();
			String previous = table((String) invocation.getArgument(0)).putIfAbsent(
					String.valueOf((Object) invocation.getArgument(1)),
					String.valueOf((Object) invocation.getArgument(2)));
			return previous == null;
		});
		when(this.redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
			checkAlive();
			return ((RedisCallback) invocation.getArgument(0)).doInRedis(this.connection);
		});
		when(this.connection.ping()).thenAnswer(invocation -> {
			checkAlive();
			return "PONG";
		});
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenAnswer(invocation -> handleScript(invocation.getArgument(0), invocation.getArgument(1),
						trailingArguments(invocation.getArguments())));
	}

	// ── 脚本语义（逐行复刻 Lua） ────────────────────────────────────────────

	private Object handleScript(RedisScript<?> script, List<?> keys, List<Object> args) {
		this.scriptCalls.incrementAndGet();
		checkAlive();
		String body = script.getScriptAsString();
		this.lastScriptBody = body;
		this.lastScriptKeys = new ArrayList<>(keys);
		if (body.contains(ACQUIRE_MARK)) {
			return acquire(str(keys.get(0)), str(keys.get(1)), str(args.get(0)), Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(RENEW_MARK)) {
			return renew(str(keys.get(0)), str(args.get(0)), Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(VERIFY_MARK)) {
			return verify(str(keys.get(0)), str(args.get(0)));
		}
		if (body.contains(RELEASE_MARK)) {
			return release(str(keys.get(0)), str(args.get(0)));
		}
		if (body.contains(FENCE_MARK)) {
			return fenceSet(str(keys.get(0)), str(keys.get(1)), str(args.get(0)),
					Long.parseLong(str(args.get(1))), Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(IDEM_PUSH_MARK)) {
			return idemPush(str(keys.get(0)), str(keys.get(1)), str(args.get(0)), str(args.get(1)), str(args.get(2)),
					Duration.ofMillis(Long.parseLong(str(args.get(3)))));
		}
		throw new UnsupportedOperationException("未建模的脚本：" + body);
	}

	/**
	 * {@code -- LEASE_ACQUIRE}：GET 现值 → 有则原样返回（Java 侧判 owner 决定重入/占用）→
	 * 无则 {@code INCR} 全局 epoch 后 {@code SET PX}。
	 */
	private String acquire(String key, String epochKey, String owner, Duration ttl) {
		String current = this.strings.get(key);
		if (current != null) {
			// 命中现值（含同主重入）：created=0 —— 本次调用不是创建者，没有释放权
			return current + "|0";
		}
		long epoch = this.epochCounter.incrementAndGet();
		this.strings.put(epochKey, String.valueOf(epoch));
		String value = epoch + "|" + owner;
		this.strings.put(key, value);
		touchTtl(key, ttl);
		this.sets.incrementAndGet();
		// created=1：这一任是本次调用建的
		return value + "|1";
	}

	/** {@code -- LEASE_RENEW}：现值 ≠ 期望值（epoch|owner）⇒ 0；相等 ⇒ PEXPIRE + 1。 */
	private Long renew(String key, String expected, Duration ttl) {
		String current = this.strings.get(key);
		if (current == null || !current.equals(expected)) {
			return 0L;
		}
		touchTtl(key, ttl);
		this.renewCalls.incrementAndGet();
		return 1L;
	}

	/** {@code -- LEASE_VERIFY}：现值 = 期望值（epoch|owner）⇒ 1，否则 0（只读）。 */
	private Long verify(String key, String expected) {
		this.verifyCalls.incrementAndGet();
		String current = this.strings.get(key);
		return current != null && current.equals(expected) ? 1L : 0L;
	}

	/** {@code -- LEASE_RELEASE}：现值 = 期望值（epoch|owner）才 DEL。 */
	private Long release(String key, String expected) {
		String current = this.strings.get(key);
		if (current == null || !current.equals(expected)) {
			return 0L;
		}
		this.strings.remove(key);
		this.scriptDeletes.incrementAndGet();
		return 1L;
	}

	/**
	 * {@code -- LEASE_FENCE_SET}：fence 现值 &gt; 本次 epoch ⇒ 0（拒写，原值不动）；
	 * 否则写快照 + 把 fence 推到本次 epoch（同一 TTL）。
	 */
	private Long fenceSet(String runKey, String fenceKey, String json, long epoch, Duration ttl) {
		String fence = this.strings.get(fenceKey);
		if (fence != null && Long.parseLong(fence) > epoch) {
			return 0L;
		}
		this.strings.put(runKey, json);
		touchTtl(runKey, ttl);
		this.strings.put(fenceKey, String.valueOf(epoch));
		touchTtl(fenceKey, ttl);
		this.sets.incrementAndGet();
		return 1L;
	}

	/**
	 * {@code -- IDEM_PUSH}：{@code LLEN} 现取下标 → {@code RPUSH} 台账 → {@code HSETNX} 索引
	 * （首写者胜：重放不改写"第一次是哪条记录"）→ 两键同 TTL。整段是一个原子步（批③a）。
	 */
	private Long idemPush(String ledgerKey, String idemKey, String json, String field, String tail, Duration ttl) {
		List<String> list = list(ledgerKey);
		long index;
		synchronized (list) {
			index = list.size();
			list.add(json);
		}
		this.scriptPushes.incrementAndGet();
		table(idemKey).putIfAbsent(field, index + "|" + tail);
		touchTtl(ledgerKey, ttl);
		touchTtl(idemKey, ttl);
		return index;
	}

	// ── 取证与语料辅助 ──────────────────────────────────────────────────────

	Map<String, String> table(String key) {
		return this.hashTables.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
	}

	List<String> list(String key) {
		return this.lists.computeIfAbsent(key, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>());
	}

	public String string(String key) {
		return this.strings.get(key);
	}

	Duration expiry(String key) {
		return this.expiries.get(key);
	}

	/** 可控时钟的当前钟面（毫秒；从 1_000_000 起算，不随真实时间走）。 */
	public long nowMs() {
		return this.clockMs.get();
	}

	/**
	 * 推进可控时钟并清掉<b>到点的键</b>（TTL 到期语义：租约键消失 ⇒ 别的 owner 可以重新抢、
	 * 快照/台账/索引过期 ⇒ 读口返回空）。
	 *
	 * <p>只按<b>显式推进入口</b>过期，不跟随真实时间：用例不该用 sleep 等 TTL。
	 */
	public void advanceTime(Duration delta) {
		this.clockMs.addAndGet(Math.max(0L, delta.toMillis()));
		long now = this.clockMs.get();
		for (Map.Entry<String, Long> entry : List.copyOf(this.deadlines.entrySet())) {
			if (entry.getValue() <= now) {
				dropKey(entry.getKey());
			}
		}
	}

	/** 显式让某个键此刻到期（不推进时钟；用于"只有这个键过期"的语料）。 */
	public void expireKey(String key) {
		dropKey(key);
	}

	/** 键上是否还有截止时刻（尚未过期）。 */
	boolean hasDeadline(String key) {
		return this.deadlines.containsKey(key);
	}

	/** 模拟键过期（租约 TTL 到点 / 快照被清）。 */
	public void dropKey(String key) {
		this.strings.remove(key);
		this.hashTables.remove(key);
		this.lists.remove(key);
		this.expiries.remove(key);
		this.deadlines.remove(key);
	}

	/** 直接铺全局 epoch（跨实例语料：让本次抢到的 epoch 从某个高位起算）。 */
	void seedEpoch(long value) {
		this.epochCounter.set(value);
	}

	/**
	 * 让此后所有 Redis 命令都抛出（模拟宕机）。B23L1 批③b S6：资源锁设施不可用时工具必须
	 * <b>放行</b>而非被误判成"有人持有"。
	 */
	public void goDown() {
		this.down = true;
	}

	/** 恢复可用（{@link #goDown()} 的逆操作）。 */
	public void heal() {
		this.down = false;
	}

	int getCount(String key) {
		return this.getCounts.getOrDefault(key, 0);
	}

	void resetCounters() {
		this.scriptCalls.set(0);
		this.sets.set(0);
		this.renewCalls.set(0);
		this.scriptDeletes.set(0);
		this.verifyCalls.set(0);
		this.templateDeletes.set(0);
		this.valueGets.set(0);
		this.getCounts.clear();
		this.scriptPushes.set(0);
		this.templatePushes.set(0);
		this.templateHashPuts.set(0);
		this.listRangeCalls.set(0);
		this.listIndexCalls.set(0);
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/** 记 TTL 的<b>两个</b>面：时长（既有断言读它）与截止时刻（{@link #advanceTime} 读它）。 */
	private void touchTtl(String key, Duration ttl) {
		this.expiries.put(key, ttl);
		this.deadlines.put(key, this.clockMs.get() + Math.max(0L, ttl.toMillis()));
	}

	/** {@code LRANGE} 语义（含负下标）。 */
	private List<String> range(String key, long start, long stop) {
		List<String> list = list(key);
		synchronized (list) {
			int from = clamp(start, list.size());
			int to = clamp(stop, list.size());
			if (from > to || list.isEmpty()) {
				return List.of();
			}
			return new ArrayList<>(list.subList(from, to + 1));
		}
	}

	/** Redis 下标归一：负值从尾部算，越界夹到区间端点上。 */
	private static int clamp(long index, int size) {
		long normalized = index < 0L ? size + index : index;
		if (normalized < 0L) {
			return -1;
		}
		return normalized >= size ? size - 1 : (int) normalized;
	}

	private Boolean remove(String key) {
		boolean existed = this.strings.remove(key) != null;
		existed = this.hashTables.remove(key) != null || existed;
		existed = this.lists.remove(key) != null || existed;
		this.expiries.remove(key);
		this.deadlines.remove(key);
		return existed;
	}

	private void checkAlive() {
		if (this.down) {
			throw new IllegalStateException("Redis 不可用（模拟宕机）");
		}
	}

	/** Mockito 的 varargs 可能展开成多个实参、也可能打包成数组；两种形态都摊平。 */
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

	private static String str(Object value) {
		return String.valueOf(value);
	}

}
