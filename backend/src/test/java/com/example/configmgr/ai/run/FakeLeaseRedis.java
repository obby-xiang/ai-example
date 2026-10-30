package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link #nowMs()} 是可观测的当前钟面。
 *
 * <h2>hash slot 维度（B23L1 批① Cluster 硬阻断）</h2>
 * 真实 Redis 是<b>原生 Cluster</b>（3 主 3 从，16384 槽）：一条 {@code EVAL} 的 KEYS 必须同槽，
 * 否则整条命令回 {@code CROSSSLOT}。单库替身天然看不见这个约束 —— 于是这里<b>逐字复刻</b>
 * 槽算法（CRC16-XMODEM mod 16384 + hash tag 优先），并提供 {@link #enableClusterMode()}：
 * 打开后多键脚本与多键 DEL 一律按真 Redis 口径判槽，跨槽即抛 {@link CrossSlotException}。
 * 默认关闭（既有用例是单节点语料，不该被槽约束改写语义），{@code ClusterSlotTest} 显式打开。
 *
 * <p>
 * <b>M-2 窗口</b>：{@link #afterPendingGet} 是"读到待决条目之后、返回之前"的并发注入点 ——
 * 让用例可以在 {@code markRequestEmitted} 的读与写之间插入一次他方决策（
 * {@code RunStoreRequestEmittedAtomicTest} 用它复现"整条回写覆盖决策"）。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public final class FakeLeaseRedis {

	/** 解析快照 JSON（复刻 Lua 侧 {@code cjson.decode}；只读 status / cancelled 两个字段）。 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 脚本分派标记（必须与 {@link LeaseService} 脚本正文里的注释一致）。 */
	static final String ACQUIRE_MARK = "LEASE_ACQUIRE";

	/** 无 tag 租约的抢租标记（取号在脚本外；与带 tag 的脚本区分）。 */
	static final String ACQUIRE_UNTAGGED_MARK = "LEASE_ACQUIRE_UNTAGGED";

	/** 无 fence 的带 tag 租约（两键）的抢租标记。 */
	static final String ACQUIRE_NOFENCE_MARK = "LEASE_ACQUIRE_NOFENCE";

	static final String RENEW_MARK = "LEASE_RENEW";

	static final String VERIFY_MARK = "LEASE_VERIFY";

	static final String RELEASE_MARK = "LEASE_RELEASE";

	static final String FENCE_MARK = "LEASE_FENCE_SET";

	/**
	 * 取消优先终态 CAS（W-30 跨实例 + 批次2 / AR-05）：明文键 + fence 键两路。
	 *
	 * <p>标记文案与 {@code RunStore} 的 Lua 注释逐字一致；批次2 起守卫覆盖<b>所有非取消状态</b>
	 * （RUNNING / SUSPENDED / DONE / FAILED 一律不得从 CANCELLED 转出），标记因此从
	 * {@code DONE_*} 改名为 {@code TERMINAL_*}（脚本名保留，语义已扩面）。
	 */
	static final String TERMINAL_SET_IF_NOT_CANCELLED_MARK = "TERMINAL_SET_IF_NOT_CANCELLED";

	static final String FENCED_TERMINAL_SET_MARK = "FENCED_TERMINAL_SET";

	/** 恢复路径的<b>条件创建</b>（无租约）：仅当快照键不存在时写。 */
	static final String CREATE_IF_ABSENT_MARK = "RUN_CREATE_IF_ABSENT";

	/** 恢复路径的<b>条件创建</b>（持租）：存在性与 fence 在同一原子步里判。 */
	static final String FENCED_CREATE_IF_ABSENT_MARK = "RUN_FENCED_CREATE_IF_ABSENT";

	/** 台账 + 幂等索引的同一原子步（B23L1 批③a）。 */
	static final String IDEM_PUSH_MARK = "IDEM_PUSH";

	/** 认领回滚的值匹配 CAS（Minor3）。 */
	static final String CLAIM_ROLLBACK_MARK = "CLAIM_ROLLBACK";

	/** 业务活动锚点的单键原子 max + 续期（R1-09 / I-3）。 */
	static final String ACTIVITY_TOUCH_MARK = "ACTIVITY_TOUCH";

	/** session→current 的单键 compare-and-write（MI1）：值仍匹配才 DEL / SET fallback。 */
	static final String SESSION_CURRENT_CAS_DELETE_MARK = "SESSION_CURRENT_CAS_DELETE";

	static final String SESSION_CURRENT_CAS_SET_MARK = "SESSION_CURRENT_CAS_SET";

	/** session→current 的单键 compare-and-PEXPIRE（MI2）：值仍匹配才续期。 */
	static final String SESSION_CURRENT_CAS_PEXPIRE_MARK = "SESSION_CURRENT_CAS_PEXPIRE";

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

	/** 计数器自增的锁（"读-加-写"必须原子：并发用例会真的从两个线程 INCR 同一个键）。 */
	private final Object counterLock = new Object();

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

	/**
	 * 最近一次<b>模板级</b> {@code DEL} 带的键清单（R1-05：证明 deleteRun 是"一条命令带 12 个键"，
	 * 而不是 N 条单键删 —— 单键删在 Cluster 下虽然不报错，但"一轮的键同生同死"就没了）。
	 */
	volatile List<String> lastDeletedKeys = List.of();

	/** 置 true = 模拟 Redis 宕机（所有命令抛出，租约降级为进程内互斥）。 */
	volatile boolean down;

	/**
	 * 置 true = <b>只有续租脚本</b>抛异常（其它命令正常）。
	 *
	 * <p>用于"续租不可用"与"确定丢租（脚本返回 0）"的区分：前者是"不知道租约还在不在"，
	 * 后者是"确实不是自己的了"。两者都要求停止推进，但恢复路径不同（前者下一拍续上即可复工）。
	 */
	volatile boolean renewUnavailable;

	/**
	 * 置 true = 按<b>原生 Cluster</b> 口径判槽：多键脚本与多键 {@code DEL} 的键必须同槽，
	 * 否则抛 {@link CrossSlotException}（真 Redis 回 {@code CROSSSLOT}）。
	 *
	 * <p>默认关闭：既有 1000+ 例都是单节点语料，语义不该被槽约束改写；{@link ClusterSlotTest}
	 * 显式 {@link #enableClusterMode()} 打开，用它证明"同槽改造"不是空断言。
	 */
	volatile boolean clusterMode;

	/** 最近一次脚本调用各键的槽号（诊断/断言用；跨槽用例失败时可直接看差在哪个键）。 */
	volatile List<Integer> lastScriptSlots = List.of();

	/**
	 * 抢租脚本<b>执行前</b>的一次性注入点（I-2 竞争交错的唯一缝）。
	 *
	 * <p>为什么要这个缝：真实集群里"A 的请求在路上、B 已经抢完并写了 fence、A 才落地"只能靠
	 * 调度/网络抖动复现；用例需要<b>确定性地</b>把 B 的完整一轮插进 A 的脚本之前。
	 * 注意它位于 {@code EVAL} 之外 —— 脚本内部的 INCR+SET（new 口径）不会被它切开，
	 * 这正是"取号与占用不可分"要证明的东西。
	 */
	volatile Runnable beforeAcquire;

	/**
	 * 快照写脚本<b>执行前</b>的一次性注入点（恢复"读无 → 创建"与并发创建者的交错缝）。
	 *
	 * <p>为什么需要：真实竞态是"某实例刚恢复/刚建好这一轮"恰好落在另一个实例的
	 * {@code 读无} 与 {@code 写} 之间；用例要<b>确定性</b>地把这次并发创建插进快照写脚本之前。
	 * 它位于 {@code EVAL} 之外 —— 条件创建的"存在性判定 + 写"仍然原子（这正是要证明的东西）。
	 * 只对快照写类脚本生效（{@link #CREATE_IF_ABSENT_MARK} / {@link #FENCED_CREATE_IF_ABSENT_MARK} /
	 * {@link #TERMINAL_SET_IF_NOT_CANCELLED_MARK} / {@link #FENCED_TERMINAL_SET_MARK}），取走即置空。
	 */
	private volatile Runnable beforeSnapshotWrite;

	/** 装配"快照写之前"的一次性交错缝（见 {@link #beforeSnapshotWrite}）。 */
	public void beforeSnapshotWrite(Runnable hook) {
		this.beforeSnapshotWrite = hook;
	}

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
			String key = invocation.getArgument(0);
			Set<String> members = this.setsByKey.computeIfAbsent(key,
					ignored -> ConcurrentHashMap.newKeySet());
			long removed = 0L;
			for (Object value : varargs(invocation.getArguments(), 1)) {
				if (members.remove(String.valueOf(value))) {
					removed++;
				}
			}
			// Redis 语义：SET 的最后一个成员被移除时键自动消失（索引不会留下"空键"）。
			if (members.isEmpty()) {
				this.setsByKey.remove(key);
			}
			return removed;
		});
		when(this.setOps.add(anyString(), any(String[].class))).thenAnswer(invocation -> {
			checkAlive();
			String key = invocation.getArgument(0);
			Set<String> members = this.setsByKey.computeIfAbsent(key,
					ignored -> ConcurrentHashMap.newKeySet());
			long added = 0L;
			for (Object value : varargs(invocation.getArguments(), 1)) {
				if (members.add(String.valueOf(value))) {
					added++;
				}
			}
			return added;
		});
		when(this.setOps.members(anyString())).thenAnswer(invocation -> {
			checkAlive();
			Set<String> members = this.setsByKey.get(invocation.getArgument(0));
			return members == null ? Set.of() : new java.util.LinkedHashSet<>(members);
		});
		when(this.setOps.size(anyString())).thenAnswer(invocation -> {
			checkAlive();
			Set<String> members = this.setsByKey.get(invocation.getArgument(0));
			return members == null ? 0L : (long) members.size();
		});
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			touchTtl(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		});
		when(this.redis.hasKey(anyString()))
				.thenAnswer(invocation -> this.strings.containsKey((String) invocation.getArgument(0))
						|| this.hashTables.containsKey((String) invocation.getArgument(0))
						|| this.lists.containsKey((String) invocation.getArgument(0))
						|| this.setsByKey.containsKey((String) invocation.getArgument(0)));
		when(this.redis.delete(anyString())).thenAnswer(invocation -> {
			this.templateDeletes.incrementAndGet();
			String key = invocation.getArgument(0);
			this.lastDeletedKeys = List.of(key);
			return Boolean.TRUE.equals(remove(key));
		});
		when(this.redis.delete(anyList())).thenAnswer(invocation -> {
			this.templateDeletes.incrementAndGet();
			List<String> keys = invocation.getArgument(0);
			this.lastDeletedKeys = new ArrayList<>(keys);
			assertSameSlot(keys);
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
		// B23L1 批①：epoch 取号<b>外置</b>为单键 INCR（脚本只剩 GET/SET 单键）。替身按真 Redis 的
		// INCR 语义：读旧值 +1，只增不减（{@link #seedEpoch} 铺的高位起点也要被尊重）。
		// INCR 的并发安全（评审 Minor 5）：产品侧的全局取号走这里（资源锁），两个线程同时来必须
		// 拿到不同的号。{@code strings} 是 ConcurrentHashMap，但"读-加-写"不是原子的，故显式加锁。
		when(this.values.increment(anyString())).thenAnswer(invocation -> {
			checkAlive();
			String key = (String) invocation.getArgument(0);
			synchronized (this.counterLock) {
				long next = Math.max(parseOrZero(this.strings.get(key)) + 1L, this.epochCounter.incrementAndGet());
				this.strings.put(key, String.valueOf(next));
				return next;
			}
		});
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
		when(this.hashOps.delete(anyString(), any())).thenAnswer(invocation -> {
			checkAlive();
			return table((String) invocation.getArgument(0)).remove(String.valueOf((Object) invocation.getArgument(1))) == null
					? 0L : 1L;
		});
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

	// ── Cluster 槽口径（B23L1 批①） ──────────────────────────────────────────

	/** Redis Cluster 的槽数（{@code CLUSTER COUNTKEYSINSLOT} 的上界同一常数）。 */
	public static final int SLOT_COUNT = 16384;

	/** 打开 Cluster 口径的槽校验（{@link ClusterSlotTest} 用）。 */
	public void enableClusterMode() {
		this.clusterMode = true;
	}

	/** 关回单节点口径（默认）。 */
	public void disableClusterMode() {
		this.clusterMode = false;
	}

	public boolean clusterMode() {
		return this.clusterMode;
	}

	/**
	 * 键的槽号（<b>真 Redis 口径</b>）：有非空 hash tag 时对 {@code {...}} 内容做 CRC16，
	 * 否则对整键做；取模 16384。
	 *
	 * <p>tag 规则按 Redis 规范：取<b>第一个</b> 左花括号，再取其后的<b>第一个</b> 右花括号，
	 * 两者之间非空才算 tag（左花括号紧跟右花括号、或只有左花括号时，都按整键散列）。
	 */
	public static int slot(String key) {
		return crc16(hashTagOf(key)) & (SLOT_COUNT - 1);
	}

	/** CRC16（CCITT/XMODEM，poly 0x1021、初值 0——Redis 用的就是这一支，不是 MODBUS）。 */
	static int crc16(String value) {
		int crc = 0;
		for (byte raw : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
			crc ^= (raw & 0xFF) << 8;
			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
				crc &= 0xFFFF;
			}
		}
		return crc;
	}

	/** hash tag 内容（无形 tag 返回整键）。 */
	static String hashTagOf(String key) {
		int open = key.indexOf('{');
		if (open < 0) {
			return key;
		}
		int close = key.indexOf('}', open + 1);
		if (close <= open + 1) {
			return key;
		}
		return key.substring(open + 1, close);
	}

	/**
	 * Cluster 口径下的一条命令：KEYS 必须同槽，否则整条回 {@code CROSSSLOT}（真 Redis 服务端拒绝，
	 * 客户端无法替脚本拆槽）。
	 */
	void assertSameSlot(List<?> keys) {
		this.lastScriptSlots = keys.stream().map(k -> slot(str(k))).toList();
		if (!this.clusterMode || this.lastScriptSlots.size() < 2) {
			return;
		}
		int first = this.lastScriptSlots.get(0);
		for (int i = 1; i < this.lastScriptSlots.size(); i++) {
			if (this.lastScriptSlots.get(i) != first) {
				throw new CrossSlotException("CROSSSLOT Keys in request don't hash to the same slot"
						+ "（" + str(keys.get(0)) + " slot=" + first + " / " + str(keys.get(i)) + " slot="
						+ this.lastScriptSlots.get(i) + "）");
			}
		}
	}

	/**
	 * 真 Redis Cluster 的硬错误：一条命令里的 KEYS 落在不同槽。
	 *
	 * <p>单独成型（而不是塞进 {@link IllegalStateException}）是为了让用例能<b>断言"这是跨槽"</b>，
	 * 也为了让产品侧的 catch 归类可被检验（{@code ACQUIRE} 的 catch 会把任何异常折成
	 * {@code UNAVAILABLE}，这正是 R1-01 的静默退化面）。
	 */
	public static final class CrossSlotException extends RuntimeException {

		public CrossSlotException(String message) {
			super(message);
		}

	}

	// ── 脚本语义（逐行复刻 Lua） ────────────────────────────────────────────

	/**
	 * Lua 脚本的语义替身。
	 *
	 * <p><b>synchronized 是刻意的</b>：真实 Redis 的脚本执行为<b>串行</b>语义 —— 抢租脚本里
	 * "GET 命中则返回 / 否则 INCR+SET"这一整段原子执行，两个并发调用不可能都看到空值。
	 * 早期实现没加锁，于是并发用例里两个线程可能各建一任（epoch 不同、owner 相同），
	 * 表现为"两边 created=true"的<b>替身假象</b>（生产不会发生）。这里用同一把锁复刻串行引擎。
	 */
	private synchronized Object handleScript(RedisScript<?> script, List<?> keys, List<Object> args) {
		this.scriptCalls.incrementAndGet();
		checkAlive();
		assertSameSlot(keys);
		String body = script.getScriptAsString();
		this.lastScriptBody = body;
		this.lastScriptKeys = new ArrayList<>(keys);
		if (body.contains(CREATE_IF_ABSENT_MARK) || body.contains(FENCED_CREATE_IF_ABSENT_MARK)
				|| body.contains(TERMINAL_SET_IF_NOT_CANCELLED_MARK) || body.contains(FENCED_TERMINAL_SET_MARK)) {
			runSnapshotWriteHook();
		}
		if (body.contains(ACQUIRE_UNTAGGED_MARK)) {
			runAcquireHook();
			return acquireUntagged(str(keys.get(0)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(ACQUIRE_NOFENCE_MARK)) {
			runAcquireHook();
			return acquireNoFence(str(keys.get(0)), str(keys.get(1)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))),
					Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(ACQUIRE_MARK)) {
			runAcquireHook();
			return acquireFenced(str(keys.get(0)), str(keys.get(1)), str(keys.get(2)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))),
					Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(RENEW_MARK)) {
			if (this.renewUnavailable) {
				throw new IllegalStateException("Redis 续租不可用（模拟）");
			}
			return renew(str(keys.get(0)), str(args.get(0)), Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(VERIFY_MARK)) {
			return verify(str(keys.get(0)), str(args.get(0)));
		}
		if (body.contains(RELEASE_MARK)) {
			return release(str(keys.get(0)), str(args.get(0)));
		}
		if (body.contains(FENCED_CREATE_IF_ABSENT_MARK)) {
			return fencedCreateIfAbsent(str(keys.get(0)), str(keys.get(1)), str(args.get(0)),
					Long.parseLong(str(args.get(1))), Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(CREATE_IF_ABSENT_MARK)) {
			return createIfAbsent(str(keys.get(0)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(FENCED_TERMINAL_SET_MARK)) {
			return fenceTerminalSet(str(keys.get(0)), str(keys.get(1)), str(args.get(0)),
					Long.parseLong(str(args.get(1))), Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(TERMINAL_SET_IF_NOT_CANCELLED_MARK)) {
			return terminalSetIfNotCancelled(str(keys.get(0)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(FENCE_MARK)) {
			return fenceSet(str(keys.get(0)), str(keys.get(1)), str(args.get(0)),
					Long.parseLong(str(args.get(1))), Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(IDEM_PUSH_MARK)) {
			return idemPush(str(keys.get(0)), str(keys.get(1)), str(args.get(0)), str(args.get(1)), str(args.get(2)),
					Duration.ofMillis(Long.parseLong(str(args.get(3)))));
		}
		if (body.contains(CLAIM_ROLLBACK_MARK)) {
			String key = str(keys.get(0));
			String field = str(args.get(0));
			String expected = str(args.get(1));
			if (expected.equals(table(key).get(field))) {
				table(key).remove(field);
				return 1L;
			}
			return 0L;
		}
		if (body.contains(ACTIVITY_TOUCH_MARK)) {
			return activityTouch(str(keys.get(0)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		if (body.contains(SESSION_CURRENT_CAS_DELETE_MARK)) {
			return sessionCurrentCasDelete(str(keys.get(0)), str(args.get(0)));
		}
		if (body.contains(SESSION_CURRENT_CAS_SET_MARK)) {
			return sessionCurrentCasSet(str(keys.get(0)), str(args.get(0)), str(args.get(1)),
					Duration.ofMillis(Long.parseLong(str(args.get(2)))));
		}
		if (body.contains(SESSION_CURRENT_CAS_PEXPIRE_MARK)) {
			return sessionCurrentCasPexpire(str(keys.get(0)), str(args.get(0)),
					Duration.ofMillis(Long.parseLong(str(args.get(1)))));
		}
		throw new UnsupportedOperationException("未建模的脚本：" + body);
	}

	/** 抢租脚本执行前的一次性注入（I-2 的确定性交错缝；单线程用例，取走即置空）。 */
	private void runAcquireHook() {
		Runnable hook = this.beforeAcquire;
		if (hook != null) {
			this.beforeAcquire = null;
			hook.run();
		}
	}

	/** 快照写脚本之前的交错缝（取走即置空，见 {@link #beforeSnapshotWrite}）。 */
	private void runSnapshotWriteHook() {
		Runnable hook = this.beforeSnapshotWrite;
		if (hook != null) {
			this.beforeSnapshotWrite = null;
			hook.run();
		}
	}

	/**
	 * {@code -- RUN_CREATE_IF_ABSENT}：键已存在 ⇒ 0（不写）；否则 {@code SET PX} 并返回 1。
	 *
	 * <p>与真 Redis 同一判据：{@code EXISTS} 与 {@code SET} 在<b>同一个 EVAL</b> 内串行执行，
	 * 因此"读到不存在"与"写入"之间不可能插进并发创建者。
	 */
	private Long createIfAbsent(String runKey, String json, Duration ttl) {
		if (this.strings.containsKey(runKey)) {
			return 0L;
		}
		this.strings.put(runKey, json);
		touchTtl(runKey, ttl);
		this.sets.incrementAndGet();
		return 1L;
	}

	/**
	 * {@code -- RUN_FENCED_CREATE_IF_ABSENT}：存在 ⇒ 0；fence 已越过本次 epoch ⇒ 0；
	 * 否则写快照并把 fence 推到本次 epoch（与 {@link #fenceSet} 同一 fencing 语义）。
	 */
	private Long fencedCreateIfAbsent(String runKey, String fenceKey, String json, long epoch, Duration ttl) {
		if (this.strings.containsKey(runKey)) {
			return 0L;
		}
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
	 * {@code -- LEASE_ACQUIRE}（<b>两键</b>，带 tag 的租约）：GET 租约 → 命中原样返回 →
	 * 否则 {@code INCR} 计数器 + {@code PEXPIRE} 计数器 + {@code SET 租约 PX}。
	 *
	 * <p>取号与占用在<b>同一个同步块</b>里（真实 Redis 的 {@code EVAL} 串行语义），
	 * 于是"晚到者用更小的号占租"这条路径在替身里也不存在 —— 用例因此能断言设计意图，
	 * 而不是替身的巧合。
	 */
	/**
	 * {@code -- LEASE_ACQUIRE}（<b>三键</b>：租约 / 计数器 / fence）：逐行复刻产品的自愈语义
	 * （评审 N-1）—— 有效租约直返并补计数器、陈旧租约被 fence+1 替换、缺键按 0 处理。
	 */
	private String acquireFenced(String key, String epochKey, String fenceKey, String owner, Duration ttl,
			Duration epochTtl) {
		long fence = parseOrZero(this.strings.get(fenceKey));
		String current = this.strings.get(key);
		if (current != null) {
			long currentEpoch = epochOf(current);
			if (currentEpoch >= fence) {
				// 有效租约：原样返回（Java 侧按 owner 判重入/占用），并把计数器补到 ≥ 现值 + 刷新 TTL
				long counter = parseOrZero(this.strings.get(epochKey));
				if (counter < currentEpoch) {
					this.strings.put(epochKey, String.valueOf(currentEpoch));
				}
				touchTtl(epochKey, epochTtl);
				return current + "|0";
			}
			// 陈旧租约（epoch < fence）：落到下面重建 —— 陈旧持有人本来就写不进去
		}
		long epoch = incrCounter(epochKey);
		if (epoch <= fence) {
			epoch = fence + 1;
			this.strings.put(epochKey, String.valueOf(epoch));
		}
		touchTtl(epochKey, epochTtl);
		String value = epoch + "|" + owner;
		this.strings.put(key, value);
		touchTtl(key, ttl);
		this.sets.incrementAndGet();
		return value + "|1";
	}

	/**
	 * {@code -- LEASE_ACQUIRE_NOFENCE}（<b>两键</b>：租约 / 计数器）：同上的"命中即补计数器"，
	 * 但没有 fence 可对账（不自愈）。
	 */
	private String acquireNoFence(String key, String epochKey, String owner, Duration ttl, Duration epochTtl) {
		String current = this.strings.get(key);
		if (current != null) {
			long currentEpoch = epochOf(current);
			long counter = parseOrZero(this.strings.get(epochKey));
			if (counter < currentEpoch) {
				this.strings.put(epochKey, String.valueOf(currentEpoch));
			}
			touchTtl(epochKey, epochTtl);
			return current + "|0";
		}
		long epoch = incrCounter(epochKey);
		touchTtl(epochKey, epochTtl);
		String value = epoch + "|" + owner;
		this.strings.put(key, value);
		touchTtl(key, ttl);
		this.sets.incrementAndGet();
		return value + "|1";
	}

	/** 租约值里的 epoch 段（坏值按 0 —— 与 Lua 侧 {@code tonumber(...) or 0} 同口径）。 */
	private static long epochOf(String leaseValue) {
		int sep = leaseValue.indexOf('|');
		return sep <= 0 ? 0L : parseOrZero(leaseValue.substring(0, sep));
	}

	/**
	 * {@code -- LEASE_ACQUIRE_UNTAGGED}（<b>单键</b>，无 tag 的资源锁）：GET → SET，值由 Java 侧取号传入。
	 */
	private String acquireUntagged(String key, String value, Duration ttl) {
		String current = this.strings.get(key);
		if (current != null) {
			return current + "|0";
		}
		this.strings.put(key, value);
		touchTtl(key, ttl);
		this.sets.incrementAndGet();
		return value + "|1";
	}

	/** 计数器自增（{@code INCR} 语义：读旧值 +1；已由 {@link #handleScript} 的锁串行化）。 */
	private long incrCounter(String key) {
		synchronized (this.counterLock) {
			long next = parseOrZero(this.strings.get(key)) + 1L;
			this.strings.put(key, String.valueOf(next));
			return next;
		}
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
	 * {@code -- FENCED_TERMINAL_SET}：fence 校验（与 {@link #fenceSet} 逐字相同）+ 当前快照是取消态 ⇒ 拒写。
	 * 与 Lua 一样，先判 fence 再解当前 JSON。
	 */
	private Long fenceTerminalSet(String runKey, String fenceKey, String json, long epoch, Duration ttl) {
		String fence = this.strings.get(fenceKey);
		if (fence != null && Long.parseLong(fence) > epoch) {
			return 0L;
		}
		if (cancelledSnapshot(this.strings.get(runKey))) {
			return 0L;
		}
		return fenceSet(runKey, fenceKey, json, epoch, ttl);
	}

	/** {@code -- TERMINAL_SET_IF_NOT_CANCELLED}：当前快照是取消态 ⇒ 拒写，否则普通 SET PX。 */
	private Long terminalSetIfNotCancelled(String runKey, String json, Duration ttl) {
		if (cancelledSnapshot(this.strings.get(runKey))) {
			return 0L;
		}
		this.strings.put(runKey, json);
		touchTtl(runKey, ttl);
		this.sets.incrementAndGet();
		return 1L;
	}

	/** Lua 侧 {@code cjson.decode} 的替身：解不开的旧值不冒充取消态（与脚本的 pcall 口径一致）。 */
	private static boolean cancelledSnapshot(String json) {
		if (json == null) {
			return false;
		}
		try {
			JsonNode snapshot = MAPPER.readTree(json);
			return snapshot != null && ("CANCELLED".equals(snapshot.path("status").asText())
					|| snapshot.path("cancelled").asBoolean(false));
		}
		catch (Exception ex) {
			return false;
		}
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

	/**
	 * {@code -- ACTIVITY_TOUCH}：单键原子 max + 续期。
	 *
	 * <p>复刻产品 Lua 的关键语义：<b>坏值按 0 处理并覆盖</b>（不是永久失效）；旧帧不回拨；
	 * 只在键存在时续期（真实 {@code PEXPIRE} 对不存在的键是 no-op）。
	 */
	private Long activityTouch(String key, String rawStamp, Duration ttl) {
		long incoming;
		try {
			incoming = Long.parseLong(rawStamp);
		}
		catch (NumberFormatException ex) {
			incoming = 0L;
		}
		long current;
		try {
			String raw = this.strings.get(key);
			current = raw == null ? 0L : Long.parseLong(raw);
		}
		catch (NumberFormatException ex) {
			current = 0L;
		}
		if (incoming > current) {
			this.strings.put(key, rawStamp);
		}
		if (this.strings.containsKey(key)) {
			touchTtl(key, ttl);
		}
		return 1L;
	}

	/** {@code -- SESSION_CURRENT_CAS_DELETE}：值 ≠ 期望 runId ⇒ 0；相等才 DEL。 */
	private Long sessionCurrentCasDelete(String key, String expectedRunId) {
		if (!expectedRunId.equals(this.strings.get(key))) {
			return 0L;
		}
		this.strings.remove(key);
		return 1L;
	}

	/** {@code -- SESSION_CURRENT_CAS_SET}：值 ≠ 期望 runId ⇒ 0；相等才 {@code SET ... PX}。 */
	private Long sessionCurrentCasSet(String key, String expectedRunId, String value, Duration ttl) {
		if (!expectedRunId.equals(this.strings.get(key))) {
			return 0L;
		}
		this.strings.put(key, value);
		touchTtl(key, ttl);
		return 1L;
	}

	/** {@code -- SESSION_CURRENT_CAS_PEXPIRE}：值 ≠ 期望 runId ⇒ 0；相等才续期。 */
	private Long sessionCurrentCasPexpire(String key, String expectedRunId, Duration ttl) {
		if (!expectedRunId.equals(this.strings.get(key))) {
			return 0L;
		}
		touchTtl(key, ttl);
		return 1L;
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
		this.setsByKey.remove(key);
		this.expiries.remove(key);
		this.deadlines.remove(key);
	}

	/** 直接铺全局 epoch（跨实例语料：让本次抢到的 epoch 从某个高位起算）。 */
	void seedEpoch(long value) {
		this.epochCounter.set(value);
	}

	/** 铺某个<b>per-run 计数器</b>的当前值（新口径：{@code ai:lease:epoch:{run:<runId>}}）。 */
	void seedEpoch(String epochKey, long value) {
		this.strings.put(epochKey, String.valueOf(value));
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

	/** 让续租脚本单独失败（见 {@link #renewUnavailable}）；{@code false} = 恢复。 */
	public void failRenew(boolean fail) {
		this.renewUnavailable = fail;
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
		existed = this.setsByKey.remove(key) != null || existed;
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

	/** 同上，但从第 {@code from} 个实参起摊平（SET 的 {@code add/remove} 在 key 之后）。 */
	private static List<Object> varargs(Object[] raw, int from) {
		List<Object> out = new ArrayList<>();
		for (int i = from; i < raw.length; i++) {
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

	/** 数字串 → long（缺失/坏值按 0，{@code INCR} 的起点口径）。 */
	private static long parseOrZero(String raw) {
		if (raw == null) {
			return 0L;
		}
		try {
			return Long.parseLong(raw);
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

}
