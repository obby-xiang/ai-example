package com.example.configmgr.ai.observe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.InstanceId;
import com.example.configmgr.ai.tenant.TenantKeyScope;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>结算观测位</b>：跨实例仲裁"同一条工具调用的结算行（{@code tool_call_end} + 审计计数）发过没有"。
 *
 * <h2>为什么需要一个独立的面（独立复审 I-1 / I-2）</h2>
 * park 之后"谁结算一条调用"是<b>跨实例</b>事实：决策/放弃写胜者、内联批循环、续跑认领胜者都可能在
 * 不同实例上走到同一条调用的结算处。进程内的"本进程有没有等待方"（{@code woke}）既可能让两个执行体
 * 各发一条（跨实例回灌），也可能让"已唤醒、尚无人 await 的预登记闸门"把一行彻底丢掉（多卡乱序）。
 * 故"发不发"由<b>持久首写者</b>裁决：{@code HSETNX}，抢到才发。
 *
 * <p>它<b>不参与任何判定</b>：不读决策、不读认领、不改变执行语义，只回答"这一行发过没有"。
 * 与 {@code RunStore} 的 {@code ai:decided:}（决策 WAL）分键存放，正是为了不让观测位被误读成决策。
 *
 * <h2>键生命周期（复审 C-1 后的口径）</h2>
 * <ul>
 * <li><b>键形</b>：{@code ai:observed:<tenantId>:<userId>:<runId>}（HASH；field = {@code toolCallId}，
 * value = {@code <atMs>|<instanceId>|<randomOwnerToken>}）。身份段来自 {@code TenantKeyScope#scoped}（与
 * {@code ai:beat:} / {@code ai:activity:} 同族）；<b>裸前缀、不带
 * {@code {run:<runId>}} tag</b> —— 只进单键命令/单键脚本（{@code EVAL} 只有一把 KEYS，
 * Cluster 下天然同槽）。无身份上下文时退回<b>旧键形</b> {@code ai:observed:<runId>}（独立
 * legacy 命名空间）；<b>跨租户同 runId 不共享观测位</b>（身份段进键，进程内退化表也按整键分槽）；</li>
 * <li><b>TTL</b>：{@code app.ai.session-ttl}。<b>抢位与置 TTL 在同一把单键脚本里</b>
 * （见 {@link #OBSERVE_SCRIPT}）：不存在"键写进去了但没有 TTL"的中间态；脚本之外每拍还做一次
 * 自愈 {@code EXPIRE}，把旧版本/半途失败留下的无 TTL 墓碑补回来（{@link #claim}）；</li>
 * <li><b>purge</b>：<b>会话销毁必须删</b>（绑定要求：删除全部 AI 归属数据，不能只等 TTL）。
 * 销毁方调用 {@link #purge(String)}（或按 {@link #observedKey(String)} 取键一并 DEL），
 * 两个面（Redis + 进程内）同时清；TTL 只是兜底，不是销毁手段；</li>
 * <li><b>迁移</b>：<b>不搬</b> —— 一次性仲裁位，跨世代没有要保留的事实；</li>
 * <li><b>降级</b>：Redis 读写失败 ⇒ {@link #claim} 返回允许发射的 handle（fail-open）——
 * 观测面宁可极小概率双记，也不能在依赖抖动时整片缺行。每次失败计一次
 * {@link RedisDegradation#O1_OBSERVATION_CLAIM}（否则"仲裁整体失效"在现网零信号）；</li>
 * <li><b>ARGV 类型</b>：脚本参数一律传<b>字符串</b>。{@code StringRedisTemplate} 用
 * {@code StringRedisSerializer} 序列化 ARGV，传 {@code Long} 会在发命令前
 * {@code ClassCastException} 并被 {@link #claim} 的 catch 吞成 fail-open（复审 R-C-1）。</li>
 * </ul>
 *
 * <h2>装配</h2>
 * Redis 可用时走跨实例面；无 Redis（轻装配/单测）时退化为进程内表 —— 两个"实例"共享同一个本类实例
 * 即可模拟"共享一份 Redis"，这也是聚焦用例的装配方式。
 */
@Slf4j
@Component
public class SettlementObservationStore {

	/** 键前缀（裸前缀；单键命令/单键脚本专用）。 */
	public static final String KEY_PREFIX = "ai:observed:";

	/**
	 * 单键"抢位 + 置 TTL"脚本：{@code HSETNX} 决定首写者，随后无条件 {@code EXPIRE}。
	 *
	 * <p>为什么必须原子：两条独立命令时"写成功、过期失败"会留下<b>永不过期</b>的键
	 * （复审 C-1 的探针实测）。脚本在 Redis 内一次执行，二者要么都生效、要么都没发生；
	 * 且"已存在但没 TTL"的旧墓碑也会在这一步被补上。
	 * 只有一把 KEYS ⇒ 不是多键脚本、不需要 {@code {run:<runId>}} tag。
	 */
	private static final RedisScript<Long> OBSERVE_SCRIPT = new DefaultRedisScript<>(
			"local claimed = redis.call('HSETNX', KEYS[1], ARGV[1], ARGV[2])\n" //
					+ "redis.call('EXPIRE', KEYS[1], ARGV[3])\n" //
					+ "return claimed",
			Long.class);

	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
				return redis.call('HDEL', KEYS[1], ARGV[1])
			end
			return 0
			""", Long.class);

	public record ClaimHandle(boolean claimed, String runId, String toolCallId, String token) {
	}

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	/** 进程内退化面（无 Redis / 单测）：键名 →（toolCallId → owner token）。 */
	private final Map<String, Map<String, String>> observed = new ConcurrentHashMap<>();

	@Autowired
	public SettlementObservationStore(ObjectProvider<StringRedisTemplate> redisProvider, AiProperties properties) {
		this((StringRedisTemplate) (redisProvider == null ? null : redisProvider.getIfAvailable()), properties);
	}

	/** 显式装配（测试/轻装配）：{@code redis == null} 时退化为进程内表。 */
	public SettlementObservationStore(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.properties = properties == null ? new AiProperties() : properties;
	}

	/** 纯进程内装配（单测默认）。 */
	public SettlementObservationStore() {
		this((StringRedisTemplate) null, new AiProperties());
	}

	/** 键名（销毁面/诊断/取证按它取键；与 {@link #claim} 写入的是同一个键）。 */
	public String observedKey(String runId) {
		// 身份段来自当前上下文：跨租户同 runId 落在不同键上（无上下文 = legacy 旧键形）。
		return TenantKeyScope.scoped(KEY_PREFIX, runId);
	}

	/** 抢"本次结算的观测位"，并返回后续失败释放所需的所有权 token。 */
	public ClaimHandle claim(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			return new ClaimHandle(true, runId, toolCallId, null);
		}
		String token = System.currentTimeMillis() + "|" + InstanceId.get() + "|" + UUID.randomUUID();
		if (this.redis == null) {
			Map<String, String> ids = this.observed.computeIfAbsent(observedKey(runId),
					ignored -> new ConcurrentHashMap<>());
			boolean claimed = ids.putIfAbsent(toolCallId, token) == null;
			return new ClaimHandle(claimed, runId, toolCallId, token);
		}
		String key = observedKey(runId);
		try {
			Long claimed = this.redis.execute(OBSERVE_SCRIPT, List.of(key), toolCallId, token,
					String.valueOf(ttlSeconds()));
			ensureTtl(key);
			return new ClaimHandle(claimed == null || claimed != 0L, runId, toolCallId, token);
		}
		catch (RuntimeException ex) {
			RedisDegradation.record(RedisDegradation.O1_OBSERVATION_CLAIM, ex);
			log.debug("settlement observation slot unavailable (treated as unobserved, this line still emitted) runId={} toolCallId={} error={}", runId, toolCallId,
					ex.getMessage());
			return new ClaimHandle(true, runId, toolCallId, token);
		}
	}

	/** 仅释放仍属于本次 handle 的占位；旧 owner 不得删除新 owner 的字段。 */
	public void release(ClaimHandle handle) {
		if (handle == null || handle.token() == null || handle.token().isBlank()) {
			return;
		}
		String key = observedKey(handle.runId());
		if (this.redis == null) {
			Map<String, String> ids = this.observed.get(key);
			if (ids != null && handle.token().equals(ids.get(handle.toolCallId()))) {
				ids.remove(handle.toolCallId());
			}
			return;
		}
		try {
			this.redis.execute(RELEASE_SCRIPT, List.of(key), handle.toolCallId(), handle.token());
		}
		catch (RuntimeException ex) {
			log.warn("release of settlement observation slot failed (this settlement may still be blocked) runId={} toolCallId={} error={}", handle.runId(), handle.toolCallId(),
					ex.getMessage());
		}
	}

	/**
	 * <b>销毁面</b>（会话销毁调用；Task 3 接线）：删 Redis 键 + 进程内表。
	 *
	 * <p>绑定要求：会话销毁要删除<b>全部</b> AI 归属数据，本键不能只靠 TTL。
	 *
	 * @return {@code true} = 两个面都已清（无 Redis 也算清）；{@code false} = Redis DEL 失败，
	 *         调用方应把它记进销毁错误集（走既有重试语义），不要静默
	 */
	public boolean purge(String runId) {
		if (runId == null || runId.isBlank()) {
			return true;
		}
		String key = observedKey(runId);
		this.observed.remove(key);
		if (this.redis == null) {
			return true;
		}
		try {
			this.redis.delete(key);
			return true;
		}
		catch (RuntimeException ex) {
			log.warn("settlement observation slot purge failed runId={} key={} error={}", runId, key, ex.getMessage());
			return false;
		}
	}

	private void ensureTtl(String key) {
		try {
			this.redis.expire(key, this.properties.getSessionTtl());
		}
		catch (RuntimeException ex) {
			// 自愈失败只降级：脚本本身已（原子地）置过 TTL，这里只是兜底修复旧墓碑
			log.debug("settlement observation slot TTL self-heal failed key={} error={}", key, ex.getMessage());
		}
	}

	private long ttlSeconds() {
		Duration ttl = this.properties.getSessionTtl();
		long seconds = ttl == null ? 0L : ttl.toSeconds();
		return seconds > 0L ? seconds : 1L;
	}

}
