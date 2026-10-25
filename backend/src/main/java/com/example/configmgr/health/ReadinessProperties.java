package com.example.configmgr.health;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.time.Duration;

/**
 * 就绪探测的有界执行参数（{@code app.health.readiness.*}）。
 *
 * <p>三项都是"让端点按期给结论"的护栏，不是功能开关：改小会更早判 DOWN，改大更宽容，
 * 但都不允许 0/负值（那等于取消时限 —— 端点重新变成可被单个慢探针无限拖住）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.health.readiness")
public class ReadinessProperties {

	/** draining 窗口上界：与 {@code timeout-per-shutdown-phase} 相加即停机总时长，必须有上界。 */
	static final Duration MAX_DRAIN_WINDOW = Duration.ofSeconds(60);

	/**
	 * 一次就绪判定的**共享**总时限（所有维度合计，不是每维度各一份）。
	 * 缺省 2s：LB/编排器探测超时通常 1–5s，留出代理侧余量。
	 */
	private Duration probeTimeout = ReadinessProbeRunner.DEFAULT_TIMEOUT;

	/**
	 * 探测线程数（固定，不随探测次数增长）。应 ≥ 探针维度数，否则多出的维度会排队等到共享时限。
	 */
	private int probeThreads = ReadinessProbeRunner.DEFAULT_THREADS;

	/**
	 * 等待队列容量（**必须 > 0**；饱和即该维度记 DOWN，不改用调用线程执行）。
	 */
	private int probeQueueCapacity = ReadinessProbeRunner.DEFAULT_QUEUE_CAPACITY;

	/**
	 * draining 窗口（{@code app.health.readiness.drain-window}；缺省 {@code 0} = 关闭，单实例形态行为不变）。
	 *
	 * <p>关闭流程里 {@code /health/ready} 变成 503 之后，必须<b>还给 LB 一段能探测到 503 的时间</b>，
	 * 再让 web 连接器停止接受新连接。Spring Boot 的 {@code server.shutdown=graceful} 只保证
	 * "停接新请求 + 等在途请求收尾"，它在同一个 {@code ContextClosedEvent} 里<b>立刻</b>关闭监听
	 * socket（实测：SIGTERM 后约 55ms 起新连接全部 ECONNREFUSED），因此没有窗口时摘流链只剩
	 * 一条事后日志 —— 探针永远读不到 {@code ready=false/draining=true}。
	 *
	 * <p>取值必须覆盖 LB 的"探测间隔 × 失败阈值"，且与
	 * {@code spring.lifecycle.timeout-per-shutdown-phase} 一起构成停机总时长上界。
	 */
	private Duration drainWindow = Duration.ZERO;

	@PostConstruct
	void verify() {
		if (this.probeTimeout == null || this.probeTimeout.isZero() || this.probeTimeout.isNegative()) {
			throw new IllegalStateException("app.health.readiness.probe-timeout 必须 > 0（当前 = "
					+ this.probeTimeout + "）：0/负值等于取消就绪探测的时限");
		}
		if (this.probeThreads <= 0) {
			throw new IllegalStateException("app.health.readiness.probe-threads 必须 > 0（当前 = "
					+ this.probeThreads + "）");
		}
		if (this.probeQueueCapacity <= 0) {
			throw new IllegalStateException("app.health.readiness.probe-queue-capacity 必须 > 0（当前 = "
					+ this.probeQueueCapacity + "）：0 容量的有界队列不合法");
		}
		if (this.drainWindow == null || this.drainWindow.isNegative()) {
			throw new IllegalStateException("app.health.readiness.drain-window 不得为负（当前 = "
					+ this.drainWindow + "）：0 = 关闭 draining 窗口，负值没有语义");
		}
		if (this.drainWindow.compareTo(MAX_DRAIN_WINDOW) > 0) {
			throw new IllegalStateException("app.health.readiness.drain-window 不得超过 " + MAX_DRAIN_WINDOW
					+ "（当前 = " + this.drainWindow + "）：它与 timeout-per-shutdown-phase 相加就是停机总时长，"
					+ "过大会被编排器的 SIGKILL 宽限期截断");
		}
	}

}
