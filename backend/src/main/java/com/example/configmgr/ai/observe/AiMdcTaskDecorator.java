package com.example.configmgr.ai.observe;

import org.springframework.core.task.TaskDecorator;

/**
 * 线程池边界的 MDC 传递装饰（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>Spring 的 {@code ThreadPoolTaskExecutor} 会在<b>提交线程</b>上调用 {@link #decorate(Runnable)}，
 * 因此这里抓到的 {@link org.slf4j.MDC} 快照就是"发起这一轮的那个线程"的锚点：HTTP 线程绑好的
 * {@code runId}/{@code sessionId} 就这样跟着任务进池，任务结束后池线程恢复原状。
 *
 * <p>平台线程不继承 {@code InheritableThreadLocal}，池又是跨轮复用的 —— 没有这一层，
 * 池里跑出来的每一行日志都没有行首锚点（正是本专项要补的缺口）。
 */
public class AiMdcTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		return AiTrace.wrap(runnable);
	}

}
