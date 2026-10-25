package com.example.configmgr.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 就绪探测的**有界执行**（2026-10-25 跨分支裁决）：{@code /health/ready} 不得被单个阻塞探针拖死。
 *
 * <h2>为什么必须有上界</h2>
 * {@code StorageReadinessProbe} 的 {@code Files.createDirectories/isWritable} 在远程共享卷
 * （NFS/SMB）上可能长时间阻塞（含不可中断的 native I/O）；LB 的探测超时通常 1–5s，
 * 端点一旦跟着阻塞，代理看到的是"探测超时"而不是"503"，摘流与恢复都会变得不确定。
 * 因此：<b>所有探针共享一个有界时限</b>，超时维度记 DOWN，调用方按期返回。
 *
 * <h2>确定性</h2>
 * 阻塞探针用 {@link CountDownLatch}（不释放）实现，不靠 sleep 猜时序；断言的是
 * "调用方在多长时间内返回"与"该维度的 status/detail"，不是线程调度细节。
 *
 * <h2>边界（如实声明）</h2>
 * Java 的 {@code Future.cancel(true)} 只能<b>中断</b>线程，不能杀死阻塞在
 * 不可中断 native I/O 上的调用；本用例保证的是<b>调用方有界返回</b>，不是"底层 I/O 被终止"。
 * 卡住的池线程由固定容量池 + 有界队列兜住：池满且队列满时新探测直接记 DOWN（saturated），
 * 不会无限建线程。
 */
class ReadinessProbeTimeoutTest {

	@Test
	void blockingProbeReturnsBoundedAndMarksOnlyThatDimensionDown() {
		CountDownLatch never = new CountDownLatch(1);
		ReadinessProbe blocked = blocking("storage", never);
		ReadinessProbe fast = up("db");

		ReadinessService service = new ReadinessService(List.of(blocked, fast));

		long startedAt = System.nanoTime();
		ReadinessService.Snapshot snapshot = assertTimeoutPreemptively(Duration.ofSeconds(10), service::check);
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(elapsedMs).as("整次就绪判定必须有界（默认探针时限 2s + 余量）")
				.isLessThan(5_000L);
		assertThat(status(snapshot, "storage")).as("阻塞维度必须记 DOWN").isEqualTo("DOWN");
		assertThat(String.valueOf(check(snapshot, "storage").get("detail")))
				.as("detail 要能看出是超时而不是业务失败")
				.contains("超时");
		assertThat(status(snapshot, "db")).as("其它维度不受阻塞维度影响").isEqualTo("UP");
		assertThat(snapshot.ready()).as("storage 是硬依赖 ⇒ 超时仍判不 ready").isFalse();
	}

	@Test
	void allProbesShareOneDeadlineInsteadOfOneTimeoutEach() {
		CountDownLatch never = new CountDownLatch(1);
		ReadinessService service = new ReadinessService(List.of(
				blocking("db", never),
				blocking("storage", never),
				blocking("seed", never),
				blocking("redis", never)));

		long startedAt = System.nanoTime();
		ReadinessService.Snapshot snapshot = assertTimeoutPreemptively(Duration.ofSeconds(10), service::check);
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(elapsedMs)
				.as("共享一个时限：不能是 N×timeout（4 个探针串行等 2s 各一次 = 8s）")
				.isLessThan(5_000L);
		assertThat(snapshot.body()).containsEntry("ready", false);
		assertThat(status(snapshot, "db")).isEqualTo("DOWN");
		assertThat(status(snapshot, "storage")).isEqualTo("DOWN");
		assertThat(status(snapshot, "seed")).isEqualTo("DOWN");
		assertThat(status(snapshot, "redis")).isEqualTo("DOWN");
	}

	@Test
	void drainingShortCircuitsWithoutInvokingAnyProbe() {
		AtomicInteger calls = new AtomicInteger();
		ReadinessProbe counting = new ReadinessProbe() {
			@Override
			public String name() {
				return "db";
			}

			@Override
			public ProbeResult check() {
				calls.incrementAndGet();
				return ProbeResult.up("ok");
			}
		};
		ReadinessService service = new ReadinessService(List.of(counting));
		service.markDraining();

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(calls.get()).as("draining 是快路径：摘流判定不需要再探依赖").isZero();
		assertThat(snapshot.ready()).isFalse();
		assertThat(snapshot.body()).containsEntry("draining", true);
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

	/** 永不释放的阻塞探针：可被中断（超时取消），但不会自己返回。 */
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> check(ReadinessService.Snapshot snapshot, String name) {
		return (Map<String, Object>) ((Map<String, Object>) snapshot.body().get("checks")).get(name);
	}

	private static String status(ReadinessService.Snapshot snapshot, String name) {
		return String.valueOf(check(snapshot, name).get("status"));
	}

}
