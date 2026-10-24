package com.example.configmgr.ai.memory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话记忆仓储（{@code chat:mem:*}）的进程内 Redis 替身。
 *
 * <p>与 {@code FakeLeaseRedis} / {@code FakeFrameRedis} 同口径：<b>不为测试起真实 Redis</b>，
 * 建模面 = 产品代码实际发出的命令。本类补上 Cluster 记忆面修复所必需的：
 *
 * <ul>
 * <li><b>{@code RENAME}</b>：源键必须存在；成功时整表覆盖目标键并继承源键 TTL；
 * 记录<b>每一次</b>源/目标键（并发用例据此断言两次保存用了不同 staging）；</li>
 * <li><b>hash slot</b>：逐字复刻 Redis 的 CRC16-XMODEM mod 16384 + hash tag 优先规则；
 * {@link #enableClusterMode()} 打开后，多键脚本与多键命令（本类只有 RENAME）跨槽即抛
 * {@link CrossSlotException}，单键脚本/命令照常；</li>
 * <li><b>{@code STAGE_WRITE} 单键脚本</b>：{@code RPUSH ... + PEXPIRE} 的原子语义替身 ——
 * staging 在写入的同时就带 TTL，finally 的 DEL 再失败也不会留下无 TTL 的孤儿；</li>
 * <li><b>失败注入</b>：{@link #failPush}（staging 写入）、{@link #failExpire}（staging TTL）、
 * {@link #failRename}、{@link #failIndexAdd}、{@link #failDelete} 分别在对应阶段抛错；</li>
 * <li><b>并发注入点</b>：{@link #afterStageWrite} 在 staging 写完后、RENAME 前执行，
 * 用来把两次保存的交错窗口固定下来。</li>
 * </ul>
 *
 * <p><b>兼容旧路径</b>：{@code rightPushAll + expire} 仍被建模（RED 阶段产品代码尚未切到
 * {@code STAGE_WRITE} 时，用例也能跑到断言处而不是卡在替身不识别命令上）。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
final class FakeMemoryRedis {

	static final String STAGE_WRITE_MARK = "STAGE_WRITE";

	/** LIST 键面（tagged final / legacy / staging）。 */
	final Map<String, List<String>> lists = new ConcurrentHashMap<>();

	/** SET 键面（全局会话 id 索引 {@code chat:mem:__ids__}）。 */
	final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

	/** 键 → 最近一次设置的 TTL（RENAME 时随源键搬到目标键）。 */
	final Map<String, Duration> expiries = new ConcurrentHashMap<>();

	/** 每一次成功 RENAME 的源键（并发保存应得到两个不同 staging）。 */
	final List<String> renameSources = new CopyOnWriteArrayList<>();

	/** 每一次模板级 DEL 的键（删除路径应同时清 tagged + legacy）。 */
	final List<String> deletedKeys = new CopyOnWriteArrayList<>();

	/** 最近一次 staging 键（失败路径断言孤儿/TTL 用）。 */
	volatile String lastStageKey;

	/** 置 true = 按原生 Cluster 口径判槽（多键命令跨槽即抛 {@link CrossSlotException}）。 */
	volatile boolean clusterMode;

	/** 置 true = 模拟 staging 写入失败（脚本内 RPUSH）。 */
	volatile boolean failPush;

	/** 置 true = 模拟 staging TTL 失败（脚本内 PEXPIRE）。 */
	volatile boolean failExpire;

	/** 置 true = 模拟 {@code RENAME} 失败。 */
	volatile boolean failRename;

	/** 置 true = 模拟索引 {@code SADD} 失败（RENAME 已成功之后）。 */
	volatile boolean failIndexAdd;

	/** 置 true = 模拟清理/删除 {@code DEL} 失败。 */
	volatile boolean failDelete;

	/** staging 写完、RENAME 之前的并发注入点。 */
	volatile Runnable afterStageWrite;

	final AtomicInteger scriptCalls = new AtomicInteger();

	final AtomicInteger stageWrites = new AtomicInteger();

	final AtomicInteger pushes = new AtomicInteger();

	final AtomicInteger expires = new AtomicInteger();

	final AtomicInteger renames = new AtomicInteger();

	final AtomicInteger deletes = new AtomicInteger();

	final AtomicInteger indexAdds = new AtomicInteger();

	final AtomicInteger indexRemoves = new AtomicInteger();

	volatile String lastRenameSource;

	volatile String lastRenameTarget;

	volatile String lastScriptBody;

	volatile List<String> lastScriptKeys = List.of();

	/** 最近一次模板级 {@code DEL} 的键清单。 */
	volatile List<String> lastDeletedKeys = List.of();

	final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	FakeMemoryRedis() {
		when(this.redis.opsForList()).thenReturn(this.listOps);
		when(this.redis.opsForSet()).thenReturn(this.setOps);

		when(this.listOps.rightPushAll(anyString(), anyCollection())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			this.lastStageKey = key;
			if (this.failPush) {
				throw new IllegalStateException("RPUSH 失败（模拟 Redis 抖动）");
			}
			Collection<String> values = invocation.getArgument(1);
			List<String> list = mutableList(key);
			synchronized (list) {
				list.addAll(values);
			}
			this.pushes.incrementAndGet();
			afterStageWritten();
			return (long) list.size();
		});
		when(this.listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
			List<String> list = this.lists.get((String) invocation.getArgument(0));
			if (list == null || list.isEmpty()) {
				return List.of();
			}
			long start = (Long) invocation.getArgument(1);
			long stop = (Long) invocation.getArgument(2);
			synchronized (list) {
				int size = list.size();
				int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
				int to = (int) (stop < 0 ? size + stop : Math.min(stop, size - 1));
				if (from > to || from >= size) {
					return List.of();
				}
				return new ArrayList<>(list.subList(from, to + 1));
			}
		});

		when(this.redis.delete(anyString())).thenAnswer(invocation -> {
			if (this.failDelete) {
				throw new IllegalStateException("DEL 失败（模拟 Redis 抖动）");
			}
			String key = invocation.getArgument(0);
			this.deletes.incrementAndGet();
			this.deletedKeys.add(key);
			this.lastDeletedKeys = List.of(key);
			return remove(key);
		});
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			if (this.failExpire) {
				throw new IllegalStateException("EXPIRE 失败（模拟 Redis 抖动）");
			}
			this.expiries.put(invocation.getArgument(0), invocation.getArgument(1));
			this.expires.incrementAndGet();
			return true;
		});
		when(this.redis.hasKey(anyString())).thenAnswer(invocation -> hasKey(invocation.getArgument(0)));
		doAnswer(invocation -> {
			String source = invocation.getArgument(0);
			String target = invocation.getArgument(1);
			assertSameSlot(List.of(source, target));
			if (this.failRename) {
				throw new IllegalStateException("RENAME 失败（模拟 Redis 抖动）");
			}
			List<String> sourceList = this.lists.get(source);
			if (sourceList == null) {
				// 真 Redis：ERR no such key（Spring 转成 RedisSystemException）
				throw new RedisSystemException("ERR no such key（RENAME source 不存在：" + source + "）", null);
			}
			List<String> copy;
			synchronized (sourceList) {
				copy = new ArrayList<>(sourceList);
			}
			this.lists.put(target, new CopyOnWriteArrayList<>(copy));
			this.lists.remove(source);
			Duration ttl = this.expiries.remove(source);
			if (ttl != null) {
				this.expiries.put(target, ttl);
			}
			this.renames.incrementAndGet();
			this.renameSources.add(source);
			this.lastRenameSource = source;
			this.lastRenameTarget = target;
			return null;
		}).when(this.redis).rename(anyString(), anyString());

		when(this.setOps.members(anyString())).thenAnswer(invocation -> {
			Set<String> members = this.sets.get(invocation.getArgument(0));
			return members == null ? Set.of() : new LinkedHashSet<>(members);
		});
		when(this.setOps.isMember(anyString(), (Object) any())).thenAnswer(invocation -> {
			Set<String> members = this.sets.get(invocation.getArgument(0));
			return members != null && members.contains(String.valueOf((Object) invocation.getArgument(1)));
		});
		doAnswer(invocation -> {
			if (this.failIndexAdd) {
				throw new IllegalStateException("SADD 失败（模拟 Redis 抖动）");
			}
			Set<String> members = mutableSet(invocation.getArgument(0));
			Object[] values = tail(invocation.getArguments(), 1);
			for (Object value : values) {
				members.add(String.valueOf(value));
			}
			this.indexAdds.incrementAndGet();
			return (long) values.length;
		}).when(this.setOps).add(anyString(), any());
		doAnswer(invocation -> {
			Set<String> members = this.sets.get(invocation.getArgument(0));
			Object[] values = tail(invocation.getArguments(), 1);
			long removed = 0L;
			if (members != null) {
				for (Object value : values) {
					if (members.remove(String.valueOf(value))) {
						removed++;
					}
				}
			}
			this.indexRemoves.incrementAndGet();
			return removed;
		}).when(this.setOps).remove(anyString(), any());

		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
			this.scriptCalls.incrementAndGet();
			List<?> keys = invocation.getArgument(1);
			assertSameSlot(keys);
			String body = ((RedisScript<?>) invocation.getArgument(0)).getScriptAsString();
			this.lastScriptBody = body;
			this.lastScriptKeys = List.of(String.valueOf(keys.get(0)));
			if (body.contains(STAGE_WRITE_MARK)) {
				return stageWrite(String.valueOf(keys.get(0)), tail(invocation.getArguments(), 2));
			}
			throw new UnsupportedOperationException("未建模的脚本：" + body);
		});
	}

	// ── 脚本语义：STAGE_WRITE（单键 RPUSH + PEXPIRE） ─────────────────────

	private Long stageWrite(String key, Object[] args) {
		this.lastStageKey = key;
		if (this.failPush) {
			throw new IllegalStateException("RPUSH 失败（模拟 Redis 抖动）");
		}
		if (this.failExpire) {
			// 真脚本里 RPUSH 与 PEXPIRE 同一条 EVAL；PEXPIRE 参数错误会整条失败。
			// 替身选择“失败即无数据”，用来证明不会留下无 TTL 的孤儿。
			throw new IllegalStateException("PEXPIRE 失败（模拟 Redis 抖动）");
		}
		List<String> values = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			values.add(String.valueOf(args[i]));
		}
		List<String> list = mutableList(key);
		synchronized (list) {
			list.addAll(values);
		}
		long ttlMillis = args.length == 0 ? 0L : Long.parseLong(String.valueOf(args[0]));
		if (ttlMillis > 0L) {
			this.expiries.put(key, Duration.ofMillis(ttlMillis));
			this.expires.incrementAndGet();
		}
		this.pushes.incrementAndGet();
		this.stageWrites.incrementAndGet();
		afterStageWritten();
		return (long) values.size();
	}

	private void afterStageWritten() {
		Runnable hook = this.afterStageWrite;
		if (hook != null) {
			hook.run();
		}
	}

	// ── Cluster 槽口径（与 FakeLeaseRedis 同一算法，独立一份避免测试包互相依赖） ──

	static final int SLOT_COUNT = 16384;

	public static int slot(String key) {
		return crc16(hashTagOf(key)) & (SLOT_COUNT - 1);
	}

	static int crc16(String value) {
		int crc = 0;
		for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
			crc ^= (raw & 0xFF) << 8;
			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
				crc &= 0xFFFF;
			}
		}
		return crc;
	}

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

	void enableClusterMode() {
		this.clusterMode = true;
	}

	/** 多键命令的槽校验：真 Redis Cluster 下 KEYS 不同槽回 {@code CROSSSLOT}。 */
	void assertSameSlot(List<?> keys) {
		if (!this.clusterMode || keys.size() < 2) {
			return;
		}
		int first = slot(String.valueOf(keys.get(0)));
		for (int i = 1; i < keys.size(); i++) {
			int current = slot(String.valueOf(keys.get(i)));
			if (current != first) {
				throw new CrossSlotException("CROSSSLOT Keys in request don't hash to the same slot（"
						+ keys.get(0) + " slot=" + first + " / " + keys.get(i) + " slot=" + current + "）");
			}
		}
	}

	static final class CrossSlotException extends RuntimeException {

		CrossSlotException(String message) {
			super(message);
		}

	}

	// ── 取证辅助 ────────────────────────────────────────────────────────────

	List<String> list(String key) {
		List<String> list = this.lists.get(key);
		return list == null ? List.of() : List.copyOf(list);
	}

	Set<String> set(String key) {
		Set<String> members = this.sets.get(key);
		return members == null ? Set.of() : Set.copyOf(members);
	}

	boolean hasKey(String key) {
		return this.lists.containsKey(key) || this.sets.containsKey(key);
	}

	Duration expiry(String key) {
		return this.expiries.get(key);
	}

	void resetCounters() {
		this.scriptCalls.set(0);
		this.stageWrites.set(0);
		this.pushes.set(0);
		this.expires.set(0);
		this.renames.set(0);
		this.deletes.set(0);
		this.indexAdds.set(0);
		this.indexRemoves.set(0);
		this.renameSources.clear();
		this.deletedKeys.clear();
		this.lastStageKey = null;
		this.lastRenameSource = null;
		this.lastRenameTarget = null;
		this.lastScriptBody = null;
		this.lastScriptKeys = List.of();
		this.lastDeletedKeys = List.of();
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	private List<String> mutableList(String key) {
		return this.lists.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
	}

	private Set<String> mutableSet(String key) {
		return this.sets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
	}

	private boolean remove(String key) {
		boolean existed = this.lists.remove(key) != null;
		existed = this.sets.remove(key) != null || existed;
		this.expiries.remove(key);
		return existed;
	}

	/** Mockito varargs 可能打包成数组，也可能展开成多个实参；从 {@code from} 起摊平。 */
	private static Object[] tail(Object[] raw, int from) {
		List<Object> out = new ArrayList<>();
		for (int i = from; i < raw.length; i++) {
			if (raw[i] instanceof Object[] packed) {
				out.addAll(List.of(packed));
			}
			else {
				out.add(raw[i]);
			}
		}
		return out.toArray();
	}

}
