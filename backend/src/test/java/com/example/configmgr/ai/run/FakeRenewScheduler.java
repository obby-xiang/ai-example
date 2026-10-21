package com.example.configmgr.ai.run;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LeaseService.RenewScheduler} 的手动 tick 替身（L10）。
 *
 * <p>为什么不用真 {@code ScheduledExecutorService}：续租用例要断言"每 10s 续一次、release 之后不再续"，
 * 真线程只能用 sleep 等真实时间（慢且不稳）。本替身把"到点了"变成一次显式 {@link #tick()}，
 * 断言因此是确定的；同时保留"release 之后任务被取消、tick 不再执行"的产品语义。
 */
public final class FakeRenewScheduler implements LeaseService.RenewScheduler {

	private final List<Task> tasks = new CopyOnWriteArrayList<>();

	@Override
	public AutoCloseable schedule(Runnable task, Duration interval) {
		Task scheduled = new Task(task, interval);
		this.tasks.add(scheduled);
		return () -> scheduled.cancelled.set(true);
	}

	/** 手动 tick 一次：执行所有仍在跑的续租任务。 */
	public int tick() {
		int ran = 0;
		for (Task task : this.tasks) {
			if (!task.cancelled.get()) {
				task.runnable.run();
				ran++;
			}
		}
		return ran;
	}

	/** 仍活着的续租任务数（release 之后必须归零）。 */
	public long activeCount() {
		return this.tasks.stream().filter(task -> !task.cancelled.get()).count();
	}

	/** 首个续租任务的间隔（null = 从未登记）。 */
	public Duration interval() {
		return this.tasks.isEmpty() ? null : this.tasks.get(0).interval;
	}

	private static final class Task {

		final Runnable runnable;

		final Duration interval;

		final AtomicBoolean cancelled = new AtomicBoolean();

		Task(Runnable runnable, Duration interval) {
			this.runnable = runnable;
			this.interval = interval;
		}

	}

}
