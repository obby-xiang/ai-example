package com.example.configmgr.perf.production;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.task.service.RedisTaskEventBus;
import com.example.configmgr.task.service.TaskSseService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class TaskEventScenario {

	private TaskEventScenario() {
	}

	static final class PubsubFanout implements ProductionScenario {

		@Override
		public String id() {
			return "task_event_pubsub_fanout";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			dataset.attributes.put("taskId", support.jobId(dataset, 11));
			dataset.attributes.put("otherTaskId", support.jobId(dataset, 12));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			long taskId = ((Number) dataset.attributes.get("taskId")).longValue();
			long otherTaskId = ((Number) dataset.attributes.get("otherTaskId")).longValue();
			RedisTaskEventBus publisher = TaskEventScenarioSupport.bus(support, null);
			RedisTaskEventBus first = TaskEventScenarioSupport.bus(support, null);
			RedisTaskEventBus second = TaskEventScenarioSupport.bus(support, null);
			List<String> publisherFrames = new CopyOnWriteArrayList<>();
			List<String> firstFrames = new CopyOnWriteArrayList<>();
			List<String> secondFrames = new CopyOnWriteArrayList<>();
			List<String> otherTaskFrames = new CopyOnWriteArrayList<>();
			try {
				support.stage(dataset, "pubsub_subscribe_three_instances", () -> {
					support.require(dataset, "publisher_listener_registered", publisher.subscribe(taskId, publisherFrames::add),
							"publisher also listens to prove own-echo suppression");
					support.require(dataset, "first_remote_listener_registered", first.subscribe(taskId, firstFrames::add),
							"first remote instance subscribed through real Redis");
					support.require(dataset, "second_remote_listener_registered", second.subscribe(taskId, secondFrames::add),
							"second remote instance subscribed through real Redis");
					support.require(dataset, "other_task_listener_registered", first.subscribe(otherTaskId, otherTaskFrames::add),
							"per-task channel isolation fixture");
				});
				String frame1 = "{\"type\":\"JOB_PROGRESS\",\"data\":{\"seq\":1}}";
				String frame2 = "{\"type\":\"JOB_DONE\",\"data\":{\"seq\":2}}";
				support.stage(dataset, "pubsub_cross_instance_fanout", () -> {
					support.require(dataset, "first_publish_accepted", publisher.publish(taskId, frame1),
							"real PUBLISH accepted");
					TaskEventScenarioSupport.awaitSize(firstFrames, 1, "first remote fanout");
					TaskEventScenarioSupport.awaitSize(secondFrames, 1, "second remote fanout");
					TaskEventScenarioSupport.awaitNoFrames(publisherFrames, "publisher echo suppression");
					support.require(dataset, "cross_instance_exactly_once", firstFrames.equals(List.of(frame1))
							&& secondFrames.equals(List.of(frame1)), "both remote instances received the original frame once");
					support.require(dataset, "own_echo_not_duplicated", publisherFrames.isEmpty(),
							"publisher local listener did not receive its own envelope");
					support.require(dataset, "task_channel_isolated", otherTaskFrames.isEmpty(),
							"another task channel received no frame");
				});
				support.stage(dataset, "pubsub_unsubscribe_and_remaining_fanout", () -> {
					first.unsubscribe(taskId);
					support.require(dataset, "second_publish_accepted", publisher.publish(taskId, frame2),
							"second real PUBLISH accepted");
					TaskEventScenarioSupport.awaitSize(secondFrames, 2, "remaining subscriber fanout");
					TaskEventScenarioSupport.awaitNoFrames(publisherFrames, "publisher echo remains suppressed");
					support.require(dataset, "unsubscribed_instance_stopped", firstFrames.equals(List.of(frame1)),
							"unsubscribed instance received no second frame");
					support.require(dataset, "remaining_instance_received_second", secondFrames.equals(List.of(frame1, frame2)),
							"remaining instance received both frames in order");
					support.require(dataset, "publisher_metrics_real_publish",
							Long.valueOf(2L).equals(publisher.metrics().get("publishedFrames")),
							"two real PUBLISH calls recorded");
				});
				support.stage(dataset, "pubsub_final_unsubscribe_and_resource_cleanup", () -> {
					publisher.unsubscribe(taskId);
					first.unsubscribe(taskId);
					first.unsubscribe(otherTaskId);
					second.unsubscribe(taskId);
					support.require(dataset, "fanout_final_subscriptions_zero",
							((Number) publisher.metrics().get("subscribedTasks")).longValue() == 0L
									&& ((Number) first.metrics().get("subscribedTasks")).longValue() == 0L
									&& ((Number) second.metrics().get("subscribedTasks")).longValue() == 0L,
							"publisher and both remote instances end with subscribedTasks=0");
					TaskEventScenarioSupport.stop(publisher, first, second);
					dataset.attributes.put("listenerContainersStopped", true);
					dataset.attributes.put("retryMechanism", "not-used-direct-bus-no-scheduler");
				});
				dataset.state = "measured";
			}
			finally {
				publisher.unsubscribe(taskId);
				first.unsubscribe(taskId);
				first.unsubscribe(otherTaskId);
				second.unsubscribe(taskId);
				TaskEventScenarioSupport.stop(publisher, first, second);
				support.faults.clear();
			}
		}
	}

	static final class SlowEmitterSaturation implements ProductionScenario {

		@Override
		public String id() {
			return "task_event_slow_emitter_saturation";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			dataset.attributes.put("taskId", support.jobId(dataset, 21));
			dataset.attributes.put("frameCount", support.intEnv("PERF_TASK_EVENT_SATURATION_FRAMES", 32));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			long taskId = ((Number) dataset.attributes.get("taskId")).longValue();
			int frameCount = ((Number) dataset.attributes.get("frameCount")).intValue();
			if (frameCount < 8 || frameCount > 256) {
				throw new IllegalArgumentException("PERF_TASK_EVENT_SATURATION_FRAMES 必须在 8..256");
			}
			ThreadPoolExecutor dispatch = TaskEventScenarioSupport.executor("perf-task-dispatch", 1, 1);
			ThreadPoolExecutor subscriptions = TaskEventScenarioSupport.executor("perf-task-subscription", 1, 4);
			RedisTaskEventBus publisher = TaskEventScenarioSupport.bus(support, null);
			RedisTaskEventBus receiver = new RedisTaskEventBus(support.redis, support.mapper, "redis", null,
					dispatch, subscriptions);
			CountDownLatch slowWriteStarted = new CountDownLatch(1);
			CountDownLatch releaseSlowWrite = new CountDownLatch(1);
			try {
				AppProperties properties = new AppProperties();
				properties.getTask().getSse().setWriteTimeout(Duration.ofMillis(500L));
				properties.getTask().getSse().setEmitterTimeout(Duration.ofSeconds(5L));
				TaskSseService service = new TaskSseService(support.mapper, properties, receiver);
				SseEmitter original = service.subscribe(taskId);
				BlockingEmitter slow = new BlockingEmitter(slowWriteStarted, releaseSlowWrite);
				TaskEventScenarioSupport.replaceEmitter(service, taskId, original, slow);
				support.stage(dataset, "slow_emitter_real_pubsub_saturation", () -> {
					long started = System.nanoTime();
					for (int i = 0; i < frameCount; i++) {
						String frame = "{\"type\":\"JOB_PROGRESS\",\"data\":{\"seq\":" + i + "}}";
						support.require(dataset, "saturation_publish_" + i, publisher.publish(taskId, frame),
								"caller-side PUBLISH remains independent of the slow remote emitter");
					}
					long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
					dataset.attributes.put("publisherElapsedMs", elapsedMs);
					TaskEventScenarioSupport.awaitSize(slowWriteStarted, "slow emitter write started");
					support.require(dataset, "publisher_released_while_write_blocked", releaseSlowWrite.getCount() == 1L,
							"all real PUBLISH calls returned while the isolated SSE write remained blocked");
					ProductionDomainSupport.await(() -> ((Number) receiver.metrics().get("dispatchRejections")).longValue() > 0L,
							5_000L, "bounded dispatch rejection");
					Map<String, Object> metrics = receiver.metrics();
					dataset.attributes.put("saturationMetrics", metrics);
					support.require(dataset, "bounded_dispatch_rejections_observed",
							((Number) metrics.get("dispatchRejections")).longValue() > 0L,
							"one-thread/one-queue dispatch pool rejected excess real Redis frames");
					support.require(dataset, "rejections_visible_as_dispatch_failures",
							((Number) metrics.get("dispatchFailures")).longValue()
									>= ((Number) metrics.get("dispatchRejections")).longValue(),
							"rejection and failure counters are coherent");
					support.require(dataset, "dispatch_pool_stays_bounded", dispatch.getMaximumPoolSize() == 1
							&& dispatch.getPoolSize() <= 1 && dispatch.getQueue().remainingCapacity() >= 0,
							"slow emitter cannot create an unbounded dispatch pool");
				});
				releaseSlowWrite.countDown();
				ProductionDomainSupport.await(() -> ((Number) receiver.metrics().get("subscribedTasks")).longValue() == 0L,
						5_000L, "timed-out emitter removed and remote channel unsubscribed");
				support.require(dataset, "slow_emitter_removed_and_unsubscribed",
						((Number) receiver.metrics().get("subscribedTasks")).longValue() == 0L,
						"write-budget timeout removed the slow emitter and its remote subscription");
				support.stage(dataset, "slow_emitter_snapshot_polling_external_browser_required", () -> {
					dataset.attributes.put("snapshotPollingCoverage", "PARTIAL");
					dataset.attributes.put("coverageLimit", "EXTERNAL_BROWSER_REQUIRED");
					support.coverageStatus = "PARTIAL";
					support.coverageReason = "task_event_slow_emitter_saturation: EXTERNAL_BROWSER_REQUIRED";
				});
				support.stage(dataset, "slow_emitter_final_unsubscribe_and_resource_cleanup", () -> {
					publisher.unsubscribe(taskId);
					receiver.unsubscribe(taskId);
					TaskEventScenarioSupport.stop(publisher, receiver);
					dispatch.shutdownNow();
					subscriptions.shutdownNow();
					support.require(dataset, "slow_final_subscriptions_zero",
							((Number) publisher.metrics().get("subscribedTasks")).longValue() == 0L
									&& ((Number) receiver.metrics().get("subscribedTasks")).longValue() == 0L,
							"slow scenario ends with subscribedTasks=0 on both instances");
					support.require(dataset, "slow_executors_stopped", dispatch.isShutdown() && subscriptions.isShutdown(),
							"bounded dispatch and subscription executors are explicitly stopped");
					dataset.attributes.put("listenerContainersStopped", true);
					dataset.attributes.put("retryMechanism", "not-used-direct-service-no-scheduler");
				});
				dataset.state = "measured";
			}
			finally {
				releaseSlowWrite.countDown();
				publisher.unsubscribe(taskId);
				receiver.unsubscribe(taskId);
				TaskEventScenarioSupport.stop(publisher, receiver);
				dispatch.shutdownNow();
				subscriptions.shutdownNow();
				support.faults.clear();
			}
		}
	}

	static final class RedisDownLazyStart implements ProductionScenario {

		@Override
		public String id() {
			return "task_event_redis_down_lazy_start";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			dataset.attributes.put("taskId", support.jobId(dataset, 31));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			long taskId = ((Number) dataset.attributes.get("taskId")).longValue();
			RedisAvailability availability = new RedisAvailability(support.redis);
			RedisTaskEventBus subscriber = TaskEventScenarioSupport.bus(support, availability);
			RedisTaskEventBus publisher = TaskEventScenarioSupport.bus(support, availability);
			List<String> received = new CopyOnWriteArrayList<>();
			try {
				support.stage(dataset, "lazy_constructor_zero_redis_commands", () -> {
					CountingRedis.Snapshot before = support.commandMetrics.snapshot();
					RedisTaskEventBus lazy = TaskEventScenarioSupport.bus(support, availability);
					CountingRedis.Snapshot after = support.commandMetrics.snapshot();
					support.require(dataset, "constructor_emits_no_commands", before.equals(after),
							"constructing a bus does not open a subscription or issue PING");
					support.require(dataset, "constructor_has_no_subscribers",
							((Number) lazy.metrics().get("subscribedTasks")).longValue() == 0L,
							"lazy container has no task subscription");
					TaskEventScenarioSupport.stop(lazy);
				});
				support.stage(dataset, "lazy_baseline_real_ping", () -> support.require(dataset,
						"lazy_baseline_real_ping", availability.isAvailable(),
						"real PING confirms the baseline before fault injection"));
				if (support.faultEnabled("ping")) {
					support.stage(dataset, "redis_down_fast_fail_without_container", () -> {
						support.faults.failNext("ping", "");
						support.require(dataset, "injected_ping_failure_observed", !availability.isAvailable(),
								"real availability probe entered DOWN");
						CountingRedis.Snapshot before = support.commandMetrics.snapshot();
						support.require(dataset, "known_down_subscribe_returns_false", !subscriber.subscribe(taskId, received::add),
								"known DOWN subscription degrades to local-only");
						support.require(dataset, "known_down_publish_returns_false", !publisher.publish(taskId, "{}"),
								"known DOWN publication degrades explicitly");
						CountingRedis.Snapshot after = support.commandMetrics.snapshot();
						support.require(dataset, "known_down_emits_no_more_commands", before.equals(after),
								"known DOWN subscribe/publish emitted no Redis command");
						support.require(dataset, "known_down_has_no_subscriber",
								((Number) subscriber.metrics().get("subscribedTasks")).longValue() == 0L,
								"failed subscription did not retain a listener");
					});
					support.stage(dataset, "redis_recovery_real_subscribe_and_publish", () -> {
						ProductionDomainSupport.sleep(RedisAvailability.NEGATIVE_TTL_MS + 100L);
						support.require(dataset, "recovered_subscription", subscriber.subscribe(taskId, received::add),
								"negative TTL elapsed and real PING allowed container startup");
						String frame = "{\"type\":\"JOB_DONE\",\"data\":{\"recovered\":true}}";
						support.require(dataset, "recovered_publish", publisher.publish(taskId, frame),
								"real PUBLISH after Redis recovery");
						TaskEventScenarioSupport.awaitSize(received, 1, "post-recovery pubsub delivery");
						support.require(dataset, "recovered_frame_delivered", received.equals(List.of(frame)),
								"recovered subscription received the real published frame");
					});
				}
				else {
					support.faultSkipped(dataset, "ping");
				}
				if (support.faultEnabled("subscribe")) {
					boundedRetries(support, dataset, taskId + 100L);
					unsubscribeStopsRetry(support, dataset, taskId + 200L);
				}
				else {
					support.faultSkipped(dataset, "subscribe");
				}
				containerRebuild(support, dataset, taskId + 300L);
				support.stage(dataset, "lazy_final_unsubscribe_and_resource_cleanup", () -> {
					subscriber.unsubscribe(taskId);
					publisher.unsubscribe(taskId);
					support.require(dataset, "lazy_final_subscriptions_zero",
							((Number) subscriber.metrics().get("subscribedTasks")).longValue() == 0L
									&& ((Number) publisher.metrics().get("subscribedTasks")).longValue() == 0L,
							"lazy-start scenario ends with subscribedTasks=0");
					TaskEventScenarioSupport.stop(subscriber, publisher);
					dataset.attributes.put("listenerContainersStopped", true);
				});
				dataset.state = "measured";
			}
			finally {
				subscriber.unsubscribe(taskId);
				publisher.unsubscribe(taskId);
				TaskEventScenarioSupport.stop(subscriber, publisher);
				support.faults.clear();
			}
		}

		private static void boundedRetries(ProductionSupport support, Manifest.Dataset dataset, long taskId) {
			RedisTaskEventBus bus = TaskEventScenarioSupport.bus(support, null);
			TaskEventScenarioSupport.ManualScheduler scheduler = new TaskEventScenarioSupport.ManualScheduler();
			TaskSseService service = new TaskSseService(support.mapper, new AppProperties(), bus, scheduler);
			try {
				SseEmitter emitter = support.stage(dataset, "task_event_bounded_real_subscribe_retries", () -> {
					String channel = com.example.configmgr.task.service.TaskEventBus.CHANNEL_PREFIX + taskId;
					long[] expectedDelays = {10L, 20L, 40L, 60L};
					SseEmitter subscribed = null;
					for (int attempt = 1; attempt <= 5; attempt++) {
						support.faults.failNext("subscribe", channel);
						if (attempt == 1) {
							subscribed = service.subscribe(taskId);
						}
						else {
							scheduler.runNext();
						}
						long failures = ((Number) bus.metrics().get("subscribeFailures")).longValue();
						support.require(dataset, "retry_failure_count_" + attempt, failures == attempt,
								"real subscribe failure " + attempt + " is observed by the production state machine");
						if (attempt < 5) {
							support.require(dataset, "retry_backoff_" + expectedDelays[attempt - 1] + "s",
									scheduler.delays.size() == attempt
											&& scheduler.delays.get(attempt - 1) == expectedDelays[attempt - 1],
									"production retry delay is " + expectedDelays[attempt - 1] + "s");
						}
					}
					support.require(dataset, "retry_attempts_capped_at_five", scheduler.executed == 4
							&& scheduler.delays.equals(java.util.List.of(10L, 20L, 40L, 60L)) && scheduler.activeCount() == 0,
							"five failed attempts stop scheduling after the fourth delayed retry");
					support.require(dataset, "retry_exhaustion_has_no_remote_subscription",
							((Number) bus.metrics().get("subscribedTasks")).longValue() == 0L,
							"exhausted retries leave subscribedTasks=0");
					dataset.attributes.put("retryBackoffSeconds", scheduler.delays);
					dataset.attributes.put("retryAttempts", 5);
					return subscribed;
				});
				TaskEventScenarioSupport.removeEmitter(service, taskId, emitter);
			}
			finally {
				bus.unsubscribe(taskId);
				TaskEventScenarioSupport.stop(bus);
				scheduler.close();
				support.faults.clear();
			}
		}

		private static void unsubscribeStopsRetry(ProductionSupport support, Manifest.Dataset dataset, long taskId) {
			RedisTaskEventBus bus = TaskEventScenarioSupport.bus(support, null);
			TaskEventScenarioSupport.ManualScheduler scheduler = new TaskEventScenarioSupport.ManualScheduler();
			TaskSseService service = new TaskSseService(support.mapper, new AppProperties(), bus, scheduler);
			try {
				support.stage(dataset, "task_event_unsubscribe_cancels_pending_retry", () -> {
					String channel = com.example.configmgr.task.service.TaskEventBus.CHANNEL_PREFIX + taskId;
					support.faults.failNext("subscribe", channel);
					SseEmitter emitter = service.subscribe(taskId);
					support.require(dataset, "retry_pending_before_unsubscribe", scheduler.activeCount() == 1,
							"first real subscribe failure schedules one retry");
					TaskEventScenarioSupport.removeEmitter(service, taskId, emitter);
					support.require(dataset, "unsubscribe_cancels_retry_future", scheduler.cancelled == 1
							&& scheduler.activeCount() == 0,
							"last local unsubscribe cancels the pending production retry");
					scheduler.runNext();
					support.require(dataset, "cancelled_retry_never_runs", scheduler.executed == 0
							&& ((Number) bus.metrics().get("subscribeFailures")).longValue() == 1L
							&& ((Number) bus.metrics().get("subscribedTasks")).longValue() == 0L,
							"no subscribe attempt or subscriber remains after cancellation");
				});
			}
			finally {
				bus.unsubscribe(taskId);
				TaskEventScenarioSupport.stop(bus);
				scheduler.close();
				support.faults.clear();
			}
		}

		private static void containerRebuild(ProductionSupport support, Manifest.Dataset dataset, long taskId) {
			RedisTaskEventBus subscriber = TaskEventScenarioSupport.bus(support, null);
			RedisTaskEventBus publisher = TaskEventScenarioSupport.bus(support, null);
			List<String> received = new CopyOnWriteArrayList<>();
			try {
				support.stage(dataset, "task_event_container_recovery_rebuild", () -> {
					support.require(dataset, "initial_container_subscription", subscriber.subscribe(taskId, received::add),
							"initial real Redis listener container starts");
					TaskEventScenarioSupport.stop(subscriber);
					support.require(dataset, "rebuilt_container_subscription", subscriber.subscribe(taskId, received::add),
							"subscribe after stop builds a fresh real listener container");
					String frame = "{\"type\":\"JOB_DONE\",\"data\":{\"containerRebuilt\":true}}";
					support.require(dataset, "rebuilt_container_publish", publisher.publish(taskId, frame),
							"real PUBLISH uses the rebuilt path");
					TaskEventScenarioSupport.awaitSize(received, 1, "rebuilt container delivery");
					support.require(dataset, "rebuilt_container_receives_frame", received.equals(List.of(frame)),
							"fresh container receives the real published frame");
					subscriber.unsubscribe(taskId);
					support.require(dataset, "rebuilt_container_final_unsubscribe",
							((Number) subscriber.metrics().get("subscribedTasks")).longValue() == 0L,
							"rebuilt container also ends with subscribedTasks=0");
					TaskEventScenarioSupport.stop(subscriber, publisher);
					dataset.attributes.put("containerRebuilt", true);
				});
			}
			finally {
				subscriber.unsubscribe(taskId);
				publisher.unsubscribe(taskId);
				TaskEventScenarioSupport.stop(subscriber, publisher);
				support.faults.clear();
			}
		}
	}

	private static final class BlockingEmitter extends SseEmitter {

		private final CountDownLatch started;
		private final CountDownLatch release;

		private BlockingEmitter(CountDownLatch started, CountDownLatch release) {
			super(5_000L);
			this.started = started;
			this.release = release;
		}

		@Override
		public void send(SseEventBuilder event) throws IOException {
			this.started.countDown();
			while (this.release.getCount() > 0L) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25L));
			}
		}
	}
}

final class TaskEventScenarioSupport {

	private TaskEventScenarioSupport() {
	}

	static RedisTaskEventBus bus(ProductionSupport support, RedisAvailability availability) {
		return new RedisTaskEventBus(support.redis, support.mapper, "redis", availability);
	}

	static ThreadPoolExecutor executor(String prefix, int threads, int queueCapacity) {
		return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(queueCapacity), runnable -> {
					Thread thread = new Thread(runnable, prefix);
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	static void awaitSize(List<String> frames, int expected, String detail) {
		ProductionDomainSupport.await(() -> frames.size() >= expected, 5_000L, detail);
	}

	static void awaitSize(CountDownLatch latch, String detail) {
		ProductionDomainSupport.await(() -> latch.getCount() == 0L, 5_000L, detail);
	}

	static void awaitNoFrames(List<String> frames, String detail) {
		ProductionDomainSupport.sleep(100L);
		if (!frames.isEmpty()) {
			throw new IllegalStateException(detail + ": " + frames);
		}
	}

	@SuppressWarnings("unchecked")
	static void replaceEmitter(TaskSseService service, long taskId, SseEmitter expected, SseEmitter replacement) {
		try {
			Field field = TaskSseService.class.getDeclaredField("emitters");
			field.setAccessible(true);
			Map<Long, List<SseEmitter>> emitters = (Map<Long, List<SseEmitter>>) field.get(service);
			List<SseEmitter> target = emitters.get(taskId);
			if (target == null) {
				throw new IllegalStateException("task emitter missing");
			}
			synchronized (target) {
				if (!target.contains(expected)) {
					throw new IllegalStateException("original task emitter missing");
				}
				target.clear();
				target.add(replacement);
			}
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("unable to isolate SSE transport", ex);
		}
	}

	static void removeEmitter(TaskSseService service, long taskId, SseEmitter emitter) {
		try {
			java.lang.reflect.Method method = TaskSseService.class.getDeclaredMethod("remove", Long.class, SseEmitter.class);
			method.setAccessible(true);
			method.invoke(service, taskId, emitter);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("unable to remove isolated task emitter", ex);
		}
	}

	static final class ManualScheduler extends ScheduledThreadPoolExecutor {

		private final Queue<ManualFuture> pending = new ConcurrentLinkedQueue<>();
		final List<Long> delays = new ArrayList<>();
		int executed;
		int cancelled;

		ManualScheduler() {
			super(1);
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
			this.delays.add(unit.toMillis(delay) / 1000L);
			ManualFuture future = new ManualFuture(this, task);
			this.pending.add(future);
			return future;
		}

		void runNext() {
			ManualFuture future = this.pending.poll();
			if (future != null && future.run()) {
				this.executed++;
			}
		}

		int activeCount() {
			int count = 0;
			for (ManualFuture future : this.pending) {
				if (!future.cancelled) {
					count++;
				}
			}
			return count;
		}

		public void close() {
			this.pending.clear();
			shutdownNow();
		}

		private static final class ManualFuture implements ScheduledFuture<Object> {

			private final ManualScheduler owner;
			private final Runnable task;
			private boolean cancelled;
			private boolean done;

			private ManualFuture(ManualScheduler owner, Runnable task) {
				this.owner = owner;
				this.task = task;
			}

			private boolean run() {
				if (this.cancelled) {
					return false;
				}
				this.task.run();
				this.done = true;
				return true;
			}

			@Override
			public long getDelay(TimeUnit unit) {
				return 0L;
			}

			@Override
			public int compareTo(java.util.concurrent.Delayed other) {
				return 0;
			}

			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				if (this.done || this.cancelled) {
					return false;
				}
				this.cancelled = true;
				this.owner.cancelled++;
				return true;
			}

			@Override
			public boolean isCancelled() {
				return this.cancelled;
			}

			@Override
			public boolean isDone() {
				return this.done || this.cancelled;
			}

			@Override
			public Object get() {
				if (this.cancelled) {
					throw new java.util.concurrent.CancellationException();
				}
				if (!this.done) {
					throw new IllegalStateException("manual future has not run");
				}
				return null;
			}

			@Override
			public Object get(long timeout, TimeUnit unit) {
				return get();
			}
		}
	}

	static void stop(RedisTaskEventBus... buses) {
		for (RedisTaskEventBus bus : buses) {
			try {
				java.lang.reflect.Method method = RedisTaskEventBus.class.getDeclaredMethod("stop");
				method.setAccessible(true);
				method.invoke(bus);
			}
			catch (ReflectiveOperationException ex) {
				throw new IllegalStateException("unable to stop task event bus", ex);
			}
		}
	}
}
