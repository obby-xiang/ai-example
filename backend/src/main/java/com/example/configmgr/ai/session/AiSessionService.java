package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
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
 * 不读请求体。"会话属于谁"的判定本身（key 隔离、只带 runId 的入口核对快照）由后续任务接入；
 * 本类只负责<b>登记</b>，键族仍为 {@code ai:session:<sessionId>}。
 *
 * <h2>为什么"校验"其实是"创建或续期"</h2>
 * 发号已改为后端接口（{@code POST /api/ai/session}，时间戳+实例位+序列），但历史页签仍可能携带
 * 旧格式 id（前端迁移前），因此校验语义保持"存在则续期、不存在则登记"，
 * 结果里返回 {@code existed} 供日志与取证区分两种情形。
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
	 * 校验并续期会话（滑动 TTL），身份取当前 {@link RequestContextUtils} 上下文（缺失则不登记归属）。
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
		String key = KEY_PREFIX + sessionId;
		Duration ttl = this.properties.getSessionTtl();
		try {
			boolean existed = Boolean.TRUE.equals(this.redis.hasKey(key));
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("sessionId", sessionId);
			value.put("lastActivity", Instant.now().toString());
			value.put("rounds", roundOf(key) + 1);
			if (identity != null) {
				// 归属随会话记录落 Redis（本批不改键族；键形隔离由后续任务统一处理）
				value.put("tenantId", identity.tenantId());
				value.put("userId", identity.userId());
			}
			this.redis.opsForValue().set(key, this.objectMapper.writeValueAsString(value), ttl);
			return existed;
		}
		catch (Exception ex) {
			// 会话登记失败不应阻断本轮对话：记忆与挂起态各自有键，AI 能力不因此降级
			log.warn("会话登记失败 sessionId={}：{}", sessionId, ex.getMessage());
			return false;
		}
	}

	public boolean exists(String sessionId) {
		try {
			return Boolean.TRUE.equals(this.redis.hasKey(KEY_PREFIX + sessionId));
		}
		catch (Exception ex) {
			return false;
		}
	}

	public String keyFor(String sessionId) {
		return KEY_PREFIX + sessionId;
	}

	private int roundOf(String key) {
		try {
			String raw = this.redis.opsForValue().get(key);
			if (raw == null) {
				return 0;
			}
			Object rounds = this.objectMapper.readValue(raw, Map.class).get("rounds");
			return rounds instanceof Number number ? number.intValue() : 0;
		}
		catch (Exception ex) {
			return 0;
		}
	}

}
