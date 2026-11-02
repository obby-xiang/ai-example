package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code clientRequestId} → run 的精确关联与取消墓碑（停止闭环的最后一段）。
 *
 * <h2>为什么需要</h2>
 * start 帧之前停止、随后刷新时，前端从未拿到 runId；只凭"会话里当前有一条 run"无法证明它就是
 * 被停的那条请求（可能是另一页签/另一轮），猜 runId 会误杀。这里让<b>客户端请求 id</b>成为
 * 可证明的关联键：{@code /chat} 发送前由前端生成并持久化，后端在创建 run 之前把它与本轮
 * 绑定；取消可只凭这个 id 精确落到这一轮，或在 run 尚未创建时留下墓碑，让随后到达的
 * {@code /chat} 在压缩/建快照/模型调用/工具副作用之前收敛为已取消。
 *
 * <h2>键与隔离</h2>
 * <b>一个会话一个 HASH</b>：{@code ai:creq:<tenantId>:<userId>:<sessionId>}
 * （{@link TenantKeyScope}，与 {@code ai:ctx:} 等同族身份段）。field = {@code clientRequestId}，
 * value = {@code RUN:<runId>} / {@code CANCELLED} / {@code CANCELLED:<runId>}。
 * <ul>
 * <li><b>不以 runId 为键</b>，也不扫描任何租户/run 集合：只按会话 + 客户端 id 精确读写；</li>
 * <li>所有操作都是<b>单键</b> HGET/HSET/PEXPIRE 或单键 Lua（KEYS 恰好一个）—— Redis Cluster
 * 下天然同槽，不存在跨键脚本；取消旗标 {@code ai:cancel:<t>:<u>:<runId>} 由调用方另做一次
 * 单键写，不放进本脚本（避免 CROSSSLOT）；</li>
 * <li>TTL = {@code app.ai.session-ttl}（缺省 24h），每次 claim/cancel 整键续期；会话销毁时由
 * {@code AiSessionPurgeService} 按同一键拼接口精确 DEL（见 {@link #sessionKey(String)}）。</li>
 * </ul>
 *
 * <h2>取消早于 run 创建</h2>
 * 取消脚本在没有 field 时直接写 {@code CANCELLED}（带 TTL）而不是 404。随后 {@code /chat} 的
 * claim 脚本原子地看到 {@code CANCELLED}，不会覆盖它；调用方据此写本轮取消旗标，服务在
 * 压缩/建快照/模型调用之前收敛为 {@code done{cancelled:true}}。
 *
 * <p>不作为 runId 使用：本类产出的 runId 只来自 {@code RUN:} 分支，客户端无法用
 * {@code clientRequestId} 指定任意 runId；同一身份下重复取消幂等，不产生重复副作用。
 */
@Slf4j
@Component
public class ClientRequestRegistry {

	/** 会话级 HASH 键前缀（后接身份段 + sessionId）。 */
	public static final String KEY_PREFIX = "ai:creq:";

	/** {@code clientRequestId} 上限（UUID 远小于此；防御超长 field）。 */
	public static final int MAX_ID_LENGTH = 128;

	private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

	private static final String RUN_PREFIX = "RUN:";

	private static final String CANCELLED = "CANCELLED";

	private static final String CANCELLED_RUN_PREFIX = "CANCELLED:";

	/**
	 * claim（单键原子）：不存在 ⇒ 写 {@code RUN:<runId>}；已取消 ⇒ 保留墓碑并返回
	 * {@code CANCELLED}；已被别的 run 占用 ⇒ 返回 {@code EXISTS:<value>}（调用方拒绝这次
	 * /chat，绝不覆盖、绝不取消另一轮）。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = runId；ARGV[3] = TTL(ms)。
	 */
	private static final RedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
			-- CLIENT_REQUEST_CLAIM
			local v = redis.call('HGET', KEYS[1], ARGV[1])
			if not v then
			  redis.call('HSET', KEYS[1], ARGV[1], 'RUN:' .. ARGV[2])
			  redis.call('PEXPIRE', KEYS[1], ARGV[3])
			  return 'CLAIMED'
			end
			redis.call('PEXPIRE', KEYS[1], ARGV[3])
			if v == 'CANCELLED' or string.sub(v, 1, 10) == 'CANCELLED:' then
			  return 'CANCELLED'
			end
			return 'EXISTS:' .. v
			""", String.class);

	/**
	 * 取消（单键原子）：无 field ⇒ 写 {@code CANCELLED} 墓碑；{@code RUN:<runId>} ⇒ 转成
	 * {@code CANCELLED:<runId>} 并把它回给调用方（由调用方另写单键取消旗标）；
	 * 已取消 ⇒ 幂等返回（重复刷新/重复取消不再产生副作用）。
	 *
	 * <p>返回 {@code NEW[:runId]} / {@code ALREADY[:runId]}。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = TTL(ms)。
	 */
	private static final RedisScript<String> CANCEL_SCRIPT = new DefaultRedisScript<>("""
			-- CLIENT_REQUEST_CANCEL
			local v = redis.call('HGET', KEYS[1], ARGV[1])
			if not v then
			  redis.call('HSET', KEYS[1], ARGV[1], 'CANCELLED')
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  return 'NEW:'
			end
			if v == 'CANCELLED' then
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  return 'ALREADY:'
			end
			if string.sub(v, 1, 10) == 'CANCELLED:' then
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  return 'ALREADY:' .. string.sub(v, 11)
			end
			if string.sub(v, 1, 4) == 'RUN:' then
			  local runId = string.sub(v, 5)
			  redis.call('HSET', KEYS[1], ARGV[1], 'CANCELLED:' .. runId)
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  return 'NEW:' .. runId
			end
			redis.call('HSET', KEYS[1], ARGV[1], 'CANCELLED')
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return 'NEW:'
			""", String.class);

	/**
	 * 释放绑定（单键条件 HDEL）：只有当前值仍是本次写的 {@code RUN:<runId>} 才删。
	 *
	 * <p>用于"run 明确**没有**开始"的失败出口（如挂起池饱和拒绝执行）：让同一 clientRequestId
	 * 的前端瞬时重试能重新 claim，而不是被一条从未启动的映射卡成重复请求。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = runId。
	 */
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			-- CLIENT_REQUEST_RELEASE
			if redis.call('HGET', KEYS[1], ARGV[1]) == ('RUN:' .. ARGV[2]) then
			  return redis.call('HDEL', KEYS[1], ARGV[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	public ClientRequestRegistry(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** 会话级键（当前身份；无身份上下文时落 legacy 键形，与其它会话级键同口径）。 */
	public static String sessionKey(String sessionId) {
		return TenantKeyScope.scoped(KEY_PREFIX, sessionId);
	}

	/** {@code clientRequestId} 形状校验（additive 字段；非法值在入口 400，不进 Redis）。 */
	public static boolean isValid(String clientRequestId) {
		return clientRequestId != null && clientRequestId.length() <= MAX_ID_LENGTH
				&& ID_PATTERN.matcher(clientRequestId).matches();
	}

	public Claim claim(String sessionId, String clientRequestId, String runId) {
		if (!isValid(clientRequestId)) {
			throw new IllegalArgumentException("clientRequestId 非法");
		}
		String result = this.redis.execute(CLAIM_SCRIPT, List.of(sessionKey(sessionId)),
				clientRequestId, runId, String.valueOf(ttl().toMillis()));
		if (result == null) {
			throw new IllegalStateException("clientRequestId claim 结果不可知");
		}
		if ("CLAIMED".equals(result)) {
			return new Claim(ClaimState.CLAIMED, runId);
		}
		if ("CANCELLED".equals(result)) {
			return new Claim(ClaimState.CANCELLED, null);
		}
		String existing = result.startsWith("EXISTS:") ? result.substring("EXISTS:".length()) : result;
		String boundRunId = existing.startsWith(RUN_PREFIX) ? existing.substring(RUN_PREFIX.length()) : null;
		log.warn("clientRequestId 已被另一轮占用，拒绝复用 clientRequestId={} existing={}", clientRequestId, existing);
		return new Claim(ClaimState.ALREADY_BOUND, boundRunId);
	}

	/**
	 * 按 clientRequestId 精确取消（幂等）。返回需要写取消旗标的 runId（可能已知、也可能为空）。
	 */
	public CancelOutcome cancel(String sessionId, String clientRequestId) {
		if (!isValid(clientRequestId)) {
			throw new IllegalArgumentException("clientRequestId 非法");
		}
		String result = this.redis.execute(CANCEL_SCRIPT, List.of(sessionKey(sessionId)),
				clientRequestId, String.valueOf(ttl().toMillis()));
		if (result == null) {
			throw new IllegalStateException("clientRequestId 取消结果不可知");
		}
		boolean already = result.startsWith("ALREADY:");
		String runId = result.substring(result.indexOf(':') + 1);
		return new CancelOutcome(true, already, runId.isEmpty() ? null : runId);
	}

	/**
	 * 释放一次**从未开始**的绑定（条件 HDEL）：不会覆盖已取消墓碑，也不会删除别的 run 的绑定。
	 *
	 * @return true = 绑定已释放，前端可用同一 id 重新 claim（等价于一次全新发送）
	 */
	public boolean releaseBinding(String sessionId, String clientRequestId, String runId) {
		if (!isValid(clientRequestId) || runId == null || runId.isBlank()) {
			return false;
		}
		Long removed = this.redis.execute(RELEASE_SCRIPT, List.of(sessionKey(sessionId)),
				clientRequestId, runId);
		return removed != null && removed > 0L;
	}

	/** 本会话内该 clientRequestId 是否已被取消（服务在压缩/建快照前复查用）。 */
	public boolean isCancelled(String sessionId, String clientRequestId) {
		if (!isValid(clientRequestId)) {
			return false;
		}
		Object value = this.redis.opsForHash().get(sessionKey(sessionId), clientRequestId);
		if (value == null) {
			return false;
		}
		String text = String.valueOf(value);
		return CANCELLED.equals(text) || text.startsWith(CANCELLED_RUN_PREFIX);
	}

	/** 当前绑定值（诊断/取证用；不续期）。 */
	public String binding(String sessionId, String clientRequestId) {
		if (!isValid(clientRequestId)) {
			return null;
		}
		Object value = this.redis.opsForHash().get(sessionKey(sessionId), clientRequestId);
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * 该 clientRequestId 已绑定的 runId（只认 {@code RUN:<runId>}；未绑定/已取消 ⇒ null）。
	 *
	 * <p>供 /chat 的**重复到达**识别：同一个 id 已经启动过一轮时，调用方应走既有
	 * events/reattach 收帧，而不是再启动第二条驱动。
	 */
	public String boundRunId(String sessionId, String clientRequestId) {
		String value = binding(sessionId, clientRequestId);
		if (value == null || !value.startsWith(RUN_PREFIX)) {
			return null;
		}
		String runId = value.substring(RUN_PREFIX.length());
		return runId.isBlank() ? null : runId;
	}

	private Duration ttl() {
		Duration sessionTtl = this.properties.getSessionTtl();
		return sessionTtl != null && !sessionTtl.isZero() && !sessionTtl.isNegative()
				? sessionTtl : Duration.ofHours(24);
	}

	/** claim 的结果：只有 {@code CLAIMED} 可以继续；{@code CANCELLED} 走取消收敛；其余拒绝。 */
	public record Claim(ClaimState state, String runId) {
	}

	public enum ClaimState {
		CLAIMED, CANCELLED, ALREADY_BOUND
	}

	/**
	 * 一次按 clientRequestId 的取消结果。
	 *
	 * @param cancelled 已生成/确认取消墓碑
	 * @param alreadyCancelled 之前已经取消过（重复刷新/重复取消）
	 * @param runId 已知的本轮 runId（无则 null：run 尚未创建，墓碑待后续 /chat 识别）
	 */
	public record CancelOutcome(boolean cancelled, boolean alreadyCancelled, String runId) {
	}

}
