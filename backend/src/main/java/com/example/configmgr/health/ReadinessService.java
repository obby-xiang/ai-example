package com.example.configmgr.health;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.common.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 就绪判定（{@code GET /health/ready} 的唯一取数点）。
 *
 * <h2>ready 查什么（硬依赖 vs 降级维度）</h2>
 * <b>硬依赖</b>（{@link ReadinessProbe#required()} 为 true，任一 DOWN ⇒ 503 / LB 摘流）：
 * <ul>
 * <li>{@code db}：数据库（一库多实例形态下的唯一共享真源）；</li>
 * <li>{@code storage}：文件存储目录（上传/导出/知识库语料的落点）；</li>
 * <li>{@code seed}：种子播种是否走完（播种中的实例数据不全，不能接业务流量）。</li>
 * </ul>
 * <b>降级维度</b>（DOWN 只记 {@code degraded}，不改 ready）：
 * <ul>
 * <li>{@code redis}：AI 会话/帧/租约/限流设施。Redis 故障时 AI 面降级（capability 标 degraded），
 * 但业务面（配置目录/数据/任务/导出）仍可服务；把 Redis 算进 ready 会在一次 Redis 抖动时
 * 让统一代理把所有实例一起摘掉（见 {@link RedisReadinessProbe} 类注）。</li>
 * </ul>
 * 分级由探针自己声明（{@link ReadinessProbe#required()}），本类<b>不按名字特判</b>。
 *
 * <h2>有界执行</h2>
 * 所有探针经 {@link ReadinessProbeRunner} 在固定容量 daemon 线程池里并发执行，共享一个总时限；
 * 超时/饱和/异常都收敛成该维度 DOWN。端点因此始终能在 {@code app.health.readiness.probe-timeout}
 * 量级内给出 200/503，不会被单个慢依赖（如远程共享卷上的目录探测）拖住。
 *
 * <h2>AI key 只是 capability</h2>
 * AI 未配置（无 provider/key/MODEL）**不影响 ready**：业务 API 与 CI 都不依赖 key，
 * AI 端点本来就是调用时惰性 503 AI_UNAVAILABLE。它只出现在 {@code capabilities.ai}；
 * Redis 降级时额外标 {@code capabilities.ai.degraded=true}（"AI 传输面不可用"）。
 * {@code /api/ai/health} 的既有语义不变。
 *
 * <h2>路由摘除（draining）</h2>
 * 应用开始关闭时（{@link ContextClosedEvent}）立刻置 draining，之后 {@code /health/ready} 一律 503
 * 且**不再探测依赖**（快路径：摘流判定不需要知道依赖此刻的状态），让 LB 在连接被容器切断之前
 * 先把本实例摘掉；配合 {@code server.shutdown=graceful}（见 application-postgresql.yml）
 * 实现"先摘流、后在途请求收尾"。LB 侧"探测到 503 → 真正停止转发"有一段间隔，
 * 需由部署侧 preStop/deregistration delay 覆盖。
 *
 * <h2>fail 的口径</h2>
 * 探针抛异常不会让端点 500：收敛成该维度 DOWN + 异常摘要（诊断可读、不带堆栈）。
 */
@Slf4j
@Component
public class ReadinessService {

	private final List<ReadinessProbe> probes;

	private final ReadinessProbeRunner runner;

	private final AiConfigService aiConfigService;

	private final AtomicBoolean draining = new AtomicBoolean(false);

	private volatile Instant drainingSince;

	@Autowired
	public ReadinessService(List<ReadinessProbe> probes, ObjectProvider<AiConfigService> aiConfigService,
			ReadinessProbeRunner runner) {
		this.probes = List.copyOf(probes);
		this.aiConfigService = aiConfigService == null ? null : aiConfigService.getIfAvailable();
		this.runner = runner;
	}

	/** 测试/极简装配用：无 AI 能力段，用缺省探测执行器（2s / 4 线程 / 队列 8）。 */
	public ReadinessService(List<ReadinessProbe> probes) {
		this(probes, null, ReadinessProbeRunner.defaults());
	}

	/** 测试用：无 AI 能力段 + 指定执行器（超时/饱和用例）。 */
	public ReadinessService(List<ReadinessProbe> probes, ReadinessProbeRunner runner) {
		this(probes, null, runner);
	}

	/** 关闭开始：后续 ready 一律 503（幂等）。 */
	public void markDraining() {
		if (this.draining.compareAndSet(false, true)) {
			this.drainingSince = Instant.now();
			log.info("实例进入 draining：/health/ready 将返回 503，等待 LB 摘流");
		}
	}

	@EventListener(ContextClosedEvent.class)
	public void onContextClosed(ContextClosedEvent event) {
		markDraining();
	}

	public boolean isDraining() {
		return this.draining.get();
	}

	/**
	 * 现算一次就绪快照（不缓存：LB 需要的正是"此刻能不能接流量"）。
	 *
	 * @return 快照；{@link Snapshot#ready()} 为 false 时端点回 503
	 */
	public Snapshot check() {
		boolean isDraining = this.draining.get();
		Map<String, Object> checks = new LinkedHashMap<>();
		List<String> down = new ArrayList<>();
		List<String> degraded = new ArrayList<>();
		Set<String> degradedCapabilities = new HashSet<>();

		if (!isDraining) {
			for (ReadinessProbeRunner.Outcome outcome : this.runner.probe(this.probes)) {
				ReadinessProbe probe = outcome.probe();
				ProbeResult result = outcome.result();
				String name = probe == null ? "unknown" : probe.name();
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("status", result.up() ? "UP" : "DOWN");
				if (result.detail() != null) {
					entry.put("detail", result.detail());
				}
				entry.put("required", probe == null || probe.required());
				checks.put(name, entry);
				if (!result.up()) {
					if (probe != null && !probe.required()) {
						degraded.add(name);
						// 能力面由探针自己声明（不是"任意降级维度都算 AI 降级"）
						if (probe.capability() != null) {
							degradedCapabilities.add(probe.capability());
						}
					}
					else {
						down.add(name);
					}
				}
			}
		}
		boolean ready = !isDraining && down.isEmpty();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", ready ? "UP" : "DOWN");
		body.put("draining", isDraining);
		if (this.drainingSince != null) {
			body.put("drainingSince", this.drainingSince.toString());
		}
		body.put("ready", ready);
		body.put("down", down);
		body.put("degraded", degraded);
		body.put("checks", checks);
		body.put("capabilities", Map.of("ai",
				aiCapability(degradedCapabilities.contains(ReadinessProbe.CAPABILITY_AI))));
		body.put("checkedAt", Instant.now().toString());
		return new Snapshot(ready, body);
	}

	/**
	 * AI 能力段（**只作 capability**，不参与 ready）。
	 *
	 * <p>值来自内存快照（{@link AiConfigService#availability()}），不打上游、不读密钥；
	 * 只回 {@code configured/code/degraded}，永不回 key 本身或 key 片段。
	 *
	 * @param transportDegraded 承载 AI 能力面的探针（Redis，声明 {@code capability()="ai"}）已 DOWN
	 * ⇒ AI 传输面不可用。与 {@code configured} 是两件事：配置齐全但 Redis 掉了，AI 同样不可用，
	 * 运维要在一个字段上看得出来。**只有声明了 AI 能力面的维度**才会点亮它，其它 required=false
	 * 维度 DOWN 不影响本字段（见 {@link ReadinessProbe#capability()}）。
	 */
	private Map<String, Object> aiCapability(boolean transportDegraded) {
		if (this.aiConfigService == null) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("configured", false);
			out.put("code", ErrorCodes.AI_UNAVAILABLE);
			out.put("reason", "not-wired");
			out.put("degraded", transportDegraded);
			return out;
		}
		try {
			AiConfigService.Availability availability = this.aiConfigService.availability();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("configured", availability.available());
			out.put("code", availability.code());
			out.put("degraded", transportDegraded);
			return out;
		}
		catch (RuntimeException ex) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("configured", false);
			out.put("code", ErrorCodes.AI_UNAVAILABLE);
			out.put("reason", ex.getClass().getSimpleName());
			out.put("degraded", transportDegraded);
			return out;
		}
	}

	/** 一次就绪判定的结果（{@code body} 直接作为响应体）。 */
	public record Snapshot(boolean ready, Map<String, Object> body) {
	}

}
