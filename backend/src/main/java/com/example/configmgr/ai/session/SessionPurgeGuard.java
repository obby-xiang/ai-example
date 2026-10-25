package com.example.configmgr.ai.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 会话销毁的<b>写回守卫</b>（{@code ai:purge:guard:<sessionId>}）。
 *
 * <h2>它挡的是什么</h2>
 * 删除会话记忆是一条"先清键、后收尾"的路径：在跑轮次的终局会做整窗记忆收敛
 * （{@code ChatMemoryRepository#saveAll}），把 {@code chat:mem:*} 重新写回来。销毁会话必须
 * 关掉这个窗口 —— 否则用户点了"销毁"，几秒后旧轮收盘又把历史写回同名键。
 *
 * <p>
 * 因此本守卫被<b>所有"会把会话数据写回来"的写口</b>消费：{@code RedisChatMemoryRepository#saveAll}
 * 与完整历史的 {@code AiChatHistoryService#append} 在写之前问一次 {@link #isArmed(String)}，
 * 命中即拒绝写回（WARN 留痕）。它<b>不</b>拦读、不拦删除：销毁流程本身仍要能删键，
 * 重复销毁也必须能继续。
 *
 * <h2>为什么是带 TTL 的墓碑而不是永久标记</h2>
 * TTL 取 {@code app.ai.session-ttl}（默认 24h），与会话同寿：覆盖"在飞轮次最迟一个会话 TTL 内
 * 必然收尾"的窗口；过期后同名 sessionId（页签随机 UUID，正常不会复用）可重新使用。
 * 但会话销毁失败/延后时，{@link SessionPurgePendingStore} 的 durable pending 会保留 ——
 * {@link #isArmed(String)} 同时看 pending：<b>未完成期间即使 guard key 的 TTL 到了，也不会放行</b>。
 * 只有删除全部完成、pending 移除后才由 {@link #retainOrArmIfOwner} 进入有限保留期。
 *
 * <h2>失败口径：销毁场景 fail-closed</h2>
 * {@link #isArmed(String)} 读失败<b>不再</b>返回 {@code false}，而是抛出
 * {@link com.example.configmgr.common.AiStateUnavailableException}：
 * "读不出来"不等于"没装守卫"。若按未装守卫放行，正在收尾的旧轮/历史写口就可能在本会话
 * 刚被销毁后把数据写回来 —— 这正是守卫要防的事。抛出让写口<b>拒绝本次写入</b>，
 * 调用方（记忆收敛会吞掉并 WARN；历史写口按 agent-39 的约定不吞）自行决定可观测性，
 * 但没有任何一条路径能在守卫状态不可知时"悄悄复活"数据。
 * 销毁流程自己的 {@link #arm(String, String, Duration)} 失败仍如实抛出，由服务中止删除
 * （见 {@code AiSessionPurgeService} 的"先装守卫、再删数据"顺序）；{@code disarmQuietly}
 * 只用于"删除任何数据之前就失败"的回滚，且按 purgeId 前缀 CAS（删不到新一轮/另一实例的守卫），
 * 不改变这条语义。
 */
@Slf4j
@Component
public class SessionPurgeGuard {

	/** 守卫键前缀（诊断/取证可直读）。 */
	public static final String KEY_PREFIX = "ai:purge:guard:";

	/** durable pending 键前缀（未完成期间即使守卫 TTL 到期，isArmed 仍为 true）。 */
	public static final String PENDING_PREFIX = "ai:purge:pending:";

	/**
	 * 完成后的“保留期”守卫：只续 TTL，不改变 owner；若守卫已过期则按当前 purgeId 重新装。
	 * 只有当前值以 {@code <purgeId>|} 开头才允许写，旧重试不能覆盖新守卫。
	 */
	private static final RedisScript<Long> RETAIN_OR_ARM = new DefaultRedisScript<>("""
			-- PURGE_GUARD_RETAIN
			local current = redis.call('GET', KEYS[1])
			if current and string.sub(current, 1, string.len(ARGV[1])) ~= ARGV[1] then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
			return 1
			""", Long.class);

	/**
	 * 撤销守卫（<b>owner 条件</b>）：只有当前值仍以 {@code <purgeId>|} 开头才 DEL。
	 * 用于"删任何数据之前就失败"的回滚 —— 旧 purgeId 不能删掉新一轮装的守卫。
	 */
	private static final RedisScript<Long> DISARM_IF_OWNER = new DefaultRedisScript<>("""
			-- PURGE_GUARD_DISARM
			local current = redis.call('GET', KEYS[1])
			if current and string.sub(current, 1, string.len(ARGV[1])) == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);


	private final StringRedisTemplate redis;

	public SessionPurgeGuard(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 守卫键名。 */
	public String keyFor(String sessionId) {
		return KEY_PREFIX + sessionId;
	}

	/** 装守卫（销毁流程在删除任何数据之前调用）。值 = {@code purgeId|requestedBy}，便于取证。 */
	public void arm(String sessionId, String purgeId, Duration ttl) {
		Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(24) : ttl;
		this.redis.opsForValue().set(keyFor(sessionId), purgeId, effective);
	}

	/**
	 * 该会话是否处于"已销毁/正在销毁"状态。
	 *
	 * @throws com.example.configmgr.common.AiStateUnavailableException 读失败：
	 *         <b>销毁场景 fail-closed</b> —— 不得当作"未装守卫"继续写回（见类注释）。
	 */
	public boolean isArmed(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return false;
		}
		try {
			if (Boolean.TRUE.equals(this.redis.hasKey(keyFor(sessionId)))) {
				return true;
			}
			// 未完成 pending = 守卫有效，即使 guard key 的 TTL 已经到了也不能放行。
			return Boolean.TRUE.equals(this.redis.hasKey(PENDING_PREFIX + sessionId));
		}
		catch (RuntimeException ex) {
			log.warn("读取会话销毁守卫失败 sessionId={}（fail-closed：拒绝任何可能复活的写回）：{}", sessionId,
					ex.getMessage());
			throw new com.example.configmgr.common.AiStateUnavailableException(
					"无法确认会话销毁守卫状态（fail-closed）sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 删除全部完成后的守卫收尾：续/装有限保留期 TTL，且只允许当前 purgeId 的 owner 操作。
	 *
	 * @return true = 已续期/已装；false = 守卫已被另一个 purgeId 接管（旧重试不得改新守卫）
	 * @throws com.example.configmgr.common.AiStateUnavailableException Redis 不可用（不得 blind 继续）
	 */
	public boolean retainOrArmIfOwner(String sessionId, String purgeId, String actor, Duration ttl) {
		if (sessionId == null || sessionId.isBlank()) {
			return false;
		}
		Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(24) : ttl;
		String ownerPrefix = purgeId + "|";
		String value = ownerPrefix + (actor == null || actor.isBlank() ? "unknown" : actor);
		try {
			Long result = this.redis.execute(RETAIN_OR_ARM, List.of(keyFor(sessionId)), ownerPrefix, value,
					String.valueOf(effective.toMillis()));
			return result != null && result > 0L;
		}
		catch (RuntimeException ex) {
			throw new com.example.configmgr.common.AiStateUnavailableException(
					"收尾会话销毁守卫失败 sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 仅在"销毁在删任何数据之前就失败"时撤销守卫（避免把正常会话误锁成不可写）。
	 *
	 * <p><b>owner 条件</b>：只有守卫值仍以 {@code <purgeId>|} 开头才删。重复 DELETE 的中止回滚
	 * 因此不会拆掉上一轮（或另一实例）装的守卫 —— 那个守卫还在保护的正是上一轮没删完的数据。
	 *
	 * @return true = 确实由本次 purgeId 撤销；false = 守卫属于别人或已不存在（不动它）
	 */
	public boolean disarmQuietly(String sessionId, String purgeId) {
		if (sessionId == null || sessionId.isBlank() || purgeId == null || purgeId.isBlank()) {
			return false;
		}
		try {
			Long deleted = this.redis.execute(DISARM_IF_OWNER, List.of(keyFor(sessionId)), purgeId + "|");
			return deleted != null && deleted > 0L;
		}
		catch (RuntimeException ex) {
			log.warn("撤销会话销毁守卫失败 sessionId={} purgeId={}（守卫会在 TTL 后自清）：{}", sessionId, purgeId,
					ex.getMessage());
			return false;
		}
	}

}
