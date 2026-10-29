package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.SessionIdGenerator;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端会话发号入口（前端不再生成 sessionId）。
 *
 * <p>
 * {@code POST /api/ai/session} 返回
 * {@code {"success":true,"data":{"sessionId":<long>,"sessionIdText":"<十进制字符串>"}}}，
 * 同时以当前请求身份（{@code X-Tenant-Id}/{@code X-User-Id} 建立的上下文）登记会话归属。
 * 切换租户/用户后由前端调用本接口取新号；旧号留在原身份下，不删除、不复用。
 *
 * <h2>两个字段的分工（I1）</h2>
 * 号值约 1.7×10^18，超过 JS {@code Number.MAX_SAFE_INTEGER}；JSON number 在多数语言里会被
 * 解析成另一个数（double 在该量级间隔 256），且**不报错**。因此：
 * <ul>
 * <li>{@code sessionIdText}：<b>权威值</b>，十进制字符串，消费方（前端/脚本/E2E）必须用它；</li>
 * <li>{@code sessionId}：同值的 long 数字，<b>只为存量兼容</b>；禁止把它当 JSON number 直读。</li>
 * </ul>
 *
 * <h2>发号暂不可用</h2>
 * {@link SessionIdGenerator} 的时钟回拨超窗/容量耗尽以 {@link SessionIdGenerator.UnavailableException}
 * 表达，本入口映射为 <b>503</b>（客户端可重试），不是 500；此时不登记会话。
 *
 * <p>
 * <b>为什么必须由后端发号</b>：前端生成的 id 无法保证多实例/同毫秒唯一，也无法证明归属；
 * 统一由 {@link SessionIdGenerator} 按"时间戳 + 实例位 + 实例内序列"发号后，归属仍以
 * {@code (tenantId,userId,sessionId)} 为准（唯一性不等于隔离）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiSessionController {

	/** 发号暂不可用的机器可读码（503，可重试）。 */
	public static final String SESSION_ID_UNAVAILABLE = "SESSION_ID_UNAVAILABLE";

	private final SessionIdGenerator sessionIdGenerator;

	private final AiSessionService sessionService;

	public AiSessionController(SessionIdGenerator sessionIdGenerator, AiSessionService sessionService) {
		this.sessionIdGenerator = sessionIdGenerator;
		this.sessionService = sessionService;
	}

	@PostMapping("/session")
	public ResponseEntity<ApiResponse<Map<String, Object>>> create() {
		// 身份缺失即失败（不落默认身份）；HTTP 边界已校验，这里再取一次保证"无上下文不登记"。
		long tenantId = RequestContextUtils.getTenantId();
		long userId = RequestContextUtils.getUserId();
		long sessionId;
		try {
			sessionId = this.sessionIdGenerator.next();
		}
		catch (SessionIdGenerator.UnavailableException ex) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ApiResponse.error(SESSION_ID_UNAVAILABLE,
						"会话发号暂不可用（" + (ex.retryable() ? "可稍后重试" : "请检查实例时钟") + "）：" + ex.getMessage()));
		}
		this.sessionService.ensure(String.valueOf(sessionId), tenantId, userId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("sessionId", sessionId);
		// 权威值：十进制字符串。JSON number 超过 2^53 后不可直读（见类注）。
		data.put("sessionIdText", Long.toString(sessionId));
		return ResponseEntity.ok(ApiResponse.ok(data));
	}

}
