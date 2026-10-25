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
	}

}
