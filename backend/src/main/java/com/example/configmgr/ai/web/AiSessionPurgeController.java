package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.run.InstanceId;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.ai.tenant.TenantUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 完整会话销毁端点（{@code DELETE /api/ai/session/{sessionId}}）。
 *
 * <p>删除该会话全部 AI 数据，统一委托 {@link AiSessionPurgeService}，不存在第二套删除语义。
 *
 * <h2>HTTP 契约</h2>
 * <ul>
 * <li><b>幂等 200</b>：删除不存在的会话、重复删除都返回 200；{@code complete=true} 时无残留；</li>
 * <li><b>部分成功/活跃 run 延后</b>：仍 200，{@code complete=false} + {@code errors}/{@code deferred}
 * 明细（可重试；所有删除幂等）。<b>不恒 true</b>；</li>
 * <li><b>枚举失败/守卫失败/H2 删除失败</b>：200 + {@code aborted=true} + {@code phase}
 * （枚举失败时一个键都没删，H2 失败时 Redis 面未动）；</li>
 * <li><b>Redis 不可用</b>：503 {@code AI_REDIS_UNAVAILABLE}（与全仓 AI 端点同口径）；</li>
 * <li>响应是<b>裸对象</b>（与 {@code /cancel} 同族），不套 {@code ApiResponse} 信封；</li>
 * <li>不查 AI key（销毁是纯存储面操作，无 key 时也必须能删干净）。</li>
 * </ul>
 *
 * <p><b>本地镜像/草稿</b>：浏览器 {@code sessionStorage} 不归服务端管，响应里的
 * {@code clientCleanup} 给出精确键（带身份：{@code ai-mirror:<tenantId>:<userId>:<sessionId>}、
 * {@code cm.ai.form.draft.<tenantId>:<userId>.<toolCallId>}；无身份上下文时是旧键形
 * {@code ai-mirror:<sid>}、{@code cm.ai.form.draft.<toolCallId>}），由前端在收到 200 后删除；
 * 服务端不伪造"已删"。
 *
 * <p><b>身份</b>：本端点自己不解析身份头 —— 身份由 {@code TenantIdentityFilter} 建立的上下文提供，
 * {@link AiSessionPurgeService} 在入口固定它并贯穿全部键族（跨租户同 sessionId 互不可见），
 * <b>包括 H2 面</b>：{@code ai_feedback} 按 {@code sessionId + tenantId + userId} 删除（V11）。
 * 响应里的 {@code identity} 是这次身份的快照。
 */
@RestController
@RequestMapping("/api/ai")
public class AiSessionPurgeController {

	private static final Logger log = LoggerFactory.getLogger(AiSessionPurgeController.class);

	private final AiSessionPurgeService purgeService;

	private final RedisAvailability redisAvailability;

	public AiSessionPurgeController(AiSessionPurgeService purgeService, RedisAvailability redisAvailability) {
		this.purgeService = purgeService;
		this.redisAvailability = redisAvailability;
	}

	/**
	 * 完整会话销毁：{@code DELETE /session/{sid}}。
	 *
	 * <p>响应字段即 {@link AiSessionPurgeService.PurgeResult#toBody()} 的当前契约
	 * （{@code complete/deferred/errors/clientCleanup/...}），另附 {@code identity} 身份快照。
	 */
	@DeleteMapping("/session/{sessionId}")
	public ResponseEntity<Map<String, Object>> purge(@PathVariable String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(errorBody("BAD_REQUEST", "sessionId 必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(redisUnavailable());
		}
		// 无身份 fail-closed：销毁是身份内的操作（键族带身份段、反馈行按三元组删）。
		// 没有身份上下文时"删谁的会话"没有答案 ⇒ 拒绝，不回落"默认租户"、更不按 sessionId 全量删。
		// 生产入口由 TenantIdentityFilter 保证有身份；这里兜住任何绕过过滤器的直达调用。
		if (RequestContextUtils.currentOrNull() == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(errorBody(ErrorCodes.TENANT_IDENTITY_REQUIRED, "缺少租户/用户身份上下文，会话销毁不可跨身份执行"));
		}
		AiSessionPurgeService.PurgeResult result;
		try {
			result = this.purgeService.purge(sessionId, InstanceId.get());
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(errorBody("BAD_REQUEST", ex.getMessage()));
		}
		Map<String, Object> body = new LinkedHashMap<>(result.toBody());
		// 身份快照（纯 additive）：反馈行的删除面 = sessionId + tenantId + userId（V11），
		// 调用方据此可核对"这次销毁动的是哪个身份的会话数据"。
		TenantUser identity = RequestContextUtils.currentOrNull();
		body.put("identity", identity == null ? null
				: Map.of("tenantId", identity.tenantId(), "userId", identity.userId()));
		log.info("session purge endpoint sessionId={} complete={} deferred={} errors={}", sessionId,
				result.complete(), result.deferred().size(), result.errors().size());
		return ResponseEntity.ok(body);
	}

	private static Map<String, Object> errorBody(String code, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code);
		body.put("message", message);
		return body;
	}

	private Map<String, Object> redisUnavailable() {
		Map<String, Object> body = errorBody(RedisAvailability.code(),
				this.redisAvailability.reason() == null ? "Redis 不可用" : this.redisAvailability.reason());
		body.put("redis", this.redisAvailability.describeForResponse());
		return body;
	}

}
