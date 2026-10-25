package com.example.configmgr.health;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程级健康端点（LB/K8s/编排脚本的探针入口）。
 *
 * <h2>两个端点分工</h2>
 * <ul>
 * <li>{@code GET /health/live}：<b>存活</b>。只回答"这个进程还在跑、还能处理请求"，
 * <b>不</b>查任何外部依赖。依赖故障时它必须仍是 200 —— 否则编排器会把"Redis 抖动"
 * 判成"进程死了"并重启，重启并不能修好 Redis，只会放大震荡。draining 期间同样 200。</li>
 * <li>{@code GET /health/ready}：<b>就绪</b>。只查**硬依赖**（DB/存储/种子，由
 * {@link ReadinessProbe#required()} 声明），任一不可用、或实例正在关闭 ⇒ <b>503</b>；
 * LB 据此把本实例从路由摘掉（"readiness 支持路由摘除"）。Redis 是**降级维度**：
 * DOWN 时仍在响应体里如实报告（{@code checks.redis=DOWN}、{@code degraded}、AI capability 标降级），
 * 但不摘流 —— 详见 {@link ReadinessService} 与 {@link RedisReadinessProbe} 的类注。</li>
 * </ul>
 *
 * <h2>形状</h2>
 * 两个端点都回 JSON（探针脚本普遍会解析 status 字段）：{@code status=UP|DOWN}、
 * {@code ready}、{@code draining}、{@code checks}（逐维度 status/required/detail）、
 * {@code down}（拦 ready 的硬依赖）、{@code degraded}（不拦 ready 的降级维度）、
 * {@code capabilities.ai}（configured/code/degraded）。
 * 响应带 {@code Cache-Control: no-store}：探针结果不可缓存，否则摘流会滞后。
 *
 * <p>判定本身有界（{@link ReadinessProbeRunner}：共享总时限 + 固定容量 daemon 池），
 * 端点不会因为某个慢依赖而无限等待。
 *
 * <h2>为什么不用 Actuator</h2>
 * 本仓没有 actuator 依赖；这里的两条端点语义（"AI 只作 capability"、seed 维度、Redis 降级分级）
 * 需要自定义判据，引一套 actuator + 自定义 HealthIndicator 反而多一层装配面。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

	private final ReadinessService readinessService;

	public HealthController(ReadinessService readinessService) {
		this.readinessService = readinessService;
	}

	/** 存活：恒 200（draining 也 200），不带任何外部依赖探测。 */
	@GetMapping("/live")
	public ResponseEntity<Map<String, Object>> live() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", "UP");
		body.put("draining", this.readinessService.isDraining());
		body.put("checkedAt", Instant.now().toString());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 就绪：200 = 可接流量；503 = 摘流（依赖不可用或正在关闭）。 */
	@GetMapping("/ready")
	public ResponseEntity<Map<String, Object>> ready() {
		ReadinessService.Snapshot snapshot = this.readinessService.check();
		ResponseEntity.BodyBuilder builder = ResponseEntity
				.status(snapshot.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
				.cacheControl(CacheControl.noStore());
		return builder.body(snapshot.body());
	}

}
