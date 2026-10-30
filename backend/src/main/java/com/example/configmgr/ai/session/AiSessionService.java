package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话生命周期（S4.2 §2 {@code session/AiSessionService}，§3 时序第 1 步）。
 *
 * <h2>职责边界（DC-02 / D1：会话不做长期留存）</h2>
 * <p>
 * <b>2026-10-20 用户裁决第 4 条</b>：会话有效期 6h → <b>24h</b>，滑动续期（活跃即续），
 * 整个会话维度，<b>无绝对上限</b> —— 挂起等人的卡可以跨过任何"创建时刻"封顶。
 * 这里只维护"页签会话还活着"这一件事：{@code ai:session:<sessionId>} 一个带
 * <b>滑动 TTL</b>（{@code app.ai.session-ttl}，默认 24h）的轻量记录，每轮对话 touch 一次。
 * 记忆窗口（{@code chat:mem:{<sessionId>}}，官方 {@code ChatMemoryRepository} 实现；整窗覆盖写走
 * {@code saveAll}，空窗即删除）
 * 与挂起态（{@code ai:run:<runId>}）各有自己的 TTL，本类不复制它们的内容，
 * 也不做"会话不属于谁"的权限判定（DC-10：权限/认证不在本规格范围）。
 *
 * <h2>身份归属（2026-10-30）</h2>
 * 每条会话记录额外登记 {@code tenantId/userId}：值由服务端从请求身份上下文取得，
 * 不读请求体。"会话属于谁"的判定由<b>键形隔离</b>保证：
 * 记录键为 {@code ai:session:<tenantId>:<userId>:<sessionId>}（见 {@link TenantKeyScope}），
 * 跨租户/跨用户即使 sessionId 相同也落在不同键上，互不可见。
 * 没有身份上下文时落到<b>不含身份段的 legacy 键</b>（{@code ai:session:<sessionId>}）——
 * 那是独立的过渡命名空间，带身份的读写永远不会碰它。
 *
 * <h2>为什么"校验"其实是"创建或续期"</h2>
 * 发号已改为后端接口（{@code POST /api/ai/session}，时间戳+实例位+序列），但历史页签仍可能携带
 * 旧格式 id（前端迁移前），因此校验语义保持"存在则续期、不存在则登记"，
 * 结果里返回 {@code existed} 供日志与取证区分两种情形。
 *
 * <h2>撞号保护（写一次 + 只续期）</h2>
 * 登记走 Redis 原子 {@code SET NX EX}：键不存在才写，已存在<b>只刷新 TTL、不重写记录</b>。
 * 因此多实例发号若撞上已有号，旧会话的归属与内容原样保留，{@code existed=true} 让发号入口换号重发
 * （仍撞则 503），绝不会把两条会话静默合并成一条。
 */
@Slf4j
@Component
public class AiSessionService {

	public static final String KEY_PREFIX = "ai:session:";

	private final StringRedisTemplate redis;

	private final ObjectMapper objectMapper;

	private final AiProperties properties;

	public AiSessionService(StringRedisTemplate redis, ObjectMapper objectMapper, AiProperties properties) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/**
	 * 会话记录键（<b>带当前身份</b>）：{@code ai:session:<t>:<u>:<sessionId>}。
	 *
	 * <p>无身份上下文时返回 legacy 键形（{@code ai:session:<sessionId>}，独立的过渡命名空间）。
	 */
	public static String keyFor(String sessionId) {
		return TenantKeyScope.scoped(KEY_PREFIX, sessionId);
	}

	/** 会话记录键（<b>显式身份</b>；身份来自记录/调用方，不读线程上下文）。 */
	public static String keyFor(String sessionId, long tenantId, long userId) {
		return TenantKeyScope.scoped(KEY_PREFIX, tenantId, userId, sessionId);
	}

	/** 会话记录键（可空身份：{@code null} = legacy 键形）。 */
	public static String keyFor(String sessionId, TenantUser identity) {
		return KEY_PREFIX + TenantKeyScope.segmentOf(identity) + sessionId;
	}

	/**
	 * 校验并续期会话（滑动 TTL），身份取当前 {@link RequestContextUtils} 上下文。
	 *
	 * <p>有上下文 ⇒ 记录落在该身份的键上；<b>没有上下文</b> ⇒ 落在 legacy 键
	 * （{@code ai:session:<sessionId>}，独立过渡命名空间，带身份的读路径永不触及）。
	 *
	 * @return true = 该 sessionId 此前已存在（续期）；false = 首次登记
	 */
	public boolean ensure(String sessionId) {
		return ensureInternal(sessionId, RequestContextUtils.currentOrNull());
	}

	/**
	 * 校验并续期会话，并<b>显式登记</b>这次操作的身份归属。
	 *
	 * <p>归属只认服务端传入的 {@code tenantId/userId}（HTTP 边界从请求头校验后取得），
	 * 不读客户端请求体 —— 否则前端可以自报另一个租户。
	 *
	 * @return true = 该 sessionId 此前已存在（续期）；false = 首次登记
	 */
	public boolean ensure(String sessionId, long tenantId, long userId) {
		return ensureInternal(sessionId, new TenantUser(tenantId, userId));
	}

	private boolean ensureInternal(String sessionId, TenantUser identity) {
		String key = keyFor(sessionId, identity);
		Duration ttl = this.properties.getSessionTtl();
		try {
			// 原子"不存在才创建"（SET NX EX）：两个实例拿到同一个号时，只有先到者写入。
			if (Boolean.TRUE.equals(this.redis.opsForValue().setIfAbsent(key, valueOf(sessionId, identity), ttl))) {
				return false;
			}
			// 已存在：只滑动 TTL，<b>不重写记录</b>。撞号时旧会话的归属/内容必须原样保留
			// （发号入口据此换号；仍撞则 503，绝不把两条会话静默合成一条）。
			this.redis.expire(key, ttl);
			return true;
		}
		catch (Exception ex) {
			// 会话登记失败不应阻断本轮对话：记忆与挂起态各自有键，AI 能力不因此降级
			log.warn("会话登记失败 sessionId={}：{}", sessionId, ex.getMessage());
			return false;
		}
	}

	private String valueOf(String sessionId, TenantUser identity) throws Exception {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("sessionId", sessionId);
		// 首次登记时刻（记录写一次后不再改写，见 ensureInternal）。
		value.put("lastActivity", Instant.now().toString());
		if (identity != null) {
			// 归属随会话记录落 Redis：取证面；隔离本身由键形（身份段）保证。
			value.put("tenantId", identity.tenantId());
			value.put("userId", identity.userId());
		}
		return this.objectMapper.writeValueAsString(value);
	}

	public boolean exists(String sessionId) {
		try {
			return Boolean.TRUE.equals(this.redis.hasKey(keyFor(sessionId)));
		}
		catch (Exception ex) {
			return false;
		}
	}

}
