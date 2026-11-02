package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 会话销毁的<b>durable pending 台账</b>（失败/延后时由 {@link SessionPurgeRetryRunner} 自动续清）。
 *
 * <h2>为什么需要它</h2>
 * {@code complete=false}（活跃 run 延后 / Redis 部分失败）如果只靠客户端重试，用户关掉页面就永远不会
 * 再删净；而 {@code SessionPurgeGuard} 若只有 session TTL，墓碑到期后同一个 sessionId 又能聊天并写回。
 * 因此销毁请求在删除任何数据之前登记本台账（无 TTL，直到 complete=true 才移除），
 * 守卫同时看 pending key —— <b>未完成期间守卫不会因 TTL 到期而失效</b>。
 *
 * <h2>键（全部带身份）</h2>
 * <ul>
 * <li>{@code ai:purge:pending:<tenantId>:<userId>:<sessionId>} STRING（JSON，无 TTL）—— 每个身份的
 * 每个会话一条 pending；跨租户/跨用户即使 sessionId 相同也不共用台账；</li>
 * <li>{@code ai:purge:retry:due} ZSET（member = <b>完整 pending 键</b>，score = 下次到期毫秒）——
 * 唯一的待重试发现面。成员带完整复合身份（键形里就是 {@code <t>:<u>:<sid>}），恢复时
 * <b>不需要</b>先知道是谁，也不需要遍历租户列表；{@link #listDue} 用有界 {@code ZRANGEBYSCORE}
 * 取到点项，不再有 LIST 队列 / SCAN 兜底。</li>
 * </ul>
 *
 * <h2>身份来源：按<b>记录</b>恢复，绝不从键名反解</h2>
 * {@code sessionId} 允许含冒号，键名无法可靠反解出身份段。因此：
 * <ul>
 * <li>写：身份取当前 {@link RequestContextUtils} 上下文（HTTP 边界/编排入口已建立），落进 JSON
 * 的 {@code tenantId/userId} 字段；</li>
 * <li>读：{@code listDue} 先按<b>成员键</b>取值，身份从 JSON <b>记录</b>读出
 * （{@link Pending#hasIdentity()}），调用方据此 {@code useContext} 恢复上下文再继续 ——
 * 这就是"按复合身份恢复，不扫描租户列表"。</li>
 * </ul>
 * 无身份上下文时落到 legacy 键（{@code ai:purge:pending:<sessionId>}，独立过渡命名空间），
 * 其记录没有身份字段；带身份的路径永远不会读写它。
 *
 * <h2>owner / purgeId</h2>
 * pending 内保存发起时的 {@code purgeId}。所有更新（失败重排、完成删除）都带 purgeId 条件：
 * 旧的自动重试不能改写/删除新一轮销毁的 pending，也不能据此 disarm 新守卫。
 * <b>登记是 upsert-if-absent</b>：已有台账时复用其 purgeId/owner（不重开新一轮），
 * 但手动 DELETE 会把 nextAt/verifyAt 重置为"现在可推进"，耗尽的 attempts 归零
 * （见 {@link #register}）。
 * 这样"本次中止回滚"只撤销自己新建的台账，不会把上一轮的 durable 义务一起抹掉。
 *
 * <h2>有界与一致性</h2>
 * <p>{@code listDue} 的候选数由调用方传入上限；本类不做无界 key 遍历。
 *
 * <p>pending 键与 due ZSET 不在同一 slot（ZSET 只有一个键，无法与所有 pending 键同槽），
 * 因此写路径是<b>单键脚本/单键命令</b>，不做跨键 Lua：
 * <ul>
 * <li><b>登记</b>先 {@code ZADD} 候选到期项，再 SET pending，最后按 effective.nextAt 对齐；
 * SET 失败只留下幽灵成员（listDue 清理），不会留下"有台账、无到期项"的永久 410；</li>
 * <li>改期/失败/完成先维护 pending 事实（purgeId 条件 CAS），再 {@code ZADD}/{@code ZREM}；
 * 台账换主时只按新台账的 nextAt 对齐到期分，不改新台账；</li>
 * <li>{@code listDue} 对单个坏 JSON 成员只隔离该到期项并 WARN，pending 键保留；
 * pending 键读取失败仍整轮 fail-closed。</li>
 * </ul>
 */
@Slf4j
@Component
public class SessionPurgePendingStore {

	/** durable pending 键前缀（守卫也读它）。 */
	public static final String PENDING_PREFIX = SessionPurgeGuard.PENDING_PREFIX;

	/** 待重试到期 ZSET（member = pending 键：键本身带完整复合身份，不是裸 sessionId）。 */
	public static final String DUE_KEY = "ai:purge:retry:due";

	/** 失败重试基础退避。 */
	static final long BASE_BACKOFF_MS = 10_000L;

	/** 失败重试最大退避（有界）。 */
	static final long MAX_BACKOFF_MS = 300_000L;

	/** 单条台账的自动尝试上限（与 {@code SessionPurgeRetryRunner#MAX_ATTEMPTS} 同源）。 */
	public static final int MAX_AUTO_ATTEMPTS = 10;

	/** purgeId 条件更新（值包含 token 才 SET 新 JSON）。 */
	private static final RedisScript<Long> CAS_SET = new DefaultRedisScript<>("""
			-- PURGE_PENDING_CAS_SET
			local v = redis.call('GET', KEYS[1])
			if not v or not string.find(v, ARGV[1], 1, true) then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	/**
	 * 登记（<b>不存在才写入</b>）：重复销毁必须复用既有台账，返回最终生效的那条。
	 *
	 * <p>为什么不能无条件 {@code SET}：第二次 DELETE 若在枚举/H2 处失败，回滚只应撤销"本次新建"
	 * 的台账（见调用方 {@code ownedByThisCall}）；无条件覆盖会先把上一轮的 purgeId/attempts 抹掉，
	 * 再被回滚删除 —— 上一轮的 deferred/失败义务从此无人认领。并发登记同理：后到者复用先到者的 owner。
	 */
	private static final RedisScript<String> REGISTER_IF_ABSENT = new DefaultRedisScript<>("""
			-- PURGE_PENDING_REGISTER
			local v = redis.call('GET', KEYS[1])
			if v then
			  return v
			end
			redis.call('SET', KEYS[1], ARGV[1])
			return ARGV[1]
			""", String.class);

	/** purgeId 条件删除（值包含 token 才 DEL）。 */
	private static final RedisScript<Long> CAS_DEL = new DefaultRedisScript<>("""
			-- PURGE_PENDING_CAS_DEL
			local v = redis.call('GET', KEYS[1])
			if not v or not string.find(v, ARGV[1], 1, true) then
			  return 0
			end
			return redis.call('DEL', KEYS[1])
			""", Long.class);

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final AiProperties properties;

	public SessionPurgePendingStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties) {
		this.redis = redis;
		this.mapper = mapper;
		this.properties = properties;
	}

	/**
	 * 一条 pending 台账（JSON 落 Redis；无 TTL）。
	 *
	 * <p><b>{@code verifyAtMs}（fix4，additive）</b>：非空 = 这条台账已"删除主体完成"，只剩一次
	 * <b>记忆面复核</b>要到点执行（{@code chat:mem:*} 的崩溃残留补偿）。为 {@code null} 时是普通
	 * 待重试台账，语义与本字段引入前完全一致。
	 *
	 * <p>老 JSON 没有该字段 ⇒ 反序列化为 {@code null}（= 普通台账）；{@code null} 值不写出
	 * （{@link JsonInclude}），因此普通台账的 JSON 形态逐字不变。
	 *
	 * <p>为什么复用 {@code nextAtMs} 而不加状态枚举：到点语义本来就由 {@code nextAtMs} 驱动
	 * （{@code listDue} 只取 due 项），复核排程只需把 {@code nextAtMs} 对齐 {@code verifyAtMs}；
	 * 复核失败沿用 {@link #recordFailure} 的退避。复核是<b>独立阶段</b>：排程时 attempts 重开为 0
	 * （见 {@link #scheduleVerify}），普通台账不经过这条路径，其 10 次上限不变。
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Pending(String sessionId, String purgeId, String requestedBy, int attempts, long nextAtMs,
			long createdAtMs, String lastError, Long verifyAtMs, Long tenantId, Long userId) {

		/** 兼容构造（老 JSON/既有调用点）：{@code verifyAtMs = null} = 普通待重试台账。 */
		public Pending(String sessionId, String purgeId, String requestedBy, int attempts, long nextAtMs,
				long createdAtMs, String lastError) {
			this(sessionId, purgeId, requestedBy, attempts, nextAtMs, createdAtMs, lastError, null, null, null);
		}

		/** 兼容构造：无身份（legacy 台账）。 */
		public Pending(String sessionId, String purgeId, String requestedBy, int attempts, long nextAtMs,
				long createdAtMs, String lastError, Long verifyAtMs) {
			this(sessionId, purgeId, requestedBy, attempts, nextAtMs, createdAtMs, lastError, verifyAtMs, null, null);
		}

		/** 记录里是否带可用身份（两个都非空且合法）。 */
		public boolean hasIdentity() {
			return TenantKeyScope.hasIdentity(this.tenantId, this.userId);
		}

		/** 记录里的身份（缺失返回 {@code null} = legacy，不冒充任何新身份）。 */
		public TenantUser identityOrNull() {
			return hasIdentity() ? new TenantUser(this.tenantId, this.userId) : null;
		}

		/** 这条台账的键（身份来自<b>记录</b>，不读线程上下文）。 */
		public String key() {
			return pendingKey(this.sessionId, this.tenantId, this.userId);
		}

	}

	/**
	 * 登记结果。
	 *
	 * @param pending 最终生效的台账（可能是既有那条 —— 复用时 attempts/nextAt 原样保留）
	 * @param created 本次是否由我新建（{@code false} = 复用既有台账，<b>回滚时不得删除它</b>）
	 */
	public record Registration(Pending pending, boolean created) {
	}

	/** pending 键名（<b>带当前身份</b>；无上下文时是 legacy 键形）。 */
	public String pendingKey(String sessionId) {
		return TenantKeyScope.scoped(PENDING_PREFIX, sessionId);
	}

	/** pending 键名（显式身份；身份来自记录/调用方）。 */
	public static String pendingKey(String sessionId, Long tenantId, Long userId) {
		return TenantKeyScope.hasIdentity(tenantId, userId)
				? TenantKeyScope.scoped(PENDING_PREFIX, tenantId, userId, sessionId)
				: PENDING_PREFIX + sessionId;
	}

	/**
	 * 登记 pending 并写入到期 ZSET；<b>复用既有 purgeId/owner</b>。
	 *
	 * <p><b>顺序（C2）</b>：先 ZADD 候选到期项，再 SET pending 事实，最后按 effective.nextAt 对齐。
	 * SET 失败时只留下一个 ZSET 幽灵成员（listDue 读不到台账会摘掉），<b>不会</b>留下
	 * "pending 存在但没有任何到期项"的永久 410；ZADD 失败则直接中止，快照/数据都没动。
	 *
	 * <p><b>手动 DELETE = 手动重开推进</b>：既有台账的 nextAt 重置为 <b>now</b>、verifyAt 清空；
	 * 已耗尽自动尝试的 attempts 重置为 0，未耗尽的保留原值。
	 * purgeId/owner/createdAt 不变。否则耗尽项被排到 24h 未来后，用户手动 DELETE 也永远推进不了。
	 *
	 * @return 最终生效的 pending 与"是否本次新建"
	 */
	public Registration register(String sessionId, String purgeId, String requestedBy, long nowMs) {
		return register(RequestContextUtils.currentOrNull(), sessionId, purgeId, requestedBy, nowMs);
	}

	/** 显式身份的登记（身份来自调用方/记录，不读线程上下文）。 */
	public Registration register(TenantUser identity, String sessionId, String purgeId, String requestedBy,
			long nowMs) {
		Long tenantId = identity == null ? null : identity.tenantId();
		Long userId = identity == null ? null : identity.userId();
		Pending candidate = new Pending(sessionId, purgeId, requestedBy, 0, nowMs, nowMs, null, null, tenantId,
				userId);
		String key = candidate.key();
		try {
			// 1) 先放候选到期项：SET 失败时它是可被 listDue 清理的幽灵成员，而不是无到期项的台账。
			schedule(key, nowMs, "登记候选");
			// 2) 再落 pending 事实（不存在才写）。
			String raw = this.redis.execute(REGISTER_IF_ABSENT, List.of(key), json(candidate));
			// REGISTER_IF_ABSENT 在"新建"时返回 candidate JSON、已存在时返回既有 JSON，raw 不为 null；
			// created 必须按原语义由 purgeId 判定（同一 purgeId = 本次调用拥有该台账，可回滚）。
			Pending effective = raw == null ? candidate : parse(sessionId, raw);
			boolean created = purgeId.equals(effective.purgeId());
			if (!created) {
				// 3a) 手动 DELETE 覆盖自动退避/耗尽/复核：nextAt 重置为 now，立即可推进。
				//     未耗尽的 attempts 原样保留（重复 DELETE 不白送自动重试预算）；
				//     已耗尽的台账必须重置为 0，否则 runner 会再次把它排到长未来。
				//     身份取当前键的身份（台账就落在这把键上），保证后续 complete 定位到同一键。
				int manualAttempts = effective.attempts() >= MAX_AUTO_ATTEMPTS ? 0 : effective.attempts();
				Pending manual = new Pending(sessionId, effective.purgeId(), effective.requestedBy(),
						manualAttempts, nowMs, effective.createdAtMs(), effective.lastError(), null, tenantId,
						userId);
				Long reset = this.redis.execute(CAS_SET, List.of(key), token(effective.purgeId()), json(manual));
				if (reset == null || reset <= 0L) {
					throw new AiStateUnavailableException(
							"无法重置会话销毁 pending（owner 已变更）sessionId=" + sessionId);
				}
				effective = manual;
			}
			// 3b) 对齐到期分：新建/手动重置都是 now，立即进入本轮候选。
			schedule(key, effective.nextAtMs(), "登记对齐");
			return new Registration(effective, created);
		}
		catch (RuntimeException ex) {
			// 台账已落就必须保留：它是守卫的一部分；调用方按失败中止，绝不删除保护事实后放行。
			throw new AiStateUnavailableException("无法登记会话销毁 pending sessionId=" + sessionId, ex);
		}
	}

	/** 当前 pending（<b>按当前身份</b>；不存在返回 null；JSON 损坏抛错，不静默当成无 pending）。 */
	public Pending current(String sessionId) {
		return readAt(pendingKey(sessionId), sessionId);
	}

	/** 按<b>键</b>读台账（listDue 恢复用：身份从记录读，不从键名猜）。 */
	private Pending readAt(String key, String sessionIdForLog) {
		String raw;
		try {
			raw = this.redis.opsForValue().get(key);
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("读取会话销毁 pending 失败 sessionId=" + sessionIdForLog, ex);
		}
		if (raw == null) {
			return null;
		}
		return parse(sessionIdForLog, raw);
	}

	private Pending parse(String sessionId, String raw) {
		try {
			return this.mapper.readValue(raw, Pending.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("会话销毁 pending JSON 损坏 sessionId=" + sessionId, ex);
		}
	}

	/** guard 用：pending 是否存在（读失败 fail-closed）。 */
	public boolean isPending(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(this.redis.hasKey(pendingKey(sessionId)));
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("读取会话销毁 pending 状态失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 到期待重试（候选数有界）：{@code ZRANGEBYSCORE -inf now LIMIT 0 limit}。
	 *
	 * <p>成员是 pending 键，身份从记录读。读不到的成员（台账已删除）直接摘掉；
	 * 记录里的 {@code nextAtMs} 比分数更晚（改期 CAS 成功但 ZADD 失败）时按记录纠正分数，
	 * 不提前执行 —— 退避语义以台账事实为准。
	 *
	 * <p><b>单成员坏 JSON 隔离</b>（I6）：一个成员反序列化失败只摘掉该到期成员并 WARN，
	 * <b>不中断整轮</b>，也<b>不删 pending 键</b>（台账事实保留，守卫继续 fail-closed）。
	 * pending 键读取失败（Redis 不可用）仍然整轮抛出 —— "读不出来"不能当成"没有台账"。
	 */
	public List<Pending> listDue(long nowMs, int limit) {
		int bounded = Math.max(1, limit);
		Set<String> members;
		try {
			members = this.redis.opsForZSet().rangeByScore(DUE_KEY, Double.NEGATIVE_INFINITY, nowMs, 0, bounded);
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("读取会话销毁重试到期分失败", ex);
		}
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		List<Pending> out = new ArrayList<>();
		for (String member : members) {
			if (member == null || member.isBlank() || !member.startsWith(PENDING_PREFIX)) {
				// 旧成员形态（裸 sessionId）或污染成员：无法定位带身份的台账，摘掉不猜。
				removeDueMember(member);
				continue;
			}
			Pending pending;
			try {
				pending = readAt(member, member);
			}
			catch (AiStateUnavailableException ex) {
				// pending 键读失败 = 状态不可知：整轮 fail-closed，不删台账、不猜"没有"。
				throw ex;
			}
			catch (RuntimeException ex) {
				// 单成员 JSON 损坏：隔离该到期成员（不删 pending 键），其余到期项继续处理。
				log.warn("会话销毁 pending JSON 损坏 member={}（隔离到期项、保留台账 fail-closed）：{}",
						member, ex.getMessage());
				removeDueMember(member);
				continue;
			}
			if (pending == null) {
				removeDueMember(member);
				continue;
			}
			if (pending.nextAtMs() > nowMs) {
				rescheduleQuietly(member, pending.nextAtMs());
				continue;
			}
			out.add(pending);
			if (out.size() >= bounded) {
				break;
			}
		}
		return out;
	}

	/**
	 * <b>fix4：把"删除主体已完成"的台账改排成一次记忆面复核</b>（owner-CAS，<b>复核阶段重开自己的有界预算</b>）。
	 *
	 * <p>为什么必须保留台账而不是直接 {@code complete}：写者在 RENAME 生效后、复检完成前 crash 时，
	 * 那份历史会留在 {@code chat:mem:*} 上，而销毁侧已经报过 complete —— 没有台账就没有任何人
	 * 会再清它。排程一次短期复核把"事后补偿"接到既有 runner 上，失败沿用
	 * {@link #recordFailure} 的有限退避与 {@code MAX_ATTEMPTS}（耗尽后只重排、不再自动执行）。
	 *
	 * <p><b>fix5：{@code attempts} 从 0 重开</b>。删除主体与"记忆复核"是两个阶段：主体可能已经耗光
	 * 自动尝试（deferred / Redis 抖动）之后，才被一次手动 {@code DELETE} 做成；若沿用旧计数，
	 * {@code SessionPurgeRetryRunner#runOnce} 的 {@code attempts >= MAX_ATTEMPTS} 短路排在复核分支
	 * <b>之前</b> —— 复核一次都跑不到，崩溃残留只能等记忆键自身 TTL，pending/守卫/到期项永久驻留。
	 * 重开后复核最多自动跑 {@code MAX_ATTEMPTS} 次（同一退避递增），耗尽后同样只 {@link #defer}
	 * （<b>有界，不是无限重试</b>）。普通台账永远不带 {@code verifyAtMs}，其 10 次上限/退避/defer
	 * 语义逐字不变。
	 *
	 * @param verifyAtMs 复核时刻（同时作为 {@code nextAtMs}，{@code listDue} 到点才取到）
	 * @return true = 仍归该 purgeId 且已排程；false = 台账已换主/不存在（旧调用点不得改写新一轮）
	 */
	public boolean scheduleVerify(String sessionId, String purgeId, long verifyAtMs) {
		Pending pending = current(sessionId);
		if (pending == null || !purgeId.equals(pending.purgeId())) {
			return false;
		}
		// fix5：复核是新阶段 —— 预算从 0 起；沿用主体阶段的旧计数会被 MAX_ATTEMPTS 短路在复核分支之前。
		String key = pending.key();
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), 0, verifyAtMs,
				pending.createdAtMs(), pending.lastError(), verifyAtMs, pending.tenantId(), pending.userId());
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(key), token(purgeId), json(next));
			if (updated == null || updated <= 0L) {
				return false;
			}
			schedule(key, verifyAtMs, "排程记忆复核");
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("排程会话销毁记忆复核失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 记录一次失败并退避（purgeId 条件；旧重试不得改写新 pending）。
	 *
	 * <p><b>fix4</b>：复核失败必须保留 {@code verifyAtMs} —— 否则下一次到点会退化成"整轮 retry"
	 * （再删一遍 DB/附件），而本意只是记忆面复核。
	 *
	 * @return true = 本条 pending 仍归该 purgeId 且已更新
	 */
	public boolean recordFailure(String sessionId, String purgeId, String error) {
		Pending pending = current(sessionId);
		if (pending == null || !purgeId.equals(pending.purgeId())) {
			return false;
		}
		int attempts = pending.attempts() + 1;
		long nextAt = System.currentTimeMillis() + backoffMs(attempts);
		String key = pending.key();
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), attempts, nextAt,
				pending.createdAtMs(), error, pending.verifyAtMs(), pending.tenantId(), pending.userId());
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(key), token(purgeId), json(next));
			if (updated == null || updated <= 0L) {
				return false;
			}
			schedule(key, nextAt, "记录失败退避");
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("更新会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 把台账推到给定未来时刻（purgeId 条件，<b>不改 attempts</b>）。
	 *
	 * <p>耗尽项由 {@code SessionPurgeRetryRunner} 给 24h + 确定性抖动；普通退避由
	 * {@link #recordFailure} 维护。推到长未来后它仍在台账里（durable），但不再每 5 分钟回到
	 * 到期队头；用户再次 {@code DELETE} 时 {@link #register} 会重置为 now/耗尽预算归零。
	 *
	 * @return true = 仍归该 purgeId 且已重排
	 */
	public boolean defer(String sessionId, String purgeId, long nextAtMs) {
		Pending pending = current(sessionId);
		if (pending == null) {
			// 台账已不存在：到期成员是幽灵，交给 listDue 清理；这里不按 sessionId 盲删，
			// 避免并发 register 刚建的新台账被误删发现项。
			return false;
		}
		if (!purgeId.equals(pending.purgeId())) {
			// 已换主：按新台账的 nextAt 对齐到期分，避免旧分数继续占工作窗口；不改新台账。
			rescheduleQuietly(pending.key(), pending.nextAtMs());
			return false;
		}
		String key = pending.key();
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), pending.attempts(), nextAtMs,
				pending.createdAtMs(), pending.lastError(), pending.verifyAtMs(), pending.tenantId(),
				pending.userId());
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(key), token(purgeId), json(next));
			if (updated == null || updated <= 0L) {
				// CAS 输给并发更新：按最新台账的 nextAt 对齐到期分，避免旧分数继续占窗口。
				Pending latest = current(sessionId);
				if (latest != null) {
					rescheduleQuietly(latest.key(), latest.nextAtMs());
				}
				return false;
			}
			schedule(key, nextAtMs, "延后重试");
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("延后会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 完成：仅当 pending 仍属于该 purgeId 时删除 pending 与到期项。
	 *
	 * <p>到期项删除失败只 WARN：台账已经删除，残留成员会被下一次 {@link #listDue} 当作
	 * "读不到记录"清掉，不能因此把一次已完成的销毁报成失败。
	 *
	 * @return true = 已删除（或已不存在）；false = pending 已换主，旧重试不得动它
	 */
	public boolean complete(String sessionId, String purgeId) {
		Pending pending = current(sessionId);
		if (pending == null) {
			// 不在这里按 sessionId 删到期项：读与删之间可能有并发 register 刚建的新台账，
			// 误删它的发现项会让新 pending 永久 410。幽灵成员由 listDue 读不到台账时清理。
			return true;
		}
		if (!purgeId.equals(pending.purgeId())) {
			return false;
		}
		String key = pending.key();
		try {
			Long deleted = this.redis.execute(CAS_DEL, List.of(key), token(purgeId));
			if (deleted == null || deleted <= 0L) {
				return false;
			}
			removeDueMember(key);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("删除会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/** 维护到期分（写路径：失败即抛，由调用方按失败处理）。 */
	private void schedule(String key, long nextAtMs, String action) {
		try {
			this.redis.opsForZSet().add(DUE_KEY, key, nextAtMs);
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException(
					"更新会话销毁重试到期分失败（" + action + "）key=" + key, ex);
		}
	}

	/** 自愈式对齐：读路径发现分数过期时纠正，失败只 WARN（下一轮还会再试）。 */
	private void rescheduleQuietly(String key, long nextAtMs) {
		try {
			this.redis.opsForZSet().add(DUE_KEY, key, nextAtMs);
		}
		catch (RuntimeException ex) {
			log.debug("纠正会话销毁重试到期分失败 key={}：{}", key, ex.getMessage());
		}
	}

	private void removeDueMember(String member) {
		if (member == null || member.isBlank()) {
			return;
		}
		try {
			this.redis.opsForZSet().remove(DUE_KEY, member);
		}
		catch (RuntimeException ex) {
			log.warn("清理会话销毁重试到期分失败 member={}（stale 项会被下次扫描过滤）：{}", member, ex.getMessage());
		}
	}

	static long backoffMs(int attempts) {
		long shift = Math.min(Math.max(0, attempts - 1), 5);
		return Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << shift);
	}

	private static String token(String purgeId) {
		return "\"purgeId\":\"" + purgeId + "\"";
	}

	private String json(Pending pending) {
		try {
			return this.mapper.writeValueAsString(pending);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法序列化会话销毁 pending sessionId=" + pending.sessionId(), ex);
		}
	}

	/** 守卫保留期使用 session TTL（与会话同寿；有限，不是永久）。 */
	public Duration retentionTtl() {
		Duration ttl = this.properties == null ? null : this.properties.getSessionTtl();
		return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(24) : ttl;
	}

}
