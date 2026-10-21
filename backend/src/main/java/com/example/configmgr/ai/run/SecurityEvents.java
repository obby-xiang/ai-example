package com.example.configmgr.ai.run;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 安全事件计数（防护批③ B3-5；{@code 调研-AI输入输出安全防护业界惯例} §4.9）。
 *
 * <p>
 * 复用 {@code jobWatchdog.metrics()} / {@code cancellations} 的既有范式：
 * {@code AtomicLong + metrics()} 手写快照，<b>不</b>引 Micrometer（本仓 pom 无 actuator）。
 *
 * <h2>口径：进程内计数</h2>
 * 双实例部署下每个实例各有一份 —— 这与限流的"跨实例共享 Redis"<b>相反</b>，是有意的：
 * 安全事件是"本实例处理了什么"的审计事实，汇总口径见 {@code /health} 的 {@code security.scope}。
 * 需要全局口径时按实例相加（{@code instanceId} 已在 {@code /health} 里给出）。
 *
 * <h2>不记原文</h2>
 * 只累加次数：命中规则 id 列表由调用方就近写入日志/台账，本类不持有任何文本。
 */
@Component
public class SecurityEvents {

	private final AtomicLong inputRejected = new AtomicLong();

	private final AtomicLong inputRedacted = new AtomicLong();

	private final AtomicLong outputRedacted = new AtomicLong();

	private final AtomicLong outputBlocked = new AtomicLong();

	private final AtomicLong rateLimited = new AtomicLong();

	private final AtomicLong contextDowngraded = new AtomicLong();

	private final AtomicLong payloadTruncated = new AtomicLong();

	public void inputRejected() {
		this.inputRejected.incrementAndGet();
	}

	/** 输入侧脱敏次数（按"处"计，不是按请求计）。 */
	public void inputRedacted(int count) {
		if (count > 0) {
			this.inputRedacted.addAndGet(count);
		}
	}

	public void outputRedacted(int count) {
		if (count > 0) {
			this.outputRedacted.addAndGet(count);
		}
	}

	public void outputBlocked() {
		this.outputBlocked.incrementAndGet();
	}

	public void rateLimited() {
		this.rateLimited.incrementAndGet();
	}

	public void contextDowngraded(int count) {
		if (count > 0) {
			this.contextDowngraded.addAndGet(count);
		}
	}

	public void payloadTruncated() {
		this.payloadTruncated.incrementAndGet();
	}

	/** 快照（顺序固定，便于 {@code /health} 阅读与用例断言）。 */
	public Map<String, Object> metrics() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", "process");
		out.put("inputRejected", this.inputRejected.get());
		out.put("inputRedacted", this.inputRedacted.get());
		out.put("outputRedacted", this.outputRedacted.get());
		out.put("outputBlocked", this.outputBlocked.get());
		out.put("rateLimited", this.rateLimited.get());
		out.put("contextDowngraded", this.contextDowngraded.get());
		out.put("payloadTruncated", this.payloadTruncated.get());
		return out;
	}

}
