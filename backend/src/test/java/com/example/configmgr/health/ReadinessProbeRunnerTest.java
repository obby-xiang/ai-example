package com.example.configmgr.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReadinessProbeRunner} 的有界性细节（多实例专项 · 启动底座）。
 *
 * <p>补 {@link ReadinessProbeTimeoutTest} 的"调用方有界"之外的三条硬约束：
 * <ul>
 * <li><b>饱和</b>：池满 + 队列满 ⇒ 该维度直接 DOWN（saturated），不改用调用线程跑；</li>
 * <li><b>线程</b>：daemon（探针卡住不得阻止 JVM 退出）+ 固定命名前缀；</li>
 * <li><b>关闭</b>：执行器关闭后不再提交，全部维度 DOWN，端点仍能按期响应。</li>
 * </ul>
 * 阻塞探针一律用 {@link CountDownLatch}，不 sleep 猜时序。
 *
 * <p><b>边界</b>：{@code cancel(true)} 只能中断线程，杀不死不可中断 native I/O ——
 * 卡住的池线程会占位，池位耗尽后新探测按 saturated 记 DOWN（这正是本用例要锁住的行为）。
 */
class ReadinessProbeRunnerTest {

	@Test
	void saturatedPoolMarksDimensionDownInsteadOfBlockingCaller() throws Exception {
		CountDownLatch never = new CountDownLatch(1);
		try (ReadinessProbeRunner runner = new ReadinessProbeRunner(1, 1, Duration.ofMillis(300))) {
			long startedAt = System.nanoTime();
			List<ReadinessProbeRunner.Outcome> outcomes = runner.probe(List.of(
					blocking("storage", never),
					blocking("db", never),
					blocking("seed", never)));
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

			assertThat(elapsedMs).as("饱和也不能拖住调用方").isLessThan(3_000L);
			assertThat(status(outcomes.get(0))).as("占住唯一线程的维度按时限超时").isEqualTo("DOWN");
			assertThat(detail(outcomes.get(0))).contains("超时");
			assertThat(status(outcomes.get(1))).as("排队中的维度同样按时限 DOWN").isEqualTo("DOWN");
			assertThat(detail(outcomes.get(1))).contains("超时");
			assertThat(status(outcomes.get(2))).as("池满 + 队列满 ⇒ 直接饱和 DOWN").isEqualTo("DOWN");
			assertThat(detail(outcomes.get(2))).contains("饱和");
		}
	}

	@Test
	void probesRunOnBoundedDaemonThreads() throws Exception {
		ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
		List<Boolean> daemonFlags = new java.util.concurrent.CopyOnWriteArrayList<>();
		ReadinessProbe recorder = new ReadinessProbe() {
			@Override
			public String name() {
				return "db";
			}

			@Override
			public ProbeResult check() {
				threadNames.add(Thread.currentThread().getName());
				daemonFlags.add(Thread.currentThread().isDaemon());
				return ProbeResult.up("ok");
			}
		};

		try (ReadinessProbeRunner runner = new ReadinessProbeRunner(2, 2, Duration.ofSeconds(1))) {
			for (int i = 0; i < 5; i++) {
				runner.probe(List.of(recorder));
			}
		}

		assertThat(daemonFlags).as("探针线程必须是 daemon（卡住的探针不得阻止 JVM 退出）")
				.isNotEmpty().containsOnly(true);
		assertThat(threadNames).isNotEmpty()
				.allMatch(name -> name.startsWith("readiness-probe-"));
		assertThat(threadNames.stream().distinct().count())
				.as("线程数有界（threads=2 ⇒ 复用，不随探测次数增长）")
				.isLessThanOrEqualTo(2L);
	}

	@Test
	void closedRunnerReportsDownWithoutSubmitting() {
		ReadinessProbeRunner runner = new ReadinessProbeRunner(1, 1, Duration.ofMillis(500));
		runner.close();

		long startedAt = System.nanoTime();
		List<ReadinessProbeRunner.Outcome> outcomes = runner.probe(List.of(up("db")));
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(elapsedMs).isLessThan(1_000L);
		assertThat(status(outcomes.get(0))).isEqualTo("DOWN");
		assertThat(detail(outcomes.get(0))).contains("已关闭");
	}

	@Test
	void invalidConfigurationFailsFast() {
		assertThatThrownBy(() -> new ReadinessProbeRunner(0, 1, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("probe-threads");
		assertThatThrownBy(() -> new ReadinessProbeRunner(1, 0, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("probe-queue-capacity");
		assertThatThrownBy(() -> new ReadinessProbeRunner(1, 1, Duration.ZERO))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("probe-timeout");
	}

	@Test
	void propertiesRejectNonPositiveTimeoutAndThreads() {
		ReadinessProperties properties = new ReadinessProperties();
		properties.setProbeTimeout(Duration.ZERO);
		assertThatThrownBy(properties::verify).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("probe-timeout");

		ReadinessProperties noThreads = new ReadinessProperties();
		noThreads.setProbeThreads(0);
		assertThatThrownBy(noThreads::verify).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("probe-threads");
	}

	// ---------- helpers ----------

	private static ReadinessProbe up(String name) {
		return new ReadinessProbe() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public ProbeResult check() {
				return ProbeResult.up("ok");
			}
		};
	}

	private static ReadinessProbe blocking(String name, CountDownLatch never) {
		return new ReadinessProbe() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public ProbeResult check() {
				try {
					if (never.await(60, TimeUnit.SECONDS)) {
						return ProbeResult.up("released");
					}
					return ProbeResult.down("latch 超时");
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("probe interrupted");
				}
			}
		};
	}

	private static String status(ReadinessProbeRunner.Outcome outcome) {
		return outcome.result().up() ? "UP" : "DOWN";
	}

	private static String detail(ReadinessProbeRunner.Outcome outcome) {
		return String.valueOf(outcome.result().detail());
	}

}
