package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.SessionIdGenerator;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后端会话发号入口（前端不再生成 sessionId）。
 *
 * <p>
 * {@code POST /api/ai/session} 返回 {@code {"success":true,"data":{"sessionId":<long>}}}，
 * 同时以当前请求身份（{@code X-Tenant-Id}/{@code X-User-Id} 建立的上下文）登记会话归属。
 * 切换租户/用户后由前端调用本接口取新号；旧号留在原身份下，不删除、不复用。
 *
 * <p>
 * <b>为什么必须由后端发号</b>：前端生成的 id 无法保证多实例/同毫秒唯一，也无法证明归属；
 * 统一由 {@link SessionIdGenerator} 按"时间戳 + 实例位 + 实例内序列"发号后，归属仍以
 * {@code (tenantId,userId,sessionId)} 为准（唯一性不等于隔离）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiSessionController {

	private final SessionIdGenerator sessionIdGenerator;

	private final AiSessionService sessionService;

	public AiSessionController(SessionIdGenerator sessionIdGenerator, AiSessionService sessionService) {
		this.sessionIdGenerator = sessionIdGenerator;
		this.sessionService = sessionService;
	}

	@PostMapping("/session")
	public ApiResponse<Map<String, Object>> create() {
		// 身份缺失即失败（不落默认身份）；HTTP 边界已校验，这里再取一次保证"无上下文不登记"。
		long tenantId = RequestContextUtils.getTenantId();
		long userId = RequestContextUtils.getUserId();
		long sessionId = this.sessionIdGenerator.next();
		this.sessionService.ensure(String.valueOf(sessionId), tenantId, userId);
		return ApiResponse.ok(Map.of("sessionId", sessionId));
	}

}
