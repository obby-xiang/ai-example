package com.example.configmgr.ai.gate;

import org.springframework.ai.tool.annotation.Tool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受控的"慢后端工具"替身：进入 {@code tool_call} 之后停住，直到用例放行。
 *
 * <p>为什么需要它：park 的两处所有权窗口（跨实例决策 / 多卡乱序决策）都<b>必须</b>发生在
 * "本批请求帧已整批公布、内联循环还没走到那张卡"之间 —— 也就是前序后端工具的<b>执行时长</b>里。
 * 没有一把可控的闸门，"窗口"只是竞态，用例会时绿时红；有了它，两条序都能被钉死。
 *
 * <p>{@link #release} 必须在 {@code finally} 里放行：本工具跑在
 * {@code SpToolCallingManager} 的独立执行超时池线程上，卡住不还会让后续用例排队。
 */
public class FakeBlockingTool {

	/** 已进入工具体（用例据此判定"循环正卡在前序后端工具上"）。 */
	public final CountDownLatch entered = new CountDownLatch(1);

	/** 放行（重复放行无副作用）。 */
	public final CountDownLatch release = new CountDownLatch(1);

	/** 被调用次数（断言"恰执行一次"）。 */
	public final AtomicInteger invocations = new AtomicInteger();

	@Tool(name = "blocking_tool", description = "受控替身：进入工具体后等待用例放行（不写库）")
	public String blocking() {
		this.invocations.incrementAndGet();
		this.entered.countDown();
		try {
			if (!this.release.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("受控替身未被放行（用例未在时限内 release）");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("受控替身等待被中断", ex);
		}
		return "受控替身已完成（未写库）";
	}

}
