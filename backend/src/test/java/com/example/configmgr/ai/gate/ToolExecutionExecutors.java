package com.example.configmgr.ai.gate;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.configmgr.ai.tenant.RequestContextTaskDecorator;

/**
 * 测试用工具超时执行器：生产装配把它交给 {@code RequestContextTaskDecorator} 捕获提交线程的
 * TTL 快照（含 {@code RequestContextUtils} 身份），工具超时/执行线程因此能继续解析本轮 run 键。
 *
 * <p>为什么不能用裸池：{@code RunRegistry.of} → {@code LocalRunKey.of} 要求身份；池线程没有身份时
 * 只能在入口 fail-closed（不得匿名放行）。这里逐任务在<b>提交线程</b>做装饰，与生产同口径。
 */
public final class ToolExecutionExecutors {

	private ToolExecutionExecutors() {
	}

	private static final java.util.concurrent.ScheduledExecutorService RETRY =
			Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "ai-confirm-retry-test");
				thread.setDaemon(true);
				return thread;
			});

	/** 一次性延迟调度（与生产 {@code RetryScheduler} 同形）：真实异步执行，替身不再吞掉重试。 */
	public static ConfirmGate.RetryScheduler retryScheduler() {
		RequestContextTaskDecorator decorator = new RequestContextTaskDecorator();
		return (task, delay) -> {
			long millis = delay == null ? 1L : Math.max(1L, delay.toMillis());
			java.util.concurrent.ScheduledFuture<?> future = RETRY.schedule(decorator.decorate(task), millis,
					TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		};
	}

	public static ExecutorService identityPropagating() {
		ExecutorService raw = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-tool-timeout-test");
			thread.setDaemon(true);
			return thread;
		});
		RequestContextTaskDecorator decorator = new RequestContextTaskDecorator();
		return new AbstractExecutorService() {

			@Override
			public void execute(Runnable command) {
				raw.execute(decorator.decorate(command));
			}

			@Override
			public void shutdown() {
				raw.shutdown();
			}

			@Override
			public java.util.List<Runnable> shutdownNow() {
				return raw.shutdownNow();
			}

			@Override
			public boolean isShutdown() {
				return raw.isShutdown();
			}

			@Override
			public boolean isTerminated() {
				return raw.isTerminated();
			}

			@Override
			public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
				return raw.awaitTermination(timeout, unit);
			}
		};
	}
}
