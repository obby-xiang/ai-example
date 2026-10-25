package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>会话锁的所有权边界：续期 / 释放 / 同 run 接管必须是单键原子 compare-and-write</b>。
 *
 * <h2>缺陷形态（park 批暴露）</h2>
 * <ul>
 * <li>park 时首轮任务结束、释放会话门；决策后 {@code ResumeService} 用<b>同一个 runId</b>
 * 重新 acquire。旧实现只按 runId 释放：首轮任务那条迟到的 {@code finally}（或上一次恢复任务）
 * 会把<b>新持有者的锁删掉</b>、并 cancel 新持有者的 watchdog —— 恢复执行在"无锁"状态下跑到终态，
 * 同会话可并发第二轮；</li>
 * <li>更隐蔽的是 <b>GET → 写</b> 之间的窗口：{@code release} 是 GET 后 DELETE、{@code renew}
 * 是 GET 后 EXPIRE、同 run 接管是 GET 后 plain SET。检查与写入之间锁一旦换主，旧实现就会
 * <b>删掉 / 续期 / 覆盖别人的锁</b>，而它自己还以为"操作的是自己的锁"。</li>
 * </ul>
 *
 * <h2>本批口径（I2 收口）</h2>
 * <ul>
 * <li>每条路径都是<b>单键 Lua compare-and-write</b>：锁值与期望 token 不一致（含键不存在）
 * ⇒ 不写、返回失败；</li>
 * <li>{@code acquire} 同 run 接管 = 同一脚本内"当前值属于本 run（{@code runId} 或
 * {@code runId|*}）则改写，否则拒绝"；</li>
 * <li>强释放（判死）只允许按该 runId 匹配（含 {@code runId|token} 形态）；</li>
 * <li>Redis 异常 / 结果不可知 ⇒ <b>不</b> plain SET 兜底、<b>不</b>宣称续期/接管成功；</li>
 * <li>脚本只带<b>一个</b> KEYS（Cluster 单槽约束）。</li>
 * </ul>
 *
 * <h2>替身口径</h2>
 * {@link FakeSessionRedis} 建模面 = 产品代码实际发出的命令：脚本按正文标记分派并在 Java 侧
 * <b>串行</b>复刻语义（与 {@code FakeLeaseRedis} 同口径），另外保留 GET/SET/SETNX/DEL/EXPIRE，
 * 用于证明"旧实现的裸命令路径会被换主窗口打穿"以及"新实现不再走裸命令"。
 * {@link FakeSessionRedis#swapOnNextAccess(String)} 是确定性的换主注入点：
 * 对 {@code GET} 在<b>返回之后</b>换主（复现"检查通过、写入已过期"的窗口），
 * 对脚本在<b>执行之前</b>换主（复刻"原子 op 前一刻被别人接管"）。
 */
class SessionGateLeaseTokenTest {

	private static final String SESSION = "s-token";

	private static final String LOCK_KEY = SessionGate.LOCK_PREFIX + SESSION;

	private static final String RUN_ID = "run-token";

	private static final String OTHER_RUN = "run-other";

	private static final String OTHER_VALUE = OTHER_RUN + "|token-other";

	private final FakeSessionRedis fake = new FakeSessionRedis();

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = new SessionGate(this.fake.template, this.properties);

	// ── 基本语义（token 精确匹配） ───────────────────────────────────────────

	/** 旧持有者（首轮 token=runId）的迟到 finally 不得删掉恢复方的新门。 */
	@Test
	void staleReleaseDoesNotDeleteTheResumedHoldersLock() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-old")).isNull();
		assertThat(this.gate.release(SESSION, RUN_ID, "token-old")).isTrue();
		assertThat(this.fake.value(LOCK_KEY)).isNull();

		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-resume")).isNull();
		String heldValue = this.fake.value(LOCK_KEY);
		assertThat(heldValue).isEqualTo(RUN_ID + "|token-resume");

		assertThat(this.gate.release(SESSION, RUN_ID, "token-old"))
			.as("旧 token 的迟到释放不得删新持有者的锁").isFalse();
		assertThat(this.fake.value(LOCK_KEY)).isEqualTo(heldValue);
		assertThat(this.gate.currentRunId(SESSION)).isEqualTo(RUN_ID);

		assertThat(this.gate.release(SESSION, RUN_ID, "token-resume")).isTrue();
		assertThat(this.fake.value(LOCK_KEY)).isNull();
		assertThat(this.fake.plainWrites()).as("释放/续期/接管一律走脚本，不用裸 DEL/SET/EXPIRE")
			.isZero();
	}

	/** 同一 runId 用新 token 再取门 = 接管（重入），锁值换成新 token（单条脚本内完成）。 */
	@Test
	void reentrantAcquireWithANewTokenTakesOverTheLockAtomically() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-first")).isNull();
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-resume")).as("同一轮重入 ⇒ 已持有").isNull();

		assertThat(this.fake.value(LOCK_KEY)).contains("token-resume");
		assertThat(this.fake.ttlRemainingMs(LOCK_KEY))
			.as("接管同时刷新 TTL（脚本内 SET PX）").isEqualTo(this.properties.getSession().getLock().getTtl().toMillis());
		assertThat(this.gate.release(SESSION, RUN_ID, "token-first")).isFalse();
		assertThat(this.gate.currentRunId(SESSION)).isEqualTo(RUN_ID);
		assertThat(this.gate.release(SESSION, RUN_ID, "token-resume")).isTrue();
		assertThat(this.fake.scripts).contains(FakeSessionRedis.ACQUIRE_MARK, FakeSessionRedis.RELEASE_MARK);
		assertThat(this.fake.plainWrites()).isZero();
	}

	/** watchdog 续期前同值校验：锁已换主就不续（否则在替别人保锁）。 */
	@Test
	void watchdogRenewalRequiresTheSameToken() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-mine")).isNull();
		String mine = this.fake.value(LOCK_KEY);
		this.fake.advance(Duration.ofSeconds(5));

		assertThat(this.gate.renewLockOnce(SESSION, mine)).as("自己还持锁 ⇒ 续期").isTrue();
		assertThat(this.fake.ttlRemainingMs(LOCK_KEY))
			.isEqualTo(this.properties.getSession().getLock().getTtl().toMillis());

		// 锁被新持有者接管（模拟另一实例/续跑重新 acquire）
		this.fake.values.put(LOCK_KEY, OTHER_VALUE);
		this.fake.expireAtMs.remove(LOCK_KEY);

		assertThat(this.gate.renewLockOnce(SESSION, mine)).as("锁已换主 ⇒ 不得续期").isFalse();
		assertThat(this.fake.expireAtMs).as("没有做任何 EXPIRE").doesNotContainKey(LOCK_KEY);
		assertThat(this.fake.plainWrites()).isZero();
	}

	/** 不同 runId 仍然互斥（token 机制不放松会话串行化）。 */
	@Test
	void anotherRunIsStillBusy() {
		assertThat(this.gate.acquire(SESSION, "run-a", "token-a")).isNull();

		SessionGate.Busy busy = this.gate.acquire(SESSION, "run-b", "token-b");

		assertThat(busy).isNotNull();
		assertThat(busy.runId()).isEqualTo("run-a");
		assertThat(this.fake.value(LOCK_KEY)).isEqualTo("run-a|token-a");
	}

	// ── 换主窗口（检查与写入之间）──────────────────────────────────────────

	/** renew：GET 之后锁被换主，旧实现会 EXPIRE 别人的锁并宣称续期成功。 */
	@Test
	void renewCannotExtendAnotherRunsLockWhenOwnershipChangesMidFlight() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-mine")).isNull();
		String mine = this.fake.value(LOCK_KEY);
		this.fake.swapOnNextAccess(OTHER_VALUE);

		boolean renewed = this.gate.renewLockOnce(SESSION, mine);

		assertThat(renewed).as("锁已换主 ⇒ 续期必须失败（不得宣称续期成功）").isFalse();
		assertThat(this.fake.value(LOCK_KEY)).isEqualTo(OTHER_VALUE);
		assertThat(this.fake.expireAtMs).as("不得替别人的锁续期").doesNotContainKey(LOCK_KEY);
		assertThat(this.fake.plainWrites()).isZero();
	}

	/** release：GET 之后锁被换主，旧实现会 DEL 掉别人的锁并宣布释放成功。 */
	@Test
	void releaseCannotDeleteAnotherRunsLockWhenOwnershipChangesMidFlight() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-mine")).isNull();
		this.fake.swapOnNextAccess(OTHER_VALUE);

		boolean released = this.gate.release(SESSION, RUN_ID, "token-mine");

		// 返回值口径是"进程内门 <b>或</b> Redis 锁任一"：本进程确实摘掉了自己的 holder，
		// 所以为 true；关键不在这里，而在 Redis 侧 —— 别人的锁必须原样保留、且没有裸 DEL。
		assertThat(released).as("进程内门确实摘掉了（返回值是 OR 口径）").isTrue();
		assertThat(this.fake.value(LOCK_KEY)).as("别人的锁必须还在（脚本拒绝删除）").isEqualTo(OTHER_VALUE);
		assertThat(this.fake.plainWrites()).isZero();
		assertThat(this.gate.currentRunId(SESSION)).as("进程内已释放，视图回落到 Redis 真值")
			.isEqualTo(OTHER_RUN);
	}

	/** 同 run 接管：GET 之后锁被另一轮拿走，旧实现会 plain SET 覆盖对方的锁。 */
	@Test
	void sameRunTakeoverCannotOverwriteAnotherRunsLockWhenOwnershipChangesMidFlight() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-first")).isNull();
		this.fake.swapOnNextAccess(OTHER_VALUE);

		SessionGate.Busy busy = this.gate.acquire(SESSION, RUN_ID, "token-resume");

		assertThat(busy).as("锁已被另一轮拿走 ⇒ 不得宣称接管成功").isNotNull();
		assertThat(busy.runId()).isEqualTo(OTHER_RUN);
		assertThat(this.fake.value(LOCK_KEY)).as("对方锁值必须原样保留").isEqualTo(OTHER_VALUE);
		assertThat(this.fake.plainWrites()).isZero();
	}

	/** 判死强释放：GET 之后锁被换主，旧实现会 DEL 掉别人的锁。 */
	@Test
	void deadReleaseCannotDeleteAnotherRunsLockWhenOwnershipChangesMidFlight() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-mine")).isNull();
		this.fake.swapOnNextAccess(OTHER_VALUE);

		this.gate.releaseDeadHeld(SESSION, RUN_ID);

		assertThat(this.fake.value(LOCK_KEY)).as("强释放只允许按本 runId 匹配，别人的锁不得动")
			.isEqualTo(OTHER_VALUE);
		assertThat(this.fake.plainWrites()).isZero();
	}

	// ── 判死强释放的匹配面 ──────────────────────────────────────────────────

	/** 强释放必须同时认 {@code runId}（首轮 token=runId）与 {@code runId|token}（续跑 token）两种形态。 */
	@Test
	void deadReleaseMatchesBothBareRunIdAndRunIdTokenForms() {
		assertThat(this.gate.acquire(SESSION, RUN_ID)).isNull();
		assertThat(this.fake.value(LOCK_KEY)).isEqualTo(RUN_ID);
		this.gate.releaseDeadHeld(SESSION, RUN_ID);
		assertThat(this.fake.value(LOCK_KEY)).isNull();

		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-resume")).isNull();
		assertThat(this.fake.value(LOCK_KEY)).isEqualTo(RUN_ID + "|token-resume");
		this.gate.releaseDeadHeld(SESSION, RUN_ID);
		assertThat(this.fake.value(LOCK_KEY)).isNull();
		assertThat(this.fake.plainWrites()).isZero();
	}

	/** 强释放不得碰另一轮的锁（含"前缀相同但分隔符不同"的伪匹配 {@code run-token-other}）。 */
	@Test
	void deadReleaseIgnoresAnotherRun() {
		this.fake.values.put(LOCK_KEY, OTHER_VALUE);

		this.gate.releaseDeadHeld(SESSION, RUN_ID);

		assertThat(this.fake.value(LOCK_KEY)).isEqualTo(OTHER_VALUE);
	}

	// ── Cluster 单键脚本 ────────────────────────────────────────────────────

	/** 五条路径（取门 / 同 run 接管 / 续期 / 释放 / 强释放）都只带一个 KEYS（Cluster 单槽）。 */
	@Test
	void allLockScriptsUseExactlyOneKey() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-first")).isNull();
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-resume")).isNull();
		assertThat(this.gate.renewLockOnce(SESSION, RUN_ID + "|token-resume")).isTrue();
		assertThat(this.gate.release(SESSION, RUN_ID, "token-resume")).isTrue();

		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-second")).isNull();
		this.gate.releaseDeadHeld(SESSION, RUN_ID);

		assertThat(this.fake.keyCounts).as("单键脚本：任何一次 EVAL 都只带 1 个 key").containsOnly(1);
		assertThat(this.fake.scripts).contains(FakeSessionRedis.ACQUIRE_MARK, FakeSessionRedis.RENEW_MARK,
				FakeSessionRedis.RELEASE_MARK, FakeSessionRedis.RELEASE_DEAD_MARK);
		assertThat(this.fake.lastKeys).containsExactly(LOCK_KEY);
	}

	// ── 失败/不可知：不得 plain SET 兜底，不得宣称成功 ────────────────────────

	/** Redis 异常 ⇒ 只保留进程内互斥（degraded），不写裸 SET、不宣称续期/释放成功。 */
	@Test
	void redisFailureNeverFallsBackToPlainWritesOrClaimsOwnership() {
		this.fake.failScripts = true;

		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-a")).isNull();
		assertThat(this.fake.value(LOCK_KEY)).as("异常时不得 plain SET 兜底").isNull();
		assertThat(this.fake.plainWrites()).isZero();
		SessionGate.Busy busy = this.gate.acquire(SESSION, OTHER_RUN, "token-b");
		assertThat(busy).isNotNull();
		assertThat(busy.runId()).isEqualTo(RUN_ID);
		assertThat(busy.degraded()).as("Redis 侧未取得锁 ⇒ 降级事实必须如实上报").isTrue();

		assertThat(this.gate.release(SESSION, RUN_ID, "token-a")).isTrue();
		assertThat(this.fake.plainWrites()).as("释放失败也不得裸 DEL").isZero();
		assertThatThrownBy(() -> this.gate.renewLockOnce(SESSION, RUN_ID + "|token-a"))
			.as("续期不可知 ⇒ 抛给 watchdog，由它按降级处理（不得返回成功）")
			.isInstanceOf(IllegalStateException.class);
	}

	/** 脚本结果不可知（null）⇒ 与异常同口径：不宣称取门/续期/释放成功。 */
	@Test
	void unknownScriptResultIsNotTreatedAsAcquiredOrRenewed() {
		this.fake.nullScripts = true;

		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-a")).isNull();
		assertThat(this.fake.value(LOCK_KEY)).isNull();
		assertThat(this.gate.acquire(SESSION, OTHER_RUN, "token-b").degraded()).isTrue();

		assertThat(this.gate.renewLockOnce(SESSION, RUN_ID + "|token-a")).isFalse();
		assertThat(this.gate.release(SESSION, RUN_ID, "token-a")).isTrue();
		this.gate.releaseDeadHeld(SESSION, RUN_ID);
		assertThat(this.fake.plainWrites()).isZero();
	}

	/**
	 * 接管结果不可知：<b>不</b>宣称 Redis 接管成功（不写兜底、不重启 watchdog），
	 * 但本地 token 必须跟上 —— 否则调用方持新 token 释放时摘不掉本地门（会话被永久占住）；
	 * 释放则按<b>已确认</b>的锁值走脚本，不能把锁白留到 TTL。
	 */
	@Test
	void unknownTakeoverKeepsLocalReleaseWorkingWithoutPretendingTheRedisWriteHappened() {
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-first")).isNull();
		String confirmed = this.fake.value(LOCK_KEY);
		assertThat(confirmed).isEqualTo(RUN_ID + "|token-first");

		this.fake.nullScripts = true;
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-resume"))
			.as("结果不可知 ⇒ 按已持有处理（进程内门），但不宣称 Redis 接管成功").isNull();
		this.fake.nullScripts = false;
		assertThat(this.fake.value(LOCK_KEY)).as("不得写兜底 SET").isEqualTo(confirmed);

		assertThat(this.gate.release(SESSION, RUN_ID, "token-resume")).isTrue();
		assertThat(this.fake.value(LOCK_KEY)).as("按已确认的锁值释放（脚本验证），不把锁白留到 TTL").isNull();
		assertThat(this.fake.plainWrites()).isZero();
	}

	// ── 替身 ────────────────────────────────────────────────────────────────

	/**
	 * 会话锁的最小 Redis 替身：脚本按正文标记分派并<b>串行</b>复刻语义（GET 比对 → SET PX /
	 * PEXPIRE / DEL），同时保留裸命令以证明旧实现的窗口。
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static final class FakeSessionRedis {

		static final String ACQUIRE_MARK = "SESSION_LOCK_ACQUIRE";

		static final String RENEW_MARK = "SESSION_LOCK_RENEW";

		static final String RELEASE_MARK = "SESSION_LOCK_RELEASE";

		static final String RELEASE_DEAD_MARK = "SESSION_LOCK_RELEASE_DEAD";

		final Map<String, String> values = new ConcurrentHashMap<>();

		final Map<String, Long> expireAtMs = new ConcurrentHashMap<>();

		/** 每次脚本调用的标记（按调用顺序）与 KEYS 数量（Cluster 单槽断言）。 */
		final List<String> scripts = new ArrayList<>();

		final List<Integer> keyCounts = new ArrayList<>();

		List<String> lastKeys = List.of();

		int bareSets;

		int bareDeletes;

		int bareExpires;

		boolean failScripts;

		boolean nullScripts;

		private String swapOnNextAccess;

		private long nowMs = 1_000_000L;

		final StringRedisTemplate template;

		FakeSessionRedis() {
			this.template = mock(StringRedisTemplate.class);
			ValueOperations<String, String> ops = mock(ValueOperations.class);
			when(this.template.opsForValue()).thenReturn(ops);
			when(ops.get(anyString())).thenAnswer(invocation -> {
				String key = invocation.getArgument(0);
				String value = liveValue(key);
				// 旧实现：读完就检查通过；这里在返回值之后换主，复现"检查与写入之间已过期"
				applySwap();
				return value;
			});
			doAnswer(invocation -> {
				this.bareSets++;
				put(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
				return null;
			}).when(ops).set(anyString(), anyString(), any(Duration.class));
			when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
				this.bareSets++;
				String key = invocation.getArgument(0);
				if (liveValue(key) != null) {
					return false;
				}
				put(key, invocation.getArgument(1), invocation.getArgument(2));
				return true;
			});
			when(this.template.delete(anyString())).thenAnswer(invocation -> {
				this.bareDeletes++;
				String key = invocation.getArgument(0);
				return this.values.remove(key) != null;
			});
			when(this.template.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
				this.bareExpires++;
				String key = invocation.getArgument(0);
				if (liveValue(key) == null) {
					return false;
				}
				this.expireAtMs.put(key, this.nowMs + invocation.getArgument(1, Duration.class).toMillis());
				return true;
			});
			when(this.template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenAnswer(invocation -> handleScript(invocation.getArgument(0), invocation.getArgument(1),
						trailingArguments(invocation.getArguments())));
		}

		/** 确定性换主：下一次访问（GET 之后 / 脚本执行之前）把锁值换成 {@code value}。 */
		void swapOnNextAccess(String value) {
			this.swapOnNextAccess = value;
		}

		void advance(Duration duration) {
			this.nowMs += duration.toMillis();
		}

		String value(String key) {
			return liveValue(key);
		}

		long ttlRemainingMs(String key) {
			Long expiresAt = this.expireAtMs.get(key);
			return expiresAt == null ? -1L : expiresAt - this.nowMs;
		}

		int plainWrites() {
			return this.bareSets + this.bareDeletes + this.bareExpires;
		}

		private synchronized Object handleScript(RedisScript<?> script, List<?> keys, List<Object> args) {
			String body = script.getScriptAsString();
			String marker = markerOf(body);
			this.scripts.add(marker);
			this.keyCounts.add(keys.size());
			this.lastKeys = keys.stream().map(String::valueOf).toList();
			if (keys.size() != 1) {
				// 真 Redis Cluster：EVAL 的 KEYS 必须同槽，多键单槽会回 CROSSSLOT
				throw new IllegalStateException("CROSSSLOT 会话锁脚本只允许单键，实际 " + keys.size());
			}
			if (this.failScripts) {
				throw new IllegalStateException("Redis 不可用（替身）");
			}
			if (this.nullScripts) {
				return null;
			}
			// 原子 op 之前的换主：脚本必须自己看见新主人并拒绝
			applySwap();
			String key = String.valueOf(keys.get(0));
			String current = liveValue(key);
			return switch (marker) {
				case ACQUIRE_MARK -> {
					String runId = str(args.get(2));
					if (current == null || owns(current, runId)) {
						put(key, str(args.get(0)), Duration.ofMillis(Long.parseLong(str(args.get(1)))));
						yield 1L;
					}
					yield 0L;
				}
				case RENEW_MARK -> {
					if (current != null && current.equals(str(args.get(0)))) {
						this.expireAtMs.put(key, this.nowMs + Long.parseLong(str(args.get(1))));
						yield 1L;
					}
					yield 0L;
				}
				case RELEASE_DEAD_MARK -> {
					if (current != null && owns(current, str(args.get(0)))) {
						this.values.remove(key);
						this.expireAtMs.remove(key);
						yield 1L;
					}
					yield 0L;
				}
				case RELEASE_MARK -> {
					if (current != null && current.equals(str(args.get(0)))) {
						this.values.remove(key);
						this.expireAtMs.remove(key);
						yield 1L;
					}
					yield 0L;
				}
				default -> throw new IllegalStateException("未知会话锁脚本：" + body);
			};
		}

		private static String markerOf(String body) {
			// 先判更长的 RELEASE_DEAD，避免被 RELEASE 前缀吃掉
			for (String marker : List.of(ACQUIRE_MARK, RENEW_MARK, RELEASE_DEAD_MARK, RELEASE_MARK)) {
				if (body.contains(marker)) {
					return marker;
				}
			}
			return "UNKNOWN";
		}

		private static boolean owns(String value, String runId) {
			return value.equals(runId) || value.startsWith(runId + "|");
		}

		private String liveValue(String key) {
			Long expiresAt = this.expireAtMs.get(key);
			if (expiresAt != null && expiresAt <= this.nowMs) {
				this.values.remove(key);
				this.expireAtMs.remove(key);
				return null;
			}
			return this.values.get(key);
		}

		private void put(String key, String value, Duration ttl) {
			this.values.put(key, value);
			this.expireAtMs.put(key, this.nowMs + ttl.toMillis());
		}

		private void applySwap() {
			if (this.swapOnNextAccess != null) {
				this.values.put(LOCK_KEY, this.swapOnNextAccess);
				this.expireAtMs.remove(LOCK_KEY);
				this.swapOnNextAccess = null;
			}
		}

		private static String str(Object value) {
			return String.valueOf(value);
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

	}

}
