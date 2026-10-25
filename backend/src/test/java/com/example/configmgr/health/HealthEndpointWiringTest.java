package com.example.configmgr.health;

import com.example.configmgr.ai.config.RedisAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实上下文里的 {@code /health/*}（多实例专项 · 启动底座）：验证四个探针**都被装配成 bean**、
 * 端点映射存在、seed 维度真的读到了播种终态（启动期 DataSeedRunner / GlmSeedRunner 已跑完），
 * 并锁住两条跨分支裁决：
 * <ul>
 * <li>Redis DOWN 仍 200（降级不摘流），但 {@code checks.redis=DOWN}、{@code degraded} 点名、
 * AI capability 标降级；</li>
 * <li>{@code /api/ai/health} 的既有 AI 语义不变（无 provider 仍 503）。</li>
 * </ul>
 *
 * <p>Redis 用 {@link MockitoBean} 顶掉：单测环境没有 Redis 服务，而本用例要验证的是"装配与判定
 * 连线"，不是 Redis 协议本身（那条在 RedisAvailability / 集群冒烟里）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:health-ready-wiring;DB_CLOSE_DELAY=-1",
		"app.file.storage-path=./target/health-ready-storage"
})
class HealthEndpointWiringTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RedisAvailability redisAvailability;

	@Test
	void liveIsServedWithoutTouchingDependencies() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		this.mockMvc.perform(get("/health/live"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readyReflectsDbStorageAndSeedWiring() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		this.mockMvc.perform(get("/health/ready"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.ready").value(true))
				.andExpect(jsonPath("$.checks.db.status").value("UP"))
				.andExpect(jsonPath("$.checks.redis.status").value("UP"))
				.andExpect(jsonPath("$.checks.storage.status").value("UP"))
				.andExpect(jsonPath("$.checks.seed.status").value("UP"))
				.andExpect(jsonPath("$.checks.seed.detail.base").value("DONE"))
				.andExpect(jsonPath("$.checks.seed.detail.glm").value("DONE"));
	}

	@Test
	void redisDownKeepsInstanceInRotationButDegradesAiCapability() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);
		when(this.redisAvailability.reason()).thenReturn("java.net.ConnectException: 127.0.0.1:6379");

		this.mockMvc.perform(get("/health/ready"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ready").value(true))
				.andExpect(jsonPath("$.down").isEmpty())
				.andExpect(jsonPath("$.degraded[0]").value("redis"))
				.andExpect(jsonPath("$.checks.redis.status").value("DOWN"))
				.andExpect(jsonPath("$.checks.seed.status").value("UP"))
				.andExpect(jsonPath("$.capabilities.ai.degraded").value(true));
	}

	@Test
	void aiHealthEndpointSemanticsAreUnchanged() throws Exception {
		// 本批只改 readiness 的依赖分级：/api/ai/health 仍按 AI 可用性 503（无 provider ⇒ 不可用），
		// 不能因为 readiness 把 Redis 降级就顺手把 AI 端点也变绿。
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		this.mockMvc.perform(get("/api/ai/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.available").value(false));
	}

}
