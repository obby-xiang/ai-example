package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>结构化 {@code create_task} 候选的任务级草案</b>（{@code defCodes} / {@code conditions}），
 * 以<b>会话</b>为 Redis 键、以 <b>taskId 为字段</b>存放。
 *
 * <h2>为什么需要它</h2>
 * 候选原本只写进运行上下文的 {@code extra}（{@code createTaskDefCodes}/{@code createTaskConditions}），
 * 那只在<b>单轮 run 的上下文快照</b>里活着 —— 换轮、上下文重建、续跑都可能丢；丢了之后
 * {@code ConditionsForm} 只能把"用户已经说过的条件"当成未知，反复问用户。任务号是跨轮稳定的锚点，
 * 于是 {@code create_task} <b>结算时</b>另存一份草案。
 *
 * <h2>键与寿命（会话销毁必须能删掉它）</h2>
 * {@code ai:ctdraft:{<tenantId>:<userId>:session:<escapedSessionId>}} —— <b>每个会话一个 HASH</b>：
 * <ul>
 * <li>field = {@code taskId}，value = 一份 JSON（{@code defCodes}/{@code conditions}/{@code atMs}）；
 * 同一会话里跨 run 的同号任务读到的是同一份事实（任务号在同一租户/用户内不复用）；</li>
 * <li><b>会话销毁只删一个已知键</b>：{@code AiSessionPurgeService} 按同一个
 * {@link #sessionKey(String)} 精确 {@code DEL}，<b>不需要扫描</b>；这是本族键刻意不按 taskId 独立
 * 建键的原因 —— 那种键在会话销毁时既枚举不到、也没有任何索引可依，只能等 TTL 自清（残留）；</li>
 * <li><b>单键</b>：写入是一条单键 EVAL（{@code HSET}+{@code PEXPIRE} 原子）、读取 {@code HGET}、会话销毁
 * {@code DEL} ⇒ Cluster 下不存在 {@code CROSSSLOT}；值和 TTL 同批送达，因此不会有"有值无 TTL"的残留键，
 * tag 只用于键族识别与同槽书写规范，读路径<b>不</b>做多键脚本；</li>
 * <li><b>tag 含身份</b>：与 {@link TenantKeyScope} 同一口径 —— 两个租户可以拥有同名 sessionId，
 * 键本身就不同；无身份过渡期退回 {@code {session:<sid>}} 独立命名空间（不冒充任何真实身份）；</li>
 * <li><b>TTL = {@code app.ai.session-ttl}</b>：与会话/待决条目/快照同一把尺；每次写入续满。</li>
 * </ul>
 *
 * <h2>runId → sessionId</h2>
 * 写入/读取都以 <b>runId</b> 为入参，内部经 {@link RunStore#snapshotOwner(String)}（轻量：原始 JSON +
 * 流式读顶层 {@code sessionId}，不物化整份历史）解析会话号。快照不存在/没有 sessionId = 无可归属
 * （不建键、读不到）；快照<b>读失败或坏值</b>一律抛（{@link IllegalStateException}）——调用方必须按
 * <b>内部失败</b>口径处理，不能把它伪装成"没有候选"。
 *
 * <h2>信任边界</h2>
 * 这只是<b>候选事实</b>，绝不是任务事实：<b>绝不</b>写业务库（{@code tasks.settingsJson} / 任务条目）。
 * 条目条件的唯一写入者仍是前端的 {@code persistStepData}；读取方必须继续用与 {@code set_condition}
 * 同一份 {@code WizardActionGuard} 复核后才生成页面草案。
 *
 * <p><b>空值语义</b>：空的 {@code conditions={}} 不表示"已知条件"——写侧遇到空的 defCodes/conditions
 * 一律不覆盖（并入已有明确候选取其非空项），读侧把空 conditions 当成"没有候选"。
 */
@Slf4j
@Component
public class CreateTaskDraftStore {

	/** 键前缀（{@code ai:ctdraft:{<tenantId>:<userId>:session:<escapedSessionId>}}）。 */
	public static final String KEY_PREFIX = "ai:ctdraft:";

	/** 兜底 TTL：与 {@link AiProperties#getSessionTtl()} 的默认值一致（24h）。 */
	static final Duration DEFAULT_TTL = Duration.ofHours(24);

	/**
	 * 写入某任务字段并<b>同批</b>续满 TTL（单键 EVAL：{@code HSET} + {@code PEXPIRE}）。
	 *
	 * <p>为什么必须是一条命令：{@code HSET} 与 {@code EXPIRE} 分两条发时，两步之间进程被杀 /
	 * 连接断掉就会留下<b>有值无 TTL</b> 的草案 HASH —— 会话销毁的精确 {@code DEL} 只覆盖已知会话，
	 * 这类键既枚举不到也永不消失。单键脚本因此同时满足三件事：值与 TTL 原子、Cluster 无
	 * {@code CROSSSLOT}（只触碰一个键）、不需要扫描或索引。
	 *
	 * @param KEYS[1] {@code ai:ctdraft:{<tenantId>:<userId>:session:<escapedSessionId>}}
	 * @param ARGV[1] 字段（taskId）、ARGV[2] 草案 JSON、ARGV[3] TTL（毫秒）
	 */
	private static final RedisScript<Long> PUT_DRAFT_SCRIPT = new DefaultRedisScript<>("""
			-- CTDRAFT_PUT
			redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
			redis.call('PEXPIRE', KEYS[1], ARGV[3])
			return 1
			""", Long.class);

	private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

	private final ObjectProvider<StringRedisTemplate> redisProvider;

	private final ObjectProvider<RunStore> runStoreProvider;

	private final ObjectMapper mapper;

	private final Duration ttl;

	public CreateTaskDraftStore(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper mapper) {
		this(redisProvider, mapper, new AiProperties(), null);
	}

	@Autowired
	public CreateTaskDraftStore(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper mapper,
			AiProperties properties, ObjectProvider<RunStore> runStoreProvider) {
		this.redisProvider = redisProvider;
		this.runStoreProvider = runStoreProvider;
		this.mapper = mapper == null ? FALLBACK_MAPPER : mapper;
		Duration configured = properties == null ? null : properties.getSessionTtl();
		this.ttl = configured == null || configured.isZero() || configured.isNegative() ? DEFAULT_TTL : configured;
	}

	/**
	 * 会话草案键：{@code ai:ctdraft:{<tenantId>:<userId>:session:<escapedSessionId>}}。
	 *
	 * <p>身份段来自当前线程（与 run 级键同一份 {@link TenantKeyScope} 口径）；sessionId 里的
	 * {@code %}/{@code {} / {@code }} 按 {@code %25/%7B/%7D} 转义 —— 花括号进 tag 会让 Redis 的
	 * tag 边界提前结束（两个不同会话可能落到同一个键上），转义后 tag 非空且无碰撞。
	 */
	public static String sessionKey(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId 不得为空白：会话草案键由身份段 + sessionId 组成");
		}
		return KEY_PREFIX + "{" + TenantKeyScope.currentSegment() + "session:" + escapedSessionId(sessionId) + "}";
	}

	/** 无碰撞转义（顺序不能反：先 {@code %} 再花括号，避免二次转义）。 */
	private static String escapedSessionId(String sessionId) {
		return sessionId.replace("%", "%25").replace("{", "%7B").replace("}", "%7D");
	}

	/**
	 * 写入（或合并）某轮所属会话的任务草案。
	 *
	 * <p>写入是<b>一条</b>单键原子命令（值 + TTL 同批）：不存在"值已写、TTL 未设"的中间态，
	 * 因此写入失败/进程中断不会留下无 TTL 的草案键；命令失败照旧抛 {@link IllegalStateException}。
	 *
	 * @param runId      本轮 id（经快照解析 sessionId；null/快照缺失 = 不写）
	 * @param taskId     任务号（{@code <= 0} = 不写）
	 * @param defCodes   本次 create_task 明确给出的配置项（空 = 不覆盖已有）
	 * @param conditions 本次 create_task 明确给出的结构化条件（空/空对象 = 不覆盖已有）
	 * @throws IllegalStateException 会话归属读取失败，或 Redis 写失败
	 */
	public void save(String runId, long taskId, List<String> defCodes, Map<?, ?> conditions) {
		StringRedisTemplate redis = redis();
		if (redis == null || taskId <= 0L) {
			return;
		}
		boolean hasDefCodes = defCodes != null && !defCodes.isEmpty();
		boolean hasConditions = conditions != null && !conditions.isEmpty();
		if (!hasDefCodes && !hasConditions) {
			// 空候选不表示"已知"：不建键、不写字段，也不覆盖先前那份明确候选
			return;
		}
		String sessionId = sessionIdOf(runId);
		if (sessionId == null) {
			return;
		}
		String key = sessionKey(sessionId);
		String field = Long.toString(taskId);
		Draft merged = mergeWithExisting(redis, key, field, taskId, hasDefCodes ? defCodes : null,
				hasConditions ? conditions : null);
		if (merged == null) {
			return;
		}
		try {
			// 值与 TTL <b>同一条单键命令</b>：先 HSET 再 EXPIRE 的两条写法在两步之间被杀/断连会留下
			// 无 TTL 的草案 HASH（会话销毁只删已知键，这种键只能等人工清理）。单键 EVAL ⇒ Cluster
			// 无 CROSSSLOT，也不需要新增任何扫描或索引。
			redis.execute(PUT_DRAFT_SCRIPT, List.of(key), field, write(merged), Long.toString(this.ttl.toMillis()));
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("写入会话任务草案失败 sessionId=" + sessionId + " taskId=" + taskId, ex);
		}
	}

	/**
	 * 读取某轮所属会话的某个任务草案。
	 *
	 * @return {@code null} = 没有草案（run 快照不存在/无 sessionId、字段缺失或已过期）
	 * @throws IllegalStateException 会话归属读取失败、Redis 读失败或内容损坏（调用方按<b>内部失败</b>处理）
	 */
	public Draft read(String runId, long taskId) {
		StringRedisTemplate redis = redis();
		if (redis == null || taskId <= 0L) {
			return null;
		}
		String sessionId = sessionIdOf(runId);
		if (sessionId == null) {
			return null;
		}
		Object raw;
		try {
			raw = redis.opsForHash().get(sessionKey(sessionId), Long.toString(taskId));
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("读取会话任务草案失败 sessionId=" + sessionId + " taskId=" + taskId, ex);
		}
		if (raw == null) {
			return null;
		}
		return parse(String.valueOf(raw), taskId);
	}

	/**
	 * 合并"本次明确给出的项"与已存草案：空项一律取旧值（<b>空 {@code conditions={}} 不覆盖明确候选</b>）。
	 * 旧值损坏只 WARN 并按"没有旧值"处理 —— 写侧是 best-effort，新给出的明确事实仍然要落地。
	 */
	private Draft mergeWithExisting(StringRedisTemplate redis, String key, String field, long taskId,
			List<String> defCodes, Map<?, ?> conditions) {
		Draft existing = null;
		try {
			Object raw = redis.opsForHash().get(key, field);
			if (raw != null) {
				existing = parse(String.valueOf(raw), taskId);
			}
		}
		catch (RuntimeException ex) {
			log.warn("读取已有会话任务草案失败（按无旧值合并）key={} field={}：{}", key, field, ex.getMessage());
		}
		List<String> mergedDefCodes = defCodes != null && !defCodes.isEmpty() ? defCodes
				: existing == null ? List.of() : existing.defCodes();
		Map<String, Object> mergedConditions = new LinkedHashMap<>();
		if (conditions != null && !conditions.isEmpty()) {
			for (Map.Entry<?, ?> entry : conditions.entrySet()) {
				mergedConditions.put(String.valueOf(entry.getKey()), entry.getValue());
			}
		}
		else if (existing != null) {
			mergedConditions.putAll(existing.conditions());
		}
		if (mergedDefCodes.isEmpty() && mergedConditions.isEmpty()) {
			return null;
		}
		return new Draft(mergedDefCodes, mergedConditions, System.currentTimeMillis());
	}

	/**
	 * runId → 会话号（{@link RunStore#snapshotOwner(String)} 的轻量读口）。
	 *
	 * @return {@code null} = 快照不存在/没有可用 sessionId（无可归属，不建键）
	 * @throws IllegalStateException 快照读失败或坏值（归属不可知，调用方按内部失败处理）
	 */
	private String sessionIdOf(String runId) {
		if (runId == null || runId.isBlank()) {
			return null;
		}
		RunStore runs;
		try {
			runs = this.runStoreProvider == null ? null : this.runStoreProvider.getIfAvailable();
		}
		catch (RuntimeException ex) {
			log.debug("RunStore 不可用，会话任务草案跳过 runId={}：{}", runId, ex.getMessage());
			return null;
		}
		if (runs == null) {
			return null;
		}
		RunStore.SnapshotOwner owner = runs.snapshotOwner(runId);
		if (owner == null) {
			return null;
		}
		return switch (owner.state()) {
			case ATTRIBUTED -> owner.sessionId() == null || owner.sessionId().isBlank() ? null : owner.sessionId();
			case MISSING, ABSENT -> null;
			case UNREADABLE -> throw new IllegalStateException(
					"无法解析轮次快照的会话归属（快照读失败/坏值）runId=" + runId);
		};
	}

	private Draft parse(String raw, long taskId) {
		Draft draft;
		try {
			draft = this.mapper.readValue(raw, Draft.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("会话任务草案内容不可解析 taskId=" + taskId, ex);
		}
		if (draft == null) {
			return null;
		}
		return draft;
	}

	private String write(Object value) {
		try {
			return this.mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("会话任务草案序列化失败", ex);
		}
	}

	private StringRedisTemplate redis() {
		try {
			return this.redisProvider == null ? null : this.redisProvider.getIfAvailable();
		}
		catch (RuntimeException ex) {
			// 轻装配/单测没有 Redis 模板：草案缺席，不影响调用方既有路径
			log.debug("StringRedisTemplate 不可用，会话任务草案跳过：{}", ex.getMessage());
			return null;
		}
	}

	/**
	 * 某个任务的草案内容（字段 {@code taskId} 的值）。
	 *
	 * @param defCodes   本次 create_task 明确给出的配置项（可能为空：只给了条件）
	 * @param conditions 结构化条件（空 = 没有候选，<b>不是</b>"明确无筛选"）
	 * @param atMs       写入时刻（毫秒；0 = 未知，仅排障用）
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Draft(List<String> defCodes, Map<String, Object> conditions, long atMs) {

		public Draft {
			defCodes = defCodes == null ? List.of() : List.copyOf(defCodes);
			conditions = conditions == null ? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(conditions));
		}

		public boolean hasConditions() {
			return !this.conditions.isEmpty();
		}

	}

}
