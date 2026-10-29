package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * <li>{@code ai:purge:retry:queue} LIST（成员 = <b>pending 键</b>）—— 有界扫描的发现面。
 * 成员带完整复合身份（键形里就是 {@code <t>:<u>:<sid>}），所以恢复时<b>不需要</b>先知道是谁，
 * 更不需要遍历租户列表；</li>
 * <li>启动/{@code SCAN} 兜底把漏登记的 pending 补进队列（同样按记录读出的身份回填）。</li>
 * </ul>
 *
 * <h2>身份来源：按<b>记录</b>恢复，绝不从键名反解</h2>
 * {@code sessionId} 允许含冒号，键名无法可靠反解出身份段。因此：
 * <ul>
 * <li>写：身份取当前 {@link RequestContextUtils} 上下文（HTTP 边界/编排入口已建立），落进 JSON
 * 的 {@code tenantId/userId} 字段；</li>
 * <li>读：{@code listDue}/{@code recoverOrphans} 先按<b>成员键</b>取值，身份从 JSON <b>记录</b>读出
 * （{@link Pending#hasIdentity()}），调用方据此 {@code useContext} 恢复上下文再继续 ——
 * 这就是"按复合身份恢复，不扫描租户列表"。</li>
 * </ul>
 * 无身份上下文时落到 legacy 键（{@code ai:purge:pending:<sessionId>}，独立过渡命名空间），
 * 其记录没有身份字段；带身份的路径永远不会读写它。
 *
 * <h2>owner / purgeId</h2>
 * pending 内保存发起时的 {@code purgeId}。所有更新（失败重排、完成删除）都带 purgeId 条件：
 * 旧的自动重试不能改写/删除新一轮销毁的 pending，也不能据此 disarm 新守卫。
 * <b>登记是 upsert-if-absent</b>：已有台账时复用其 purgeId/owner/attempts（重复 DELETE 不重开
 * 新一轮），这样"本次中止回滚"只撤销自己新建的台账，不会把上一轮的 durable 义务一起抹掉。
 *
 * <h2>有界</h2>
 * 队列扫描、SCAN 回填、候选数都由调用方传入上限；本类不做无界 key 遍历。
 */
@Slf4j
@Component
public class SessionPurgePendingStore {

	/** durable pending 键前缀（守卫也读它）。 */
	public static final String PENDING_PREFIX = SessionPurgeGuard.PENDING_PREFIX;

	/** 待重试队列（LIST，成员是 sessionId）。 */
	public static final String QUEUE_KEY = "ai:purge:retry:queue";

	/** 失败重试基础退避。 */
	static final long BASE_BACKOFF_MS = 10_000L;

	/** 失败重试最大退避（有界）。 */
	static final long MAX_BACKOFF_MS = 300_000L;

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
	 * 登记 pending（写入后失败重试队列）；<b>已存在则复用</b>（不覆盖 purgeId/owner/attempts）。
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
			String raw = this.redis.execute(REGISTER_IF_ABSENT, List.of(key), json(candidate));
			Pending effective = raw == null ? candidate : parse(sessionId, raw);
			// 队列可能因崩溃窗口缺这一条（pending 已落、push 未执行）：补登记，不改台账内容。
			// 成员 = pending 键（含完整复合身份），恢复时无需知道"是谁"。
			this.redis.opsForList().remove(QUEUE_KEY, 0, key);
			this.redis.opsForList().rightPush(QUEUE_KEY, key);
			return new Registration(effective, purgeId.equals(effective.purgeId()));
		}
		catch (RuntimeException ex) {
			// pending 已落就必须保留：它是守卫的一部分，队列异常时启动/空队列 SCAN 会继续发现。
			// 销毁流程按失败中止；显式 DELETE 也可重建队列，绝不删除保护事实后放行。
			throw new AiStateUnavailableException("无法登记会话销毁 pending sessionId=" + sessionId, ex);
		}
	}

	/** 当前 pending（<b>按当前身份</b>；不存在返回 null；JSON 损坏抛错，不静默当成无 pending）。 */
	public Pending current(String sessionId) {
		return readAt(pendingKey(sessionId), sessionId);
	}

	/** 按<b>键</b>读台账（listDue/SCAN 恢复用：身份从记录读，不从键名猜）。 */
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

	/** 到期待重试（候选数有界；队列重复成员去重）。成员是 pending 键，身份从记录读。 */
	public List<Pending> listDue(long nowMs, int limit) {
		int bounded = Math.max(1, limit);
		List<String> raw;
		try {
			raw = this.redis.opsForList().range(QUEUE_KEY, 0, bounded * 4L - 1);
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("读取会话销毁重试队列失败", ex);
		}
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>();
		List<Pending> out = new ArrayList<>();
		for (String member : raw) {
			if (member == null || member.isBlank() || !seen.add(member)) {
				continue;
			}
			if (!member.startsWith(PENDING_PREFIX)) {
				// 旧队列形态（裸 sessionId）或污染成员：无法定位带身份的台账，摘掉不猜。
				removeFromQueueMember(member);
				continue;
			}
			Pending pending = readAt(member, member);
			if (pending == null) {
				removeFromQueueMember(member);
				continue;
			}
			if (pending.nextAtMs() <= nowMs) {
				out.add(pending);
				if (out.size() >= bounded) {
					break;
				}
			}
		}
		return out;
	}

	/** 队列长度（-1 = 读失败；调用方据此决定是否做 SCAN 兜底）。 */
	public long queueSize() {
		try {
			Long size = this.redis.opsForList().size(QUEUE_KEY);
			return size == null ? 0L : size;
		}
		catch (RuntimeException ex) {
			log.warn("读取会话销毁重试队列长度失败：{}", ex.getMessage());
			return -1L;
		}
	}

	/**
	 * 启动/队列为空时的 SCAN 兜底：按前缀发现 pending，把漏登记的会话补回队列。
	 *
	 * <p><b>按记录恢复</b>：扫到键后读台账 JSON，身份取自记录（{@code tenantId/userId}），
	 * 并 {@code useContext} 恢复该身份后再回填队列 —— 不从键名反解身份、不遍历租户列表。
	 * 队列成员是键本身，因此回填不会把台账"搬到"别的身份下。
	 *
	 * @param maxKeys SCAN 回填上限（有界）
	 */
	public int recoverOrphans(int maxKeys) {
		int recovered = 0;
		// 逐 master 扫描（standalone 走单连接）；任一节点失败上抛，调用方不得报 complete。
		List<String> keys = RedisClusterScanSupport.scan(this.redis,
				ScanOptions.scanOptions().match(PENDING_PREFIX + "*").count(256).build(), maxKeys);
		for (String key : keys) {
			if (key == null || !key.startsWith(PENDING_PREFIX) || key.length() <= PENDING_PREFIX.length()) {
				continue;
			}
			Pending pending = readAt(key, key);
			if (pending == null) {
				continue;
			}
			// 即使这里只写队列、不读身份字段，也按记录恢复作用域（统一口径，将来加读字段不会 fail-closed）。
			try (RequestContextUtils.SafeCloseable ignored = scopeOf(pending)) {
				this.redis.opsForList().remove(QUEUE_KEY, 0, key);
				this.redis.opsForList().rightPush(QUEUE_KEY, key);
			}
			recovered++;
		}
		if (recovered > 0) {
			log.info("会话销毁 pending SCAN 兜底：扫描 {} 键，补回 {} 条待重试", keys.size(), recovered);
		}
		return recovered;
	}

	/** 记录身份的作用域（无身份返回 {@code null} = 不改变当前上下文）。 */
	private static RequestContextUtils.SafeCloseable scopeOf(Pending pending) {
		TenantUser identity = pending.identityOrNull();
		return identity == null ? null : RequestContextUtils.useContext(identity.tenantId(), identity.userId());
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
	 * <b>之前</b> —— 复核一次都跑不到，崩溃残留只能等记忆键自身 TTL，pending/守卫/队列项永久驻留。
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
			this.redis.opsForList().remove(QUEUE_KEY, 0, key);
			this.redis.opsForList().rightPush(QUEUE_KEY, key);
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
			this.redis.opsForList().remove(QUEUE_KEY, 0, key);
			this.redis.opsForList().rightPush(QUEUE_KEY, key);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("更新会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 把已耗尽自动尝试（{@code attempts >= MAX_ATTEMPTS}）的条目推到<b>有界未来</b>并移到队尾
	 * （purgeId 条件，<b>不改 attempts</b>）。
	 *
	 * <p>为什么必须重排而不是原地跳过：{@link #listDue} 只取队头有界窗口，"永远 due、永远不执行"
	 * 的条目会永久占满候选预算、饿死其它会话；移到队尾 + nextAt 推后之后它仍在台账里
	 * （durable，再次 {@code DELETE} 可继续推进），但不再 due、也不再挡在窗口前面。
	 *
	 * @return true = 仍归该 purgeId 且已重排
	 */
	public boolean defer(String sessionId, String purgeId, long nextAtMs) {
		Pending pending = current(sessionId);
		if (pending == null || !purgeId.equals(pending.purgeId())) {
			return false;
		}
		String key = pending.key();
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), pending.attempts(), nextAtMs,
				pending.createdAtMs(), pending.lastError(), pending.verifyAtMs(), pending.tenantId(),
				pending.userId());
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(key), token(purgeId), json(next));
			if (updated == null || updated <= 0L) {
				return false;
			}
			this.redis.opsForList().remove(QUEUE_KEY, 0, key);
			this.redis.opsForList().rightPush(QUEUE_KEY, key);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("延后会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 完成：仅当 pending 仍属于该 purgeId 时删除 pending 与一条队列项。
	 *
	 * @return true = 已删除（或已不存在）；false = pending 已换主，旧重试不得动它
	 */
	public boolean complete(String sessionId, String purgeId) {
		Pending pending = current(sessionId);
		if (pending == null) {
			removeFromQueue(sessionId);
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
			// 只移除一条：同一 session 可能因重复登记/重排有多条队列成员，不能把新一轮的也删掉。
			this.redis.opsForList().remove(QUEUE_KEY, 1, key);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("删除会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/** 移除一条队列项（stale pending 清理；成员 = 当前身份的 pending 键）。 */
	public void removeFromQueue(String sessionId) {
		removeFromQueueMember(pendingKey(sessionId));
	}

	private void removeFromQueueMember(String member) {
		try {
			this.redis.opsForList().remove(QUEUE_KEY, 1, member);
		}
		catch (RuntimeException ex) {
			log.warn("清理会话销毁重试队列失败 member={}（stale 项会被下次扫描过滤）：{}", member, ex.getMessage());
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
