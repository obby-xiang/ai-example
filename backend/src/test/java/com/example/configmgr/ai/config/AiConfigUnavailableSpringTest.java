package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B10（空表 + 无 env）：0 行、{@code /health} 503 {@code AI_UNAVAILABLE} 原文案、
 * {@code /chat} 503 且<b>不建流</b>，{@code /api/ai/providers} 空表 —— 这是"零迁移风险"的基线。
 *
 * <p>
 * 与批①之前唯一的差别是"模型侧 bean 现在存在但不被用"，对外响应逐字不变；这也是
 * "移除 conditional 之后没有行为回归"的整链路面证据。
 *
 * <p>
 * <b>证红</b>：去掉 {@code AiController#chat} 的 availability 门（或让可用性恒 true）
 * ⇒ {@code chatStillReturns503WithoutBuildingAStream} 红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:ai-config-empty;DB_CLOSE_DELAY=-1",
		// 显式清空 env key（覆盖 application.yml 的 ${AI_API_KEY:...} 占位）
		"spring.ai.openai.api-key=",
		"app.ai.config.reload-interval=0s",
		"app.ai.resume.on-startup=false" })
class AiConfigUnavailableSpringTest {

	@Autowired
	private AiProviderRepository providerRepository;

	@Autowired
	private AiAvailability availability;

	@Autowired
	private AiConfigService configService;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void seedingIsSkippedWhenBothTheTableAndTheEnvKeyAreEmpty() {
		assertThat(this.providerRepository.count()).isZero();
		assertThat(this.configService.listProviders()).isEmpty();
		assertThat(this.availability.isAvailable()).isFalse();
		assertThat(this.availability.code()).isEqualTo("AI_UNAVAILABLE");
		assertThat(this.availability.reason())
			.isEqualTo("AI 能力未启用：未配置 AI_API_KEY（或 DEEPSEEK_API_KEY）环境变量，AI 端点已惰性降级");
	}

	@Test
	void healthKeepsTheLegacy503ShapeWithNoProvider() throws Exception {
		this.mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
			// 旧键仍在、值与"从 env 回读"逐字相同（批①之前的形态）
			.andExpect(jsonPath("$.model").value("deepseek-flash"))
			.andExpect(jsonPath("$.baseUrl").value("https://api.deepseek.com"))
			// 新增键以空值出现（additive，不改变 503 语义）
			.andExpect(jsonPath("$.defaultModelId").doesNotExist())
			.andExpect(jsonPath("$.providerId").doesNotExist())
			.andExpect(jsonPath("$.providers").isEmpty())
			.andExpect(jsonPath("$.message")
				.value("AI 能力未启用：未配置 AI_API_KEY（或 DEEPSEEK_API_KEY）环境变量，AI 端点已惰性降级"));
	}

	@Test
	void chatStillReturns503WithoutBuildingAStream() throws Exception {
		this.mockMvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
			.content("{\"sessionId\":\"s-unavailable\",\"message\":\"你好\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.message")
				.value("AI 能力未启用：未配置 AI_API_KEY（或 DEEPSEEK_API_KEY）环境变量，AI 端点已惰性降级"));
	}

	@Test
	void readOnlyConfigEndpointsDegradeToEmpty() throws Exception {
		this.mockMvc.perform(get("/api/ai/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").isEmpty());

		this.mockMvc.perform(get("/api/ai/startup-resume"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.scanned").value(false))
			.andExpect(jsonPath("$.data.reason").value("AI_UNAVAILABLE"));
	}

}
