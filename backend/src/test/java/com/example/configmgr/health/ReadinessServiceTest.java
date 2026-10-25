package com.example.configmgr.health;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.RedisAvailability;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 就绪判定的语义（多实例专项 · 启动底座）。
 *
 * <h2>硬依赖 vs 降级维度（2026-10-25 跨分支裁决）</h2>
 * <ul>
 * <li><b>硬依赖</b>（{@code required()=true}）：{@code db} / {@code storage} / {@code seed}
 * —— 任一 DOWN ⇒ {@code ready=false} ⇒ 503（LB 摘除）。</li>
 * <li><b>非硬依赖</b>（{@code required()=false}）：只有 {@code redis}。Redis 故障时：
 * {@code checks.redis=DOWN}、`degraded=["redis"]`、{@code capabilities.ai.degraded=true}，
 * 但 <b>{@code ready} 仍为 true</b>。理由：Redis 是 AI 会话/帧/租约的设施，AI 端点本来就有
 * 503 快速失败与降级路径；若把 Redis 算进 ready，一次 Redis 抖动会让统一代理把所有实例一起摘掉，
 * 连"读配置/导出/任务"这些不依赖 Redis 的业务也进不来（可用性反而被放大成故障）。</li>
 * <li>探针抛异常收敛成 DOWN，不让端点 500；{@code draining} 仍必须 ready=false。</li>
 * <li><b>required 是探针自身声明的契约</b>，不是对名字的字符串特判：下面的
 * {@code fakeProbeNamedRedisIsStillRequiredByDefault} 锁住这一点。</li>
 * </ul>
 */
class ReadinessServiceTest {

	@Test
	void readyWhenAllHardDependenciesUp() {
		ReadinessService service = new ReadinessService(List.of(
				probe("db", ProbeResult.up("ok")),
				redisProbe(true),
				probe("storage", ProbeResult.up("/tmp/files")),
				probe("seed", ProbeResult.up(Map.of("base", "DONE", "glm", "DONE")))));

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(snapshot.ready()).isTrue();
		assertThat(snapshot.body()).containsEntry("status", "UP")
				.containsEntry("ready", true)
				.containsEntry("draining", false);
		assertThat(down(snapshot)).isEmpty();
		assertThat(degraded(snapshot)).isEmpty();
	}

	@Test
	void redisDownKeepsInstanceReadyButDegradesAiCapability() {
		ReadinessService service = new ReadinessService(List.of(
				probe("db", ProbeResult.up("ok")),
				redisProbe(false),
				probe("storage", ProbeResult.up("/tmp/files")),
				probe("seed", ProbeResult.up("ok"))));

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(snapshot.ready()).as("Redis 不是硬依赖：不得把整站摘流").isTrue();
		assertThat(snapshot.body()).containsEntry("status", "UP").containsEntry("ready", true);
		assertThat(down(snapshot)).as("硬依赖全 UP ⇒ down 为空").isEmpty();
		assertThat(degraded(snapshot)).containsExactly("redis");
		assertThat(check(snapshot, "redis")).containsEntry("status", "DOWN");
		assertThat(aiCapability(snapshot)).containsEntry("degraded", true);
	}

	@Test
	void optionalNonAiDimensionDownDoesNotMarkAiCapabilityDegraded() {
		// 未来的"可选维度"（例如指标推送、审计旁路）DOWN 只该出现在 degraded 列表；
		// AI capability 只由真正承载 AI 传输面的维度（Redis）决定，不能被别的降级维度连带误标。
		ReadinessProbe metrics = new ReadinessProbe() {
			@Override
			public String name() {
				return "metrics";
			}

			@Override
			public boolean required() {
				return false;
			}

			@Override
			public ProbeResult check() {
				return ProbeResult.down("push gateway 不可达");
			}
		};
		ReadinessService service = new ReadinessService(List.of(
				probe("db", ProbeResult.up("ok")),
				redisProbe(true),
				probe("seed", ProbeResult.up("ok")),
				metrics));

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(snapshot.ready()).isTrue();
		assertThat(degraded(snapshot)).containsExactly("metrics");
		assertThat(aiCapability(snapshot))
				.as("只有 Redis 降级才允许标 AI 降级：其它 required=false 维度 DOWN 不得连带")
				.containsEntry("degraded", false);
	}

	@Test
	void databaseStorageAndSeedDownStillMakeItNotReady() {
		for (String hard : List.of("db", "storage", "seed")) {
			ReadinessService service = new ReadinessService(List.of(
					probe("db", "db".equals(hard) ? ProbeResult.down("down") : ProbeResult.up("ok")),
					redisProbe(true),
					probe("storage", "storage".equals(hard) ? ProbeResult.down("down") : ProbeResult.up("ok")),
					probe("seed", "seed".equals(hard) ? ProbeResult.down("播种中") : ProbeResult.up("ok"))));

			ReadinessService.Snapshot snapshot = service.check();

			assertThat(snapshot.ready()).as("硬依赖 %s DOWN ⇒ 不 ready", hard).isFalse();
			assertThat(snapshot.body()).containsEntry("status", "DOWN");
			assertThat(down(snapshot)).containsExactly(hard);
		}
	}

	@Test
	void fakeProbeNamedRedisIsStillRequiredByDefault() {
		// 不是"名字叫 redis 就降级"：required 由探针自己声明，未知探针默认是硬依赖。
		ReadinessService service = new ReadinessService(List.of(
				probe("db", ProbeResult.up("ok")),
				probe("redis", ProbeResult.down("模拟的非 Redis 实现")),
				probe("storage", ProbeResult.up("ok")),
				probe("seed", ProbeResult.up("ok"))));

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(snapshot.ready()).as("默认 required=true ⇒ 仍然拦 ready").isFalse();
		assertThat(down(snapshot)).containsExactly("redis");
		assertThat(degraded(snapshot)).isEmpty();
	}

	@Test
	void probeExceptionIsReportedAsDownInsteadOfFailingEndpoint() {
		ReadinessProbe boom = new ReadinessProbe() {
			@Override
			public String name() {
				return "db";
			}

			@Override
			public ProbeResult check() {
				throw new IllegalStateException("connection refused");
			}
		};

		ReadinessService.Snapshot snapshot = new ReadinessService(List.of(boom)).check();

		assertThat(snapshot.ready()).isFalse();
		assertThat(down(snapshot)).containsExactly("db");
		assertThat(String.valueOf(check(snapshot, "db").get("detail"))).contains("connection refused");
	}

	@Test
	void nullProbeResultIsDown() {
		ReadinessProbe nullResult = new ReadinessProbe() {
			@Override
			public String name() {
				return "storage";
			}

			@Override
			public ProbeResult check() {
				return null;
			}
		};

		ReadinessService.Snapshot snapshot = new ReadinessService(List.of(nullResult)).check();

		assertThat(snapshot.ready()).isFalse();
		assertThat(down(snapshot)).containsExactly("storage");
	}

	@Test
	void aiAvailabilityIsCapabilityOnly() {
		AiConfigService configService = mock(AiConfigService.class);
		when(configService.availability())
				.thenReturn(new AiConfigService.Availability(false, "AI_KEY_MISSING", "缺少 key"));

		ReadinessService service = new ReadinessService(
				List.of(probe("db", ProbeResult.up("ok")), probe("seed", ProbeResult.up("ok"))),
				singleProvider(configService), ReadinessProbeRunner.defaults());

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(snapshot.ready()).as("AI 未配置不影响 ready").isTrue();
		assertThat(aiCapability(snapshot)).containsEntry("configured", false)
				.containsEntry("code", "AI_KEY_MISSING");
	}

	@Test
	void aiFailureInsideCapabilityProbeDoesNotBreakReady() {
		AiConfigService configService = mock(AiConfigService.class);
		when(configService.availability()).thenThrow(new IllegalStateException("snapshot broken"));

		ReadinessService service = new ReadinessService(List.of(probe("db", ProbeResult.up("ok"))),
				singleProvider(configService), ReadinessProbeRunner.defaults());

		ReadinessService.Snapshot snapshot = service.check();

		assertThat(snapshot.ready()).isTrue();
		assertThat(aiCapability(snapshot)).containsEntry("configured", false);
	}

	@Test
	void drainingTakesPrecedenceOverHealthyDependencies() {
		ReadinessService service = new ReadinessService(List.of(probe("db", ProbeResult.up("ok"))));

		service.markDraining();
		ReadinessService.Snapshot snapshot = service.check();

		assertThat(service.isDraining()).isTrue();
		assertThat(snapshot.ready()).isFalse();
		assertThat(snapshot.body()).containsEntry("status", "DOWN").containsEntry("draining", true);
		assertThat(snapshot.body()).containsKey("drainingSince");
		assertThat(down(snapshot)).as("依赖仍 UP，只有 draining 让 ready=false").isEmpty();
	}

	@Test
	void markDrainingIsIdempotent() {
		ReadinessService service = new ReadinessService(List.of());

		service.markDraining();
		Object first = service.check().body().get("drainingSince");
		service.markDraining();

		assertThat(service.check().body().get("drainingSince")).isEqualTo(first);
	}

	@Test
	void missingAiServiceReportsNotWired() {
		ReadinessService service = new ReadinessService(List.of(probe("db", ProbeResult.up("ok"))));

		assertThat(aiCapability(service.check())).containsEntry("configured", false)
				.containsEntry("reason", "not-wired");
	}

	// ---------- helpers ----------

	private static ReadinessProbe probe(String name, ProbeResult result) {
		return new ReadinessProbe() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public ProbeResult check() {
				return result;
			}
		};
	}

	/** 真实的 Redis 探针（非硬依赖契约在它身上声明），只把 PING 结果用 mock 固定。 */
	private static ReadinessProbe redisProbe(boolean available) {
		RedisAvailability availability = mock(RedisAvailability.class);
		when(availability.isAvailable()).thenReturn(available);
		when(availability.reason()).thenReturn("PING 失败");
		return new RedisReadinessProbe(availability);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> check(ReadinessService.Snapshot snapshot, String name) {
		return (Map<String, Object>) ((Map<String, Object>) snapshot.body().get("checks")).get(name);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> aiCapability(ReadinessService.Snapshot snapshot) {
		return (Map<String, Object>) ((Map<String, Object>) snapshot.body().get("capabilities")).get("ai");
	}

	@SuppressWarnings("unchecked")
	private static List<String> down(ReadinessService.Snapshot snapshot) {
		return new ArrayList<>((List<String>) snapshot.body().get("down"));
	}

	@SuppressWarnings("unchecked")
	private static List<String> degraded(ReadinessService.Snapshot snapshot) {
		return new ArrayList<>((List<String>) snapshot.body().get("degraded"));
	}

	private static org.springframework.beans.factory.ObjectProvider<AiConfigService> singleProvider(
			AiConfigService service) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public AiConfigService getObject() {
				return service;
			}

			@Override
			public AiConfigService getIfAvailable() {
				return service;
			}
		};
	}

}
