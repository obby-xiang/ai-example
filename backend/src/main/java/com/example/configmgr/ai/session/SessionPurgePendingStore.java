package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
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
 * <h2>键</h2>
 * <ul>
 * <li>{@code ai:purge:pending:<sessionId>} STRING（JSON，无 TTL）—— 每个会话一条 pending；</li>
 * <li>{@code ai:purge:retry:queue} LIST（sessionId）—— 有界扫描的发现面；</li>
 * <li>启动/{@code SCAN} 兜底会把漏登记队列的 pending 补进队列，避免“只靠一个易丢失的索引”。</li>
 * </ul>
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

	/** 一条 pending 台账（JSON 落 Redis；无 TTL）。 */
	public record Pending(String sessionId, String purgeId, String requestedBy, int attempts, long nextAtMs,
			long createdAtMs, String lastError) {
	}

	/**
	 * 登记结果。
	 *
	 * @param pending 最终生效的台账（可能是既有那条 —— 复用时 attempts/nextAt 原样保留）
	 * @param created 本次是否由我新建（{@code false} = 复用既有台账，<b>回滚时不得删除它</b>）
	 */
	public record Registration(Pending pending, boolean created) {
	}

	/** pending 键名。 */
	public String pendingKey(String sessionId) {
		return PENDING_PREFIX + sessionId;
	}

	/**
	 * 登记 pending（写入后失败重试队列）；<b>已存在则复用</b>（不覆盖 purgeId/owner/attempts）。
	 *
	 * @return 最终生效的 pending 与"是否本次新建"
	 */
	public Registration register(String sessionId, String purgeId, String requestedBy, long nowMs) {
		Pending candidate = new Pending(sessionId, purgeId, requestedBy, 0, nowMs, nowMs, null);
		try {
			String raw = this.redis.execute(REGISTER_IF_ABSENT, List.of(pendingKey(sessionId)), json(candidate));
			Pending effective = raw == null ? candidate : parse(sessionId, raw);
			// 队列可能因崩溃窗口缺这一条（pending 已落、push 未执行）：补登记，不改台账内容。
			this.redis.opsForList().remove(QUEUE_KEY, 0, sessionId);
			this.redis.opsForList().rightPush(QUEUE_KEY, sessionId);
			return new Registration(effective, purgeId.equals(effective.purgeId()));
		}
		catch (RuntimeException ex) {
			// pending 已落就必须保留：它是守卫的一部分，队列异常时启动/空队列 SCAN 会继续发现。
			// 销毁流程按失败中止；显式 DELETE 也可重建队列，绝不删除保护事实后放行。
			throw new AiStateUnavailableException("无法登记会话销毁 pending sessionId=" + sessionId, ex);
		}
	}

	/** 当前 pending（不存在返回 null；JSON 损坏抛错，不静默当成无 pending）。 */
	public Pending current(String sessionId) {
		String raw;
		try {
			raw = this.redis.opsForValue().get(pendingKey(sessionId));
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("读取会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
		if (raw == null) {
			return null;
		}
		return parse(sessionId, raw);
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

	/** 到期待重试（候选数有界；队列重复成员去重）。 */
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
		for (String sessionId : raw) {
			if (sessionId == null || sessionId.isBlank() || !seen.add(sessionId)) {
				continue;
			}
			Pending pending = current(sessionId);
			if (pending == null) {
				removeFromQueue(sessionId);
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
	 * @param maxKeys SCAN 回填上限（有界）
	 */
	public int recoverOrphans(int maxKeys) {
		int scanned = 0;
		int recovered = 0;
		try (Cursor<String> cursor = this.redis.scan(
				ScanOptions.scanOptions().match(PENDING_PREFIX + "*").count(256).build())) {
			if (cursor == null) {
				return 0;
			}
			while (cursor.hasNext() && scanned < maxKeys) {
				String key = cursor.next();
				scanned++;
				if (key == null || !key.startsWith(PENDING_PREFIX)) {
					continue;
				}
				String sessionId = key.substring(PENDING_PREFIX.length());
				if (sessionId.isBlank()) {
					continue;
				}
				Pending pending = current(sessionId);
				if (pending == null) {
					continue;
				}
				this.redis.opsForList().remove(QUEUE_KEY, 0, sessionId);
				this.redis.opsForList().rightPush(QUEUE_KEY, sessionId);
				recovered++;
			}
			if (recovered > 0) {
				log.info("会话销毁 pending SCAN 兜底：扫描 {} 键，补回 {} 条待重试", scanned, recovered);
			}
			return recovered;
		}
		catch (RuntimeException ex) {
			log.warn("会话销毁 pending SCAN 兜底失败（本轮不猜状态）：{}", ex.getMessage());
			return -1;
		}
	}

	/**
	 * 记录一次失败并退避（purgeId 条件；旧重试不得改写新 pending）。
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
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), attempts, nextAt,
				pending.createdAtMs(), error);
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(pendingKey(sessionId)),
					token(purgeId), json(next));
			if (updated == null || updated <= 0L) {
				return false;
			}
			this.redis.opsForList().remove(QUEUE_KEY, 0, sessionId);
			this.redis.opsForList().rightPush(QUEUE_KEY, sessionId);
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
		Pending next = new Pending(sessionId, purgeId, pending.requestedBy(), pending.attempts(), nextAtMs,
				pending.createdAtMs(), pending.lastError());
		try {
			Long updated = this.redis.execute(CAS_SET, List.of(pendingKey(sessionId)), token(purgeId), json(next));
			if (updated == null || updated <= 0L) {
				return false;
			}
			this.redis.opsForList().remove(QUEUE_KEY, 0, sessionId);
			this.redis.opsForList().rightPush(QUEUE_KEY, sessionId);
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
		try {
			Long deleted = this.redis.execute(CAS_DEL, List.of(pendingKey(sessionId)), token(purgeId));
			if (deleted == null || deleted <= 0L) {
				return false;
			}
			// 只移除一条：同一 session 可能因重复登记/重排有多条队列成员，不能把新一轮的也删掉。
			this.redis.opsForList().remove(QUEUE_KEY, 1, sessionId);
			return true;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("删除会话销毁 pending 失败 sessionId=" + sessionId, ex);
		}
	}

	/** 移除一条队列项（stale pending 清理）。 */
	public void removeFromQueue(String sessionId) {
		try {
			this.redis.opsForList().remove(QUEUE_KEY, 1, sessionId);
		}
		catch (RuntimeException ex) {
			log.warn("清理会话销毁重试队列失败 sessionId={}（stale 项会被下次扫描过滤）：{}", sessionId,
					ex.getMessage());
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
