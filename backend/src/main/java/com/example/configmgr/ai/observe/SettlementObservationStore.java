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
import java.util.Set;
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
 * value = {@code <atMs>|<instanceId>}）。身份段来自 {@code TenantKeyScope#scoped}（与
 * {@code ai:cancel:} / {@code ai:beat:} / {@code ai:activity:} 同族）；<b>裸前缀、不带
 * {@code {run:<runId>}} tag</b> —— 只进单键命令/单键脚本（{@code EVAL} 只有一把 KEYS，
 * Cluster 下天然同槽）。无身份上下文时退回<b>旧键形</b> {@code ai:observed:<runId>}（独立
 * legacy 命名空间）；<b>跨租户同 runId 不共享观测位</b>（身份段进键，进程内退化表也按整键分槽）；</li>
 * <li><b>TTL</b>：{@code app.ai.session-ttl}。<b>抢位与置 TTL 在同一把单键脚本里</b>
 * （见 {@link #OBSERVE_SCRIPT}）：不存在"键写进去了但没有 TTL"的中间态；脚本之外每拍还做一次
 * 自愈 {@code EXPIRE}，把旧版本/半途失败留下的无 TTL 墓碑补回来（{@link #claim}）；</li>
 * <li><b>purge</b>：<b>会话销毁必须删</b>（绑定要求：删除全部 AI 归属数据，不能只等 TTL）。
 * 销毁方调用 {@link #purge(String)}（或按 {@link #observedKey(String)} 取键一并 DEL），
 * 两个面（Redis + 进程内）同时清；TTL 只是兜底，不是销毁手段；</li>
 * <li><b>迁移</b>：<b>不搬</b> —— 一次性仲裁位，跨世代没有要保留的事实；
 * 已登记在 {@code scripts/redis-run-migration.mjs} 的 {@code EXCLUDED_KEY_PREFIXES}；</li>
 * <li><b>降级</b>：Redis 读写失败 ⇒ {@link #claim} 返回 {@code true}（fail-open）——
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

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	/** 进程内退化面（无 Redis / 单测）：键名 → 已观测的 toolCallId。 */
	private final Map<String, Set<String>> observed = new ConcurrentHashMap<>();

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

	/**
	 * 抢"本次结算的观测位"。
	 *
	 * @return {@code true} = 本次是首个观测者（调用方应发结算行 + 记审计）；
	 *         {@code false} = 已被别的执行体观测过（本次必须不发）
	 */
	public boolean claim(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			// 没有锚点就没有"同一条调用"可仲裁：放行（观测面不该因为没有主键而沉默）
			return true;
		}
		if (this.redis == null) {
			return this.observed.computeIfAbsent(observedKey(runId), ignored -> ConcurrentHashMap.newKeySet())
				.add(toolCallId);
		}
		String key = observedKey(runId);
		try {
			// ARGV 一律用字符串：{@code StringRedisTemplate} 的 ARGV 走 valueSerializer
			// （{@code StringRedisSerializer}），传 {@code Long} 会在发命令前 ClassCastException
			// 并被下面的 catch 吞成 fail-open ⇒ 仲裁整体失效（复审 R-C-1）。与项目其它 Lua 调用同形。
			Long claimed = this.redis.execute(OBSERVE_SCRIPT, List.of(key), toolCallId,
					System.currentTimeMillis() + "|" + InstanceId.get(), String.valueOf(ttlSeconds()));
			// 自愈（确定性）：旧版本/半途失败可能留下"有键无 TTL"的墓碑，任何一次 claim 都补回来。
			ensureTtl(key);
			return claimed == null || claimed != 0L;
		}
		catch (RuntimeException ex) {
			// fail-open：观测面在依赖抖动时宁可双记，也不能整片缺行（缺行比重行更难发现）
			// 但必须留可观测信号：静默 fail-open 会让"跨实例恰好一次"整体失效而无从发现（R-I-4）。
			RedisDegradation.record(RedisDegradation.O1_OBSERVATION_CLAIM, ex);
			log.debug("结算观测位不可用（按未观测处理，本行照发）runId={} toolCallId={}：{}", runId, toolCallId,
					ex.getMessage());
			return true;
		}
	}

	/**
	 * 释放占位：发行为失败时用（复审 I-1）。抢到了位却发不出行，会让本可能成功的执行体永远让位 ⇒
	 * 该条调用永久 0 条 end。释放后下一次结算可以重试（"宁重不宁缺"）。
	 *
	 * <p>幂等、绝不抛出：释放失败只 WARN（原始异常优先，不能被清理动作替换）。
	 */
	public void release(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			return;
		}
		String key = observedKey(runId);
		if (this.redis == null) {
			Set<String> ids = this.observed.get(key);
			if (ids != null) {
				ids.remove(toolCallId);
			}
			return;
		}
		try {
			this.redis.opsForHash().delete(key, toolCallId);
		}
		catch (RuntimeException ex) {
			log.warn("释放结算观测位失败（该条结算可能仍被挡）runId={} toolCallId={}：{}", runId, toolCallId,
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
			log.warn("结算观测位销毁失败 runId={} key={}：{}", runId, key, ex.getMessage());
			return false;
		}
	}

	private void ensureTtl(String key) {
		try {
			this.redis.expire(key, this.properties.getSessionTtl());
		}
		catch (RuntimeException ex) {
			// 自愈失败只降级：脚本本身已（原子地）置过 TTL，这里只是兜底修复旧墓碑
			log.debug("结算观测位 TTL 自愈失败 key={}：{}", key, ex.getMessage());
		}
	}

	private long ttlSeconds() {
		Duration ttl = this.properties.getSessionTtl();
		long seconds = ttl == null ? 0L : ttl.toSeconds();
		return seconds > 0L ? seconds : 1L;
	}

}
