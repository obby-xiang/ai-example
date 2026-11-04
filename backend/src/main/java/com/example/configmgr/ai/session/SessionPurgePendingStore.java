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
 * 取到点项，不再有 LIST 队列 / SCAN 兜底。<b>耗尽退役的台账不在其中</b>（见
 * {@link #retireExhausted}）：自动尝试耗尽后只保留 durable 台账，不占全局发现面。</li>
 * </ul>
 *
 * <h2>身份来源：按<b>记录</b>恢复，绝不从键名反解</h2>
 * {@code sessionId} 允许含冒号，键名无法可靠反解出身份段。因此：
 * <ul>
 * <li>写：身份取当前 {@link RequestContextUtils} 上下文（HTTP 边界/编排入口已建立），落进 JSON
 * 的 {@code tenantId/userId} 字段；</li>
 * <li>读：{@code listDue} 先按<b>成员键</b>取值，身份从 JSON <b>记录</b>读出
 * （{@link Pending#identity()}），调用方据此 {@code useContext} 恢复上下文再继续 ——
 * 这就是"按复合身份恢复，不扫描租户列表"。</li>
 * </ul>
 * 身份缺失即失败：本类不生成、不读取任何不含身份段的 pending 键。
 *
 * <h2>owner / epoch / purgeId</h2>
 * pending 内保存发起时的 {@code purgeId}，并直接承载会话级执行租约：唯一 executionOwner、
 * 单调 executionEpoch 与 leaseExpiresAtMs。HTTP、runner 和记忆复核先经同一单键 Lua 抢租；
 * 阶段推进、失败退避、复核排程和完成删除均同时校验 owner/epoch/purgeId，
 * 过期接管后旧执行体不能推进台账。登记仍复用原 purgeId，durable 义务在失败时保留。
 *
 * <h2>有界与一致性</h2>
 * <p>{@code listDue} 的候选数由调用方传入上限；本类不做无界 key 遍历。
 *
 * <p>pending 键与 due ZSET 不在同一 slot（ZSET 只有一个键，无法与所有 pending 键同槽），
 * 因此写路径是<b>单键脚本/单键命令</b>，不做跨键 Lua：
 * <ul>
 * <li><b>登记</b>先 {@code ZADD} 候选到期项，再 SET pending，最后按 effective.nextAt 对齐；
 * SET 失败只留下幽灵成员（listDue 清理），不会留下"有台账、无到期项"的永久 410；
 * 既有台账坏 JSON 时，手工 DELETE 用单键 compare-by-raw 覆盖恢复；</li>
 * <li>改期/失败先维护 pending 事实（purgeId 条件 CAS），再 {@code ZADD}；
 * 台账换主时只按新台账的 nextAt 对齐到期分，不改新台账；</li>
 * <li><b>完成</b>CAS_DEL 成功后 {@code ZREM}；由于 pending/ZSET 跨槽，ZREM 后必须按原键重读，
 * 并发新台账已存在则按它的 nextAt 立即补回；</li>
 * <li><b>耗尽退役</b>（{@link #retireExhausted}）：自动尝试耗尽的台账把 nextAt 推到有界未来后
 * <b>摘掉 due 成员</b> —— durable 台账保留（守卫仍读它、不伪装完成），但不再占全局发现面；
 * 退役 CAS <b>同时校验 attempts 仍耗尽</b>（不覆盖并发手工 DELETE 的 attempts=0），失败/换主时按
 * 最新台账恢复：可重试的补回到期项，退役态不重新 ZADD。手工 DELETE 经 {@link #register} 重置为
 * now 时会重新写回到期项；</li>
 * <li>{@code listDue} 对坏 JSON 成员<b>不摘除</b>，只隔离到 1h + 抖动未来并 WARN，
 * 保留手工 DELETE 恢复入口；pending 键读取失败仍整轮 fail-closed。已耗尽的成员在这里
 * 自愈式摘除（兜住退役时 ZREM 失败的残留）。</li>
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

	/** 坏 JSON 台账的隔离退避基准（有界：1h，不永久摘除）。 */
	static final long BAD_JSON_QUARANTINE_BASE_MS = 3_600_000L;

	/** 坏 JSON 隔离抖动步长（1 分钟）。 */
	static final long BAD_JSON_QUARANTINE_JITTER_STEP_MS = 60_000L;

	/** 坏 JSON 隔离抖动步数：0..14 步（最多约 14 分钟），按成员键确定性散开。 */
	static final int BAD_JSON_QUARANTINE_JITTER_STEPS = 15;

	/**
	 * purgeId 条件更新（值包含 token 才 SET 新 JSON）。
	 *
	 * <p><b>可选的耗尽前置条件</b>（{@code ARGV[3]} = 最小 attempts）：给出时，脚本在<b>同一个原子步</b>
	 * 里解出记录并拒绝 {@code attempts < ARGV[3]} 的写。退役必须证明"记录仍是耗尽态"——
	 * 只在 Java 侧先读再按 purgeId CAS 会输给并发手工 DELETE：后者把同一 purgeId 的
	 * {@code attempts} 归零、{@code nextAt} 重置为 now 之后，退役仍会把它推回长未来并摘掉到期项，
	 * 手工恢复从此 410。省略该参数时脚本行为与旧版逐字一致（普通改期/失败/完成不受影响）。
	 */
	private static final RedisScript<Long> CAS_SET = new DefaultRedisScript<>("""
			-- PURGE_PENDING_CAS_SET
			local v = redis.call('GET', KEYS[1])
			if not v or not string.find(v, ARGV[1], 1, true) then
			  return 0
			end
			if ARGV[3] then
			  local ok, rec = pcall(cjson.decode, v)
			  if not ok or type(rec) ~= 'table' then
			    return 0
			  end
			  local attempts = tonumber(rec['attempts'])
			  if (not attempts) or attempts < tonumber(ARGV[3]) then
			    return 0
			  end
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

	private static final RedisScript<String> MANUAL_RESET = new DefaultRedisScript<>("""
			-- PURGE_PENDING_MANUAL_RESET
			local raw = redis.call('GET', KEYS[1])
			if not raw then return '' end
			local ok, rec = pcall(cjson.decode, raw)
			if not ok or type(rec) ~= 'table' or rec['purgeId'] ~= ARGV[1] then return '' end
			local attempts = tonumber(rec['attempts']) or 0
			if attempts >= tonumber(ARGV[3]) then attempts = 0 end
			rec['attempts'] = attempts
			rec['nextAtMs'] = tonumber(ARGV[2])
			rec['verifyAtMs'] = nil
			local encoded = cjson.encode(rec)
			redis.call('SET', KEYS[1], encoded)
			return encoded
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

	/**
	 * 坏 JSON 的手工恢复入口：<b>只有当前值仍是我们读到的那份坏值</b>才覆盖成新台账。
	 *
	 * <p>单键 compare-by-raw，避免并发已写入的合法新台账被这次手动 DELETE 覆盖。
	 * 覆盖成功后仍按正常登记流程写到期项。
	 */
	private static final RedisScript<Long> REPLACE_IF_UNCHANGED = new DefaultRedisScript<>("""
			-- PURGE_PENDING_REPLACE_IF_UNCHANGED
			local v = redis.call('GET', KEYS[1])
			if v ~= ARGV[1] then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	private static final RedisScript<List> EXECUTION_ACQUIRE = new DefaultRedisScript<>("""
			-- PURGE_EXECUTION_ACQUIRE
			local raw = redis.call('GET', KEYS[1])
			if not raw then return {0, 'MISSING', ''} end
			local ok, rec = pcall(cjson.decode, raw)
			if not ok or type(rec) ~= 'table' or rec['purgeId'] ~= ARGV[2] then
			  return {0, 'PURGE_MISMATCH', raw}
			end
			local expires = tonumber(rec['leaseExpiresAtMs']) or 0
			if expires > tonumber(ARGV[3]) then return {0, 'BUSY', raw} end
			local epoch = (tonumber(rec['executionEpoch']) or 0) + 1
			rec['executionOwner'] = ARGV[1]
			rec['executionEpoch'] = epoch
			rec['leaseExpiresAtMs'] = tonumber(ARGV[3]) + tonumber(ARGV[4])
			rec['phase'] = 'ACQUIRED'
			local encoded = cjson.encode(rec)
			redis.call('SET', KEYS[1], encoded)
			return {1, tostring(epoch), encoded}
			""", List.class);

	private static final RedisScript<Long> EXECUTION_SET = new DefaultRedisScript<>("""
			-- PURGE_EXECUTION_SET
			local raw = redis.call('GET', KEYS[1])
			if not raw then return 0 end
			local ok, rec = pcall(cjson.decode, raw)
			if not ok or type(rec) ~= 'table' then return 0 end
			if rec['purgeId'] ~= ARGV[3] or rec['executionOwner'] ~= ARGV[1]
			  or tonumber(rec['executionEpoch']) ~= tonumber(ARGV[2]) then return 0 end
			if ARGV[5] == '1' and (tonumber(rec['leaseExpiresAtMs']) or 0) <= tonumber(ARGV[4]) then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[6])
			return 1
			""", Long.class);

	private static final RedisScript<Long> EXECUTION_DEL = new DefaultRedisScript<>("""
			-- PURGE_EXECUTION_DEL
			local raw = redis.call('GET', KEYS[1])
			if not raw then return 0 end
			local ok, rec = pcall(cjson.decode, raw)
			if not ok or type(rec) ~= 'table' then return 0 end
			if rec['purgeId'] ~= ARGV[3] or rec['executionOwner'] ~= ARGV[1]
			  or tonumber(rec['executionEpoch']) ~= tonumber(ARGV[2]) then return 0 end
			if (tonumber(rec['leaseExpiresAtMs']) or 0) <= tonumber(ARGV[4]) then return 0 end
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
	 * <p><b>{@code verifyAtMs}</b>：非空 = 这条台账已"删除主体完成"，只剩一次
	 * <b>记忆面复核</b>要到点执行（{@code chat:mem:*} 的崩溃残留补偿）。为 {@code null} 时是普通
	 * 待重试台账。
	 *
	 * <p>{@code null} 值不写出（{@link JsonInclude}）。为什么复用 {@code nextAtMs} 而不加状态枚举：
	 * 到点语义本来就由 {@code nextAtMs} 驱动（{@code listDue} 只取 due 项），复核排程只需把
	 * {@code nextAtMs} 对齐 {@code verifyAtMs}；复核失败沿用 {@link #recordFailure} 的退避。
	 * 复核是<b>独立阶段</b>：排程时 attempts 重开为 0（见 {@link #scheduleVerify}），
	 * 普通台账不经过这条路径，其 10 次上限不变。
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Pending(String sessionId, String purgeId, String requestedBy, int attempts, long nextAtMs,
			long createdAtMs, String lastError, Long verifyAtMs, Long tenantId, Long userId,
			String executionOwner, Long executionEpoch, Long leaseExpiresAtMs, String phase) {

		/** 记录里的身份（缺失/非法即失败：台账必须带身份）。 */
		public TenantUser identity() {
			if (!TenantKeyScope.hasIdentity(this.tenantId, this.userId)) {
				throw new IllegalStateException("会话销毁 pending 缺少可用身份 sessionId=" + this.sessionId
						+ "（tenantId=" + this.tenantId + ", userId=" + this.userId + "）");
			}
			return new TenantUser(this.tenantId, this.userId);
		}

		/** 这条台账的键（身份来自<b>记录</b>，不读线程上下文）。 */
		public String key() {
			return pendingKey(this.sessionId, this.tenantId, this.userId);
		}

	}

	/**
	 * 登记结果。
	 *
	 * @param pending 最终生效的台账（可能是既有那条 —— 手工 DELETE 会把 nextAt 重置为 now、
	 *                耗尽的 attempts 归零；未耗尽的 attempts 保留）
	 * @param created 本次是否由我新建/恢复（{@code false} = 复用既有台账，<b>回滚时不得删除它</b>）
	 */
	public record Registration(Pending pending, boolean created) {
	}

	public record Execution(String sessionId, String purgeId, String owner, long epoch) {
	}

	public record AcquireResult(Execution execution, Pending pending, String status) {
		public boolean acquired() {
			return this.execution != null;
		}
	}

	public AcquireResult acquire(String sessionId, String purgeId, String owner, long nowMs, long leaseTtlMs) {
		String key = pendingKey(sessionId);
		List<?> result = this.redis.execute(EXECUTION_ACQUIRE, List.of(key), owner, purgeId,
				Long.toString(nowMs), Long.toString(leaseTtlMs));
		String status = text(result, 1);
		if (result == null || result.isEmpty() || number(result.get(0)) != 1L) {
			String raw = result != null && result.size() > 2 ? text(result.get(2)) : null;
			Pending pending = raw == null || raw.isBlank() ? null : parse(sessionId, raw);
			return new AcquireResult(null, pending, status == null ? "BUSY" : status);
		}
		long epoch = Long.parseLong(status);
		Pending pending = parse(sessionId, text(result.get(2)));
		return new AcquireResult(new Execution(sessionId, purgeId, owner, epoch), pending, "ACQUIRED");
	}

	public boolean renew(Execution execution, String phase, long nowMs, long leaseTtlMs) {
		Pending pending = ownedPending(execution);
		if (pending == null) {
			return false;
		}
		Pending next = new Pending(pending.sessionId(), pending.purgeId(), pending.requestedBy(), pending.attempts(),
				pending.nextAtMs(), pending.createdAtMs(), pending.lastError(), pending.verifyAtMs(), pending.tenantId(),
				pending.userId(), pending.executionOwner(), pending.executionEpoch(), nowMs + leaseTtlMs, phase);
		return writeExecution(execution, next, nowMs, true);
	}

	public boolean recordFailure(Execution execution, String error) {
		Pending pending = ownedPending(execution);
		if (pending == null) {
			return false;
		}
		int attempts = pending.attempts() + 1;
		long nextAt = System.currentTimeMillis() + backoffMs(attempts);
		Pending next = new Pending(pending.sessionId(), pending.purgeId(), pending.requestedBy(), attempts, nextAt,
				pending.createdAtMs(), error, pending.verifyAtMs(), pending.tenantId(), pending.userId(), null,
				pending.executionEpoch(), null, "FAILED_RETRYABLE");
		long now = System.currentTimeMillis();
		if (!writeExecution(execution, next, now, true)) {
			return false;
		}
		schedule(pending.key(), nextAt, "记录执行失败退避");
		return true;
	}

	public boolean scheduleVerify(Execution execution, long verifyAtMs) {
		Pending pending = ownedPending(execution);
		if (pending == null) {
			return false;
		}
		Pending next = new Pending(pending.sessionId(), pending.purgeId(), pending.requestedBy(), 0, verifyAtMs,
				pending.createdAtMs(), pending.lastError(), verifyAtMs, pending.tenantId(), pending.userId(), null,
				pending.executionEpoch(), null, "VERIFYING");
		long now = System.currentTimeMillis();
		if (!writeExecution(execution, next, now, true)) {
			return false;
		}
		schedule(pending.key(), verifyAtMs, "排程记忆复核");
		return true;
	}

	public boolean complete(Execution execution) {
		Pending pending = ownedPending(execution);
		if (pending == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		Long deleted = this.redis.execute(EXECUTION_DEL, List.of(pending.key()), execution.owner(),
				Long.toString(execution.epoch()), execution.purgeId(), Long.toString(now));
		if (deleted == null || deleted <= 0L) {
			return false;
		}
		removeDueMember(pending.key());
		restoreDueAfterComplete(pending.key(), execution.sessionId());
		return true;
	}

	private Pending ownedPending(Execution execution) {
		Pending pending = current(execution.sessionId());
		if (pending == null || !execution.purgeId().equals(pending.purgeId())
				|| !execution.owner().equals(pending.executionOwner())
				|| pending.executionEpoch() == null || pending.executionEpoch() != execution.epoch()) {
			return null;
		}
		return pending;
	}

	private boolean writeExecution(Execution execution, Pending next, long nowMs, boolean requireLive) {
		Long updated = this.redis.execute(EXECUTION_SET, List.of(next.key()), execution.owner(),
				Long.toString(execution.epoch()), execution.purgeId(), Long.toString(nowMs), requireLive ? "1" : "0",
				json(next));
		return updated != null && updated > 0L;
	}

	private static long number(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		return value == null ? 0L : Long.parseLong(text(value));
	}

	private static String text(List<?> values, int index) {
		return values == null || values.size() <= index ? null : text(values.get(index));
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		}
		return String.valueOf(value);
	}

	/** pending 键名（<b>带当前身份</b>；无上下文 = 失败）。 */
	public String pendingKey(String sessionId) {
		return TenantKeyScope.scoped(PENDING_PREFIX, sessionId);
	}

	/** pending 键名（显式身份；身份来自记录/调用方，不可用 = 失败）。 */
	public static String pendingKey(String sessionId, Long tenantId, Long userId) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			throw new IllegalArgumentException("会话销毁 pending 缺少可用身份 sessionId=" + sessionId
					+ "（tenantId=" + tenantId + ", userId=" + userId + "）");
		}
		return TenantKeyScope.scoped(PENDING_PREFIX, tenantId, userId, sessionId);
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
	/** 显式身份的登记（身份来自调用方/记录，不读线程上下文；缺失即失败）。 */
	public Registration register(TenantUser identity, String sessionId, String purgeId, String requestedBy,
			long nowMs) {
		if (identity == null) {
			throw new IllegalArgumentException("会话销毁 pending 必须带身份 sessionId=" + sessionId);
		}
		Long tenantId = identity.tenantId();
		Long userId = identity.userId();
		Pending candidate = new Pending(sessionId, purgeId, requestedBy, 0, nowMs, nowMs, null, null, tenantId,
				userId, null, null, null, null);
		String key = candidate.key();
		try {
			// 1) 先放候选到期项：SET 失败时它是可被 listDue 清理的幽灵成员，而不是无到期项的台账。
			schedule(key, nowMs, "登记候选");
			// 2) 再落 pending 事实（不存在才写）。
			String raw = this.redis.execute(REGISTER_IF_ABSENT, List.of(key), json(candidate));
			// REGISTER_IF_ABSENT 在"新建"时返回 candidate JSON、已存在时返回既有 JSON，raw 不为 null；
			// created 必须按原语义由 purgeId 判定（同一 purgeId = 本次调用拥有该台账，可回滚）。
			Pending effective;
			boolean created;
			if (raw == null) {
				effective = candidate;
				created = purgeId.equals(effective.purgeId());
			}
			else {
				try {
					effective = parse(sessionId, raw);
					created = purgeId.equals(effective.purgeId());
				}
				catch (RuntimeException parseEx) {
					// 坏 JSON 的手工恢复入口：只有当前值仍是刚读到的坏值才覆盖，避免覆盖并发合法台账。
					log.warn("Corrupt session purge pending JSON sessionId={} (manual DELETE overwrite recovers; recovery entry kept): {}",
							sessionId, parseEx.getMessage());
					Long replaced = this.redis.execute(REPLACE_IF_UNCHANGED, List.of(key), raw, json(candidate));
					if (replaced == null || replaced <= 0L) {
						throw new AiStateUnavailableException(
								"无法恢复损坏的会话销毁 pending（并发变更）sessionId=" + sessionId);
					}
					effective = candidate;
					created = true;
				}
			}
			if (!created) {
				String reset = this.redis.execute(MANUAL_RESET, List.of(key), effective.purgeId(),
						Long.toString(nowMs), Integer.toString(MAX_AUTO_ATTEMPTS));
				if (reset == null || reset.isBlank()) {
					throw new AiStateUnavailableException(
							"无法重置会话销毁 pending（owner 已变更）sessionId=" + sessionId);
				}
				effective = parse(sessionId, reset);
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
	 * <p><b>单成员坏 JSON 隔离</b>：不摘除到期成员、不改 pending 键，只把它推到
	 * <b>1h + 确定性抖动</b>的未来并 WARN。这样既不阻断其他租户，也保留可恢复入口
	 * （手工 DELETE 可覆盖坏 JSON，见 {@link #register}）。
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
				// 污染成员：无法定位带身份的台账，摘掉不猜。
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
				// 单成员 JSON 损坏：不摘除、不改 pending；只把到期分推到有界未来并 WARN，
				// 保留手工 DELETE 的覆盖恢复入口，其余到期项继续处理。
				log.warn("Corrupt session purge pending JSON member={} (quarantined to the future; ledger and recovery entry kept): {}",
						member, ex.getMessage());
				quarantineDueMember(member, nowMs);
				continue;
			}
			if (pending == null) {
				removeDueMember(member);
				continue;
			}
			if (pending.nextAtMs() > nowMs) {
				if (pending.attempts() >= MAX_AUTO_ATTEMPTS) {
					// 已耗尽的台账：durable 事实保留，但不再占全局发现面（C-37）。
					// 这条分支兜住"退役时 ZREM 失败 / 并发重新 ZADD"的残留成员；
					// 手工 DELETE 经 register 会把它重置为 now 并重新写回。
					removeDueMember(member);
				}
				else {
					rescheduleQuietly(member, pending.nextAtMs());
				}
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
				pending.createdAtMs(), pending.lastError(), verifyAtMs, pending.tenantId(), pending.userId(), pending.executionOwner(), pending.executionEpoch(), pending.leaseExpiresAtMs(),
				pending.phase());
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
				pending.createdAtMs(), error, pending.verifyAtMs(), pending.tenantId(), pending.userId(), pending.executionOwner(), pending.executionEpoch(), pending.leaseExpiresAtMs(),
				pending.phase());
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
	 * 把台账推到给定未来时刻（purgeId 条件，<b>不改 attempts</b>，<b>保留</b> due 成员）。
	 *
	 * <p>普通退避由 {@link #recordFailure} 维护。自动尝试<b>耗尽</b>的台账走
	 * {@link #retireExhausted}（推未来 <b>且摘掉 due 成员</b>）；本方法保留"只改期、不动
	 * 发现面"的通用语义，供其它延后场景使用。
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
				pending.userId(), pending.executionOwner(), pending.executionEpoch(), pending.leaseExpiresAtMs(),
				pending.phase());
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
	 * <b>耗尽项退役</b>：把 durable 台账推到有界未来，并<b>摘掉全局 due 成员</b>（C-37）。
	 *
	 * <p>为什么只摘发现项、<b>不</b>删台账：pending 同时是守卫的一部分（{@code SessionPurgeGuard#isArmed}
	 * 读它）和"这次销毁到底做没做成"的 durable 事实。删它等于把一次<b>没做成</b>的销毁伪装成完成，
	 * 并让同一个 sessionId 可以重新写回。因此退役只改变"谁占着全局发现面"：
	 * <ul>
	 * <li>自动重试不再取到它，{@code ai:purge:retry:due} 不再随永久失败会话单调增长；</li>
	 * <li>台账与守卫事实原样保留（attempts 不变，仍是耗尽态）；</li>
	 * <li>用户再次 {@code DELETE} ⇒ {@link #register} 的"手动重开推进"会重置为 now、耗尽 attempts
	 *     归零，并在写台账<b>之前</b>重新 {@code ZADD} 候选到期项 ⇒ 手工恢复入口不变。</li>
	 * </ul>
	 *
	 * <p><b>CAS 必须同时证明"仍是耗尽态"</b>（{@link #CAS_SET} 的 {@code ARGV[3]}）：并发手工 DELETE
	 * 会把同一 purgeId 的台账重置成 {@code attempts=0 / nextAt=now}，只看 purgeId 的 CAS 会成功，
	 * 于是手工刚排上的到期项被推回长未来并摘掉。带耗尽前置条件的 CAS 在这种情况下失败，随后
	 * {@link #restoreDueAccordingToLatest} 按最新台账把可重试的记录补回发现面（退役态则不补）。
	 *
	 * <p>与 {@link #complete} 同因（pending / ZSET 跨槽）：ZREM 之后按<b>同一把键</b>重读，
	 * 若并发 {@code register} 已把台账改成"现在可推进"（新建或手动重置），立即把到期项补回 ——
	 * 退役的 ZREM 不得把手工 DELETE 刚排上的发现项抢掉。仍是<b>本 purgeId 的退役态</b>
	 * （{@code attempts >= MAX_AUTO_ATTEMPTS}）时保持摘除。
	 *
	 * <p>只用于 <b>attempts ≥ {@link #MAX_AUTO_ATTEMPTS}</b> 的台账（自动重试已耗尽）；非耗尽台账
	 * <b>前置拒绝</b>，普通延后仍用 {@link #defer}（保留发现面）。
	 *
	 * @return true = 仍归该 purgeId 且已退役；false = 台账不存在、已换主、已非耗尽或 CAS 输给并发
	 */
	public boolean retireExhausted(String sessionId, String purgeId, long nextAtMs) {
		Pending pending = current(sessionId);
		if (pending == null) {
			return false;
		}
		if (!purgeId.equals(pending.purgeId())) {
			// 已换主：按最新台账对齐到期分（可重试才补回），绝不摘掉它的发现项。
			restoreDueAccordingToLatest(pending.key(), sessionId);
			return false;
		}
		if (pending.attempts() < MAX_AUTO_ATTEMPTS) {
			// 前置拒绝：非耗尽台账不能被推到长未来并摘掉发现项 —— 那会把一次仍可自动推进的销毁藏起来。
			// 不改期、不动成员，普通延后由 defer 负责。
			return false;
		}
		String key = pending.key();
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), pending.attempts(), nextAtMs,
				pending.createdAtMs(), pending.lastError(), pending.verifyAtMs(), pending.tenantId(),
				pending.userId(), pending.executionOwner(), pending.executionEpoch(), pending.leaseExpiresAtMs(),
				pending.phase());
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(key), token(purgeId), json(next),
					Integer.toString(MAX_AUTO_ATTEMPTS));
			if (updated == null || updated <= 0L) {
				// CAS 输给并发更新：可能已换主，也可能是同一 purgeId 被手工 DELETE 重置成 attempts=0。
				// 按最新台账恢复：可重试的补回到期项，退役态绝不重新 ZADD。
				restoreDueAccordingToLatest(key, sessionId);
				return false;
			}
			removeDueMember(key);
			restoreDueIfRescheduled(key, sessionId, purgeId);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("退役会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 退役失败/换主后的收口：<b>只有最新台账仍可重试</b>（{@code attempts < MAX_AUTO_ATTEMPTS}）才把
	 * 到期项补回发现面；已退役（耗尽）的记录保持摘除，<b>不二次 ZREM、也绝不重新 ZADD</b>。
	 *
	 * <p>为什么不能无条件按 {@code latest.nextAtMs} 补回：CAS 失败最常见的原因就是"另一个实例刚把同一条
	 * 台账退役了"，对方的 {@code nextAt} 是长未来，照它补回等于把刚摘掉的成员原样放回去（C-37 复现）。
	 * 反之，手工 DELETE 把同一 purgeId 重置成 {@code attempts=0 / nextAt=now} 时，必须立刻补回，
	 * 否则用户的手工恢复会卡到下一次登记。
	 */
	private void restoreDueAccordingToLatest(String key, String sessionIdForLog) {
		Pending latest;
		try {
			latest = readAt(key, sessionIdForLog);
		}
		catch (RuntimeException ex) {
			// 读不回来：无法确认是否已重置。不猜着补回（退役态不占发现面）；
			// 并发 register 自己的 schedule 已写过到期项，手工路径不会因这次失败而丢。
			log.debug("Ledger re-check after failed retirement failed key={} (not restored; next registration/operation self-heals): {}", key, ex.getMessage());
			return;
		}
		if (latest == null) {
			// 台账已被并发 complete 删除：到期成员由完成流程/listDue 清理，这里不按旧键盲写。
			return;
		}
		if (latest.attempts() >= MAX_AUTO_ATTEMPTS) {
			// 退役态（CAS 输给并发退役）：成员已由那次成功的退役摘除，这里不再 ZREM。
			// 二次摘除会误删并发手工 register 刚补回的到期项（register 先 ZADD、再重置台账，
			// 重读可能落在两步之间，看到仍是耗尽态）。也绝不按长未来分数重新 ZADD。
			return;
		}
		rescheduleQuietly(key, latest.nextAtMs());
	}

	/**
	 * 退役 ZREM 之后的竞态收口：<b>只有</b>台账已不是"刚退役的那份"（换主或手动重置成可推进）
	 * 才把到期项补回；仍是退役态则保持摘除。
	 *
	 * <p>为什么不能无条件补回：退役的 nextAt 是"长未来"，照它补回等于把刚摘掉的成员又放回去
	 * （C-37 原样复现）。手工 DELETE 的 nextAt 是 now、新台账的 purgeId 不同，两者都会在这里重排。
	 *
	 * <p>退役态<b>只返回、不再 ZREM</b>：调用方在进入本方法前已经摘过一次；这里再摘会误删
	 * 重读之后并发手工 {@link #register} 刚补回的到期项。
	 */
	private void restoreDueIfRescheduled(String key, String sessionId, String purgeId) {
		Pending latest;
		try {
			latest = readAt(key, sessionId);
		}
		catch (RuntimeException ex) {
			// 读不回来：无法确认是否已重置。不猜着补回（退役态不占发现面）；
			// 并发 register 自己的 schedule 已写过到期项，手工路径不会因这次失败而丢。
			log.debug("Ledger re-check after retirement failed key={} (stays removed; next registration/operation self-heals): {}", key, ex.getMessage());
			return;
		}
		if (latest == null) {
			return;
		}
		// "仍是退役态"的判据不比较具体 nextAt：并发另一实例退役同一台账时会取不同的 nowMs，
		// 按值比较会把刚摘掉的成员又按未来分数放回去（C-37 原样复现）。耗尽即退役态。
		// 也不再要求 nextAt 在未来：并发的失败记录可能把 nextAt 留在过去，耗尽记录同样不该回到发现面。
		boolean stillRetired = purgeId.equals(latest.purgeId()) && latest.attempts() >= MAX_AUTO_ATTEMPTS;
		if (stillRetired) {
			// 退役前已摘过成员；这里只保持摘除，绝不再 ZREM（重读后并发 register 可能刚补回），
			// 也绝不按长未来分数重新 ZADD。
			return;
		}
		// 换主 / 手动重置为可推进（attempts 归零、nextAt=now）：把发现项补回。
		rescheduleQuietly(key, latest.nextAtMs());
	}

	/**
	 * 完成：仅当 pending 仍属于该 purgeId 时删除 pending 与到期项。
	 *
	 * <p>pending 键与 ZSET 不同槽，无法把 DEL+ZREM 做成同一脚本：CAS_DEL 成功后、ZREM 前可能有
	 * 并发 {@link #register} 刚写了一条新台账并放了到期项，ZREM 会误删它。因此 ZREM 之后必须
	 * <b>按同一把键（记录身份）重读</b>：新台账已存在就立即按它的 {@code nextAtMs} 补回到期分；
	 * 重读失败则把到期项隔离到未来，避免新台账永久 410。
	 *
	 * <p>到期项清理/补回失败只 WARN：台账已经按本 purgeId 删除，不能因此把一次已完成的销毁报成失败。
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
			restoreDueAfterComplete(key, sessionId);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("删除会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * CAS_DEL+ZREM 之后的竞态收口：按<b>原键</b>重读（key 已带记录身份），
	 * 若并发新台账已存在，立即按它的 nextAt 把到期项补回。
	 */
	private void restoreDueAfterComplete(String key, String sessionId) {
		Pending latest;
		try {
			latest = readAt(key, sessionId);
		}
		catch (RuntimeException ex) {
			// 重读失败/坏 JSON：无法确认新台账，把该成员的到期分隔离到未来，保留恢复入口。
			log.warn("Ledger re-check after completion delete failed key={} (due entry quarantined to the future; recovery entry kept): {}", key, ex.getMessage());
			quarantineDueMember(key, System.currentTimeMillis());
			return;
		}
		if (latest == null) {
			return;
		}
		try {
			schedule(key, latest.nextAtMs(), "完成竞态补回");
		}
		catch (RuntimeException ex) {
			log.warn("Failed to re-add due score to the new ledger after completion delete key={} "
					+ "(ledger kept; next registration/operation self-heals): {}", key,
					ex.getMessage());
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
			log.debug("Failed to correct session purge retry due score key={}: {}", key, ex.getMessage());
		}
	}

	/**
	 * 坏 JSON / 无法确认的到期成员：推到 <b>1h + 确定性抖动</b>的未来。
	 *
	 * <p>不摘除成员、不改 pending：既不阻断其他租户，也保留手工 DELETE 覆盖恢复的入口。
	 * 同一键每次散列到同一刻度，测试/排障可复现。
	 */
	private void quarantineDueMember(String key, long nowMs) {
		if (key == null || key.isBlank()) {
			return;
		}
		int step = Math.floorMod(key.hashCode(), BAD_JSON_QUARANTINE_JITTER_STEPS);
		try {
			this.redis.opsForZSet().add(DUE_KEY, key, nowMs + BAD_JSON_QUARANTINE_BASE_MS
					+ step * BAD_JSON_QUARANTINE_JITTER_STEP_MS);
		}
		catch (RuntimeException ex) {
			// 隔离写失败也不摘除：下轮仍会取到并重试，绝不静默变成永久 410。
			log.warn("Failed to quarantine corrupt-JSON due entry key={} (retried next round; never removed permanently): {}", key, ex.getMessage());
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
			log.warn("Failed to clean session purge retry due member={} (stale entries are filtered by the next scan): {}", member, ex.getMessage());
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
