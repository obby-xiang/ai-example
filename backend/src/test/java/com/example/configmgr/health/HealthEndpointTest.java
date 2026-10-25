package com.example.configmgr.health;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /health/live} 与 {@code /health/ready} 的 HTTP 契约（多实例专项 · 启动底座）。
 *
 * <p>分工是本用例的核心断言：live **永远 200**（依赖故障/正在关闭都不许让编排器重启进程），
 * ready 才是摘流信号（**硬依赖** DOWN 或 draining ⇒ 503）。
 *
 * <p>Redis 不是硬依赖（见 {@link ReadinessServiceTest}）：Redis DOWN 时 ready 仍 200，
 * 但 `checks.redis=DOWN` 且 `degraded` 点名，AI capability 标降级。
 */
class HealthEndpointTest {

	@Test
	void liveIsAlways200EvenWhenDependenciesAreDown() throws Exception {
		MockMvc mvc = mvc(probe("db", ProbeResult.down("down")));

		mvc.perform(get("/health/live"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.draining").value(false));
	}

	@Test
	void liveStays200WhileDraining() throws Exception {
		ReadinessService service = new ReadinessService(List.of(probe("db", ProbeResult.up("ok"))));
		service.markDraining();
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(service)).build();

		mvc.perform(get("/health/live"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.draining").value(true));
	}

	@Test
	void readyIs200WhenAllChecksUp() throws Exception {
		MockMvc mvc = mvc(
				up("db"),
				up("redis"),
				up("storage"),
				probe("seed", ProbeResult.up(java.util.Map.of("base", "DONE", "glm", "DONE"))));

		mvc.perform(get("/health/ready"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.ready").value(true))
				.andExpect(jsonPath("$.checks.db.status").value("UP"))
				.andExpect(jsonPath("$.checks.seed.detail.base").value("DONE"))
				.andExpect(jsonPath("$.capabilities.ai.configured").value(false))
				.andExpect(jsonPath("$.degraded").isEmpty());
	}

	@Test
	void readyIs503NamingTheDownHardDimension() throws Exception {
		MockMvc mvc = mvc(
				up("db"),
				up("redis"),
				up("storage"),
				probe("seed", ProbeResult.down("播种中")));

		mvc.perform(get("/health/ready"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.ready").value(false))
				.andExpect(jsonPath("$.down[0]").value("seed"))
				.andExpect(jsonPath("$.checks.seed.status").value("DOWN"));
	}

	@Test
	void redisDownIs200WithDegradedMarker() throws Exception {
		MockMvc mvc = mvc(up("db"), redisDown(), up("storage"), up("seed"));

		mvc.perform(get("/health/ready"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.ready").value(true))
				.andExpect(jsonPath("$.down").isEmpty())
				.andExpect(jsonPath("$.degraded[0]").value("redis"))
				.andExpect(jsonPath("$.checks.redis.status").value("DOWN"))
				.andExpect(jsonPath("$.checks.redis.detail").value("PING 失败"))
				.andExpect(jsonPath("$.capabilities.ai.degraded").value(true));
	}

	@Test
	void readyIs503OnceDraining() throws Exception {
		ReadinessService service = new ReadinessService(List.of(probe("db", ProbeResult.up("ok"))));
		service.markDraining();
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(service)).build();

		mvc.perform(get("/health/ready"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.draining").value(true))
				.andExpect(jsonPath("$.ready").value(false));
	}

	private static MockMvc mvc(ReadinessProbe... probes) {
		return MockMvcBuilders
				.standaloneSetup(new HealthController(new ReadinessService(List.of(probes))))
				.build();
	}

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

	private static ReadinessProbe up(String name) {
		return probe(name, ProbeResult.up("ok"));
	}

	/** 真实 Redis 探针（非硬依赖契约由它自己声明），只用 mock 固定 PING 失败。 */
	private static ReadinessProbe redisDown() {
		var availability = org.mockito.Mockito.mock(com.example.configmgr.ai.config.RedisAvailability.class);
		org.mockito.Mockito.when(availability.isAvailable()).thenReturn(false);
		org.mockito.Mockito.when(availability.reason()).thenReturn("PING 失败");
		return new RedisReadinessProbe(availability);
	}

}
