package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.id.IdGenerator;
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
 * 号值可能超过 JS {@code Number.MAX_SAFE_INTEGER}；JSON number 在多数语言里会被
 * 解析成另一个数（double 在该量级间隔 256），且**不报错**。因此：
 * <ul>
 * <li>{@code sessionIdText}：<b>权威值</b>，十进制字符串，消费方（前端/脚本/E2E）必须用它；</li>
 * <li>{@code sessionId}：同值的 long 数字，<b>只为存量兼容</b>；禁止把它当 JSON number 直读。</li>
 * </ul>
 *
 * <h2>发号与撞号</h2>
 * 每次请求只向 {@link IdGenerator} 取一次号（当前实现 {@code TimestampIdGenerator} 返回毫秒时间戳）。
 * 新号若撞上<b>已存在的会话</b>（同一毫秒/历史号重叠），{@link AiSessionService#ensure} 不会覆盖旧记录，
 * 而是报告 {@code existed}；本入口直接返回 <b>503</b>（{@link #SESSION_ID_UNAVAILABLE}），
 * 不换号重发，也不返回一个"登记成功"的假号。
 *
 * <p>
 * <b>为什么必须由后端发号</b>：前端生成的 id 无法证明归属；归属仍以
 * {@code (tenantId,userId,sessionId)} 为准（唯一性不等于隔离）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiSessionController {

	/** 发号撞号/暂不可用的机器可读码（503，可重试）。 */
	public static final String SESSION_ID_UNAVAILABLE = "SESSION_ID_UNAVAILABLE";

	private final IdGenerator idGenerator;

	private final AiSessionService sessionService;

	public AiSessionController(IdGenerator idGenerator, AiSessionService sessionService) {
		this.idGenerator = idGenerator;
		this.sessionService = sessionService;
	}

	@PostMapping("/session")
	public ResponseEntity<ApiResponse<Map<String, Object>>> create() {
		// 身份缺失即失败（不落默认身份）；HTTP 边界已校验，这里再取一次保证"无上下文不登记"。
		long tenantId = RequestContextUtils.getTenantId();
		long userId = RequestContextUtils.getUserId();
		long sessionId = this.idGenerator.nextId();
		// ensure 是原子"不存在才创建"：false = 本次真正登记成功；true = 撞上已有会话（未被覆盖）。
		if (this.sessionService.ensure(String.valueOf(sessionId), tenantId, userId)) {
			return unavailable("会话号 " + sessionId + " 已存在，旧会话未被覆盖，请稍后重试");
		}
		return ok(sessionId);
	}

	private static ResponseEntity<ApiResponse<Map<String, Object>>> ok(long sessionId) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("sessionId", sessionId);
		// 权威值：十进制字符串。JSON number 超过 2^53 后不可直读（见类注）。
		data.put("sessionIdText", Long.toString(sessionId));
		return ResponseEntity.ok(ApiResponse.ok(data));
	}

	private static ResponseEntity<ApiResponse<Map<String, Object>>> unavailable(String message) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(ApiResponse.error(SESSION_ID_UNAVAILABLE, message));
	}

}
