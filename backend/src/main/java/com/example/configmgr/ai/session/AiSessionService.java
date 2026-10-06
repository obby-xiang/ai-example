package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
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
 * 这里只维护"页签会话还活着"这一件事：{@code ai:session:<sessionId>} 一个带
 * <b>滑动 TTL</b>（{@code app.ai.session-ttl}，默认 6h）的轻量记录，每轮对话 touch 一次。
 * 记忆窗口（{@code chat:mem:<sessionId>}，官方 {@code ChatMemoryRepository} 实现）
 * 与挂起态（{@code ai:run:<runId>}）各有自己的 TTL，本类不复制它们的内容，
 * 也不做"会话不属于谁"的权限判定（DC-10：权限/认证不在本规格范围）。
 *
 * <h2>为什么"校验"其实是"创建或续期"</h2>
 * 会话 id 由前端页签生成并随请求携带（第一棒起就没有"建会话"接口），
 * 因此首轮必然是一个尚未存在的 id —— 校验语义只能是"存在则续期、不存在则登记"，
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
	 * 校验并续期会话（滑动 TTL）。
	 *
	 * @return true = 该 sessionId 此前已存在（续期）；false = 首次登记
	 */
	public boolean ensure(String sessionId) {
		String key = KEY_PREFIX + sessionId;
		Duration ttl = this.properties.getSessionTtl();
		try {
			boolean existed = Boolean.TRUE.equals(this.redis.hasKey(key));
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("sessionId", sessionId);
			value.put("lastActivity", Instant.now().toString());
			value.put("rounds", roundOf(key) + 1);
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
