package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.AiSessionPurgeService;
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
 * 完整会话销毁端点（{@code DELETE /api/ai/session/{sessionId}}；旧
 * {@code DELETE /api/ai/history/{sessionId}} 保留兼容并映射到同一实现）。
 *
 * <h2>为什么旧路径也走这里</h2>
 * 旧 {@code /history} 只清记忆窗口（明确保留 run/ctx/session），与用户 2026-10-25 的最终口径
 * "会话销毁 = 该会话 AI 数据全删，业务数据不碰"冲突。两条路径现在都委托
 * {@link AiSessionPurgeService}，<b>不存在第二套删除语义</b>；旧响应字段（含 {@code memoryKey} /
 * {@code removedFromIndex} / {@code runActive} / {@code activeRunId}）保留，新增字段纯 additive。
 *
 * <h2>HTTP 契约</h2>
 * <ul>
 * <li><b>幂等 200</b>：删除不存在的会话、重复删除都返回 200；{@code complete=true} 时无残留；</li>
 * <li><b>部分成功/活跃 run 延后</b>：仍 200，{@code complete=false} + {@code errors}/{@code deferred}
 * 明细（可重试；所有删除幂等）。<b>不恒 true</b>；此时兼容字段 {@code deleted=false}；</li>
 * <li><b>枚举失败/守卫失败/H2 删除失败</b>：200 + {@code aborted=true} + {@code phase}
 * （枚举失败时一个键都没删，H2 失败时 Redis 面未动）；</li>
 * <li><b>Redis 不可用</b>：503 {@code AI_REDIS_UNAVAILABLE}（与全仓 AI 端点同口径）；</li>
 * <li>响应是<b>裸对象</b>（与 {@code /cancel}、旧 {@code DELETE /history} 同族），不套
 * {@code ApiResponse} 信封；</li>
 * <li>不查 AI key（销毁是纯存储面操作，无 key 时也必须能删干净）。</li>
 * </ul>
 *
 * <p><b>本地镜像/草稿</b>：浏览器 {@code sessionStorage} 不归服务端管，响应里的
 * {@code clientCleanup} 给出精确键（{@code ai-mirror:<sid>}、{@code cm.ai.form.draft.<toolCallId>}），
 * 由前端在收到 200 后删除；服务端不伪造“已删”。
 */
@RestController
@RequestMapping("/api/ai")
public class AiSessionPurgeController {

	private static final Logger log = LoggerFactory.getLogger(AiSessionPurgeController.class);

	private final AiSessionPurgeService purgeService;

	private final RunStore runStore;

	private final RedisAvailability redisAvailability;

	public AiSessionPurgeController(AiSessionPurgeService purgeService, RunStore runStore,
			RedisAvailability redisAvailability) {
		this.purgeService = purgeService;
		this.runStore = runStore;
		this.redisAvailability = redisAvailability;
	}

	/**
	 * 完整会话销毁：{@code /session/{sid}} 与旧 {@code /history/{sid}} <b>同一套语义</b>。
	 *
	 * <p>旧端点曾经只清记忆窗口并明确保留 {@code ai:session} / {@code ai:run}；用户 2026-10-25
	 * 的最终口径是"会话销毁 = 该会话 AI 数据全删，业务数据不碰"。故旧路径改为委托同一个
	 * {@link AiSessionPurgeService}，<b>不再存在第二套删除语义</b>。响应保留旧字段
	 * （{@code sessionId/deleted/existed/memoryKey/removedFromIndex/runActive/activeRunId/storedBy/note}），
	 * 并追加 {@code complete/deferred/errors/clientCleanup/...}。
	 *
	 * <p>{@code deleted} 的兼容语义修正：旧实现恒为 {@code true}（"已处于删除状态"）；现在
	 * {@code complete=false}（活跃 run 延后 / 部分失败 / 中止）时它必须为 {@code false}，
	 * 不能让响应宣称"全删干净"。幂等重复删除且无残留时仍为 {@code true}。
	 */
	@DeleteMapping({ "/session/{sessionId}", "/history/{sessionId}" })
	public ResponseEntity<Map<String, Object>> purge(@PathVariable String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(errorBody("BAD_REQUEST", "sessionId 必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(redisUnavailable());
		}
		AiSessionPurgeService.PurgeResult result;
		try {
			result = this.purgeService.purge(sessionId, this.runStore.instanceId());
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(errorBody("BAD_REQUEST", ex.getMessage()));
		}
		Map<String, Object> body = new LinkedHashMap<>(result.toBody());
		// 旧 /history 契约的兼容字段（/session 也一并返回，纯 additive，不引入第二套语义）
		body.put("deleted", result.complete());
		body.put("existed", result.memory().existed());
		body.put("memoryKey", result.memoryKey());
		body.put("removedFromIndex", result.removedFromIndex());
		String activeRunId = result.deferred().isEmpty() ? null : result.deferred().get(0).runId();
		body.put("runActive", activeRunId != null);
		body.put("activeRunId", activeRunId);
		body.put("storedBy", this.runStore.instanceId());
		body.put("note", "完整会话销毁：只删本会话 AI 数据（记忆/run/上下文/附件/反馈/完整历史/会话索引），"
				+ "业务作业、任务文件、staging、配置/provider/model/settings/KB/审计/资源锁/业务文件一概不动。"
				+ "complete=false 表示有 deferred/errors/aborted，按明细重试（删除幂等）；此时 deleted=false。"
				+ "浏览器 sessionStorage 的镜像与草稿由 clientCleanup 交给前端精确清理。");
		log.info("会话销毁端点 sessionId={} path=both complete={} deferred={} errors={}", sessionId,
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
