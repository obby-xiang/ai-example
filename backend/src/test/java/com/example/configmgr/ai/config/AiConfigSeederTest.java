package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import com.example.configmgr.ai.web.AiConfigController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B7 / B8 / B9 / B11（env 种子）＋"空表 + env 与今天 {@code spring.ai.openai.*} 逐值等价"的整链路面。
 *
 * <p>
 * 这是<b>真 Spring</b>（Flyway 建 V4、JPA validate、ApplicationRunner 种、MockMvc 读端点）：
 * 种子不是"我调了个方法"，而是"应用起来之后表里就有那一行"。
 *
 * <p>
 * <b>证红</b>
 * <ul>
 * <li>B7：把种子条件改成"总是写" ⇒ {@code secondStartupDoesNotWriteAgain} 红；</li>
 * <li>B8：去掉 {@code count==0} 判据 ⇒ 上面那条 + {@code strictSeedKeepsTheDatabaseValue} 红；</li>
 * <li>B9：给种子加"env 兜底更新"⇒ {@code strictSeedKeepsTheDatabaseValue} 红；</li>
 * <li>B11：去掉 {@code DataIntegrityViolationException} 捕获 ⇒ 并发用例把异常抛出来 ⇒ 红。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:ai-config-seeder;DB_CLOSE_DELAY=-1",
		"spring.ai.openai.api-key=seed-env-placeholder-11112222",
		"spring.ai.openai.base-url=http://seed-endpoint:18399",
		"spring.ai.openai.chat.options.model=seed-model",
		"app.ai.config.reload-interval=0s",
		"app.ai.resume.on-startup=false" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiConfigSeederTest {

	private static final String ENV_KEY = "seed-env-placeholder-11112222";

	@Autowired
	private AiProviderRepository providerRepository;

	@Autowired
	private AiProviderModelRepository modelRepository;

	@Autowired
	private AiConfigSeeder seeder;

	@Autowired
	private AiConfigService configService;

	@Autowired
	private AiAvailability availability;

	@Autowired
	private AiProperties properties;

	@Autowired
	private OpenAiChatProperties chatProperties;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	// ── B7：启动即种 1 provider 1 model，且与今天的三键逐值等价 ─────────────────

	@Test
	@Order(1)
	void startupSeedsExactlyOneProviderAndOneModelFromEnv() {
		List<AiProvider> providers = this.providerRepository.findAllByOrderBySortOrderAscIdAsc();
		assertThat(providers).hasSize(1);
		AiProvider provider = providers.get(0);
		assertThat(provider.getCode()).isEqualTo("deepseek");
		assertThat(provider.getDisplayName()).isEqualTo("DeepSeek");
		assertThat(provider.getProtocol()).isEqualTo("OPENAI_COMPAT");
		assertThat(provider.getBaseUrl()).isEqualTo("http://seed-endpoint:18399");
		assertThat(provider.getCompletionsPath()).isEqualTo("/chat/completions");
		assertThat(provider.getKeyRef()).isEqualTo(AiProvider.KEY_REF_ENV);
		assertThat(provider.getSource()).isEqualTo(AiProvider.SOURCE_ENV_SEED);
		assertThat(provider.getKeyLast4()).isEqualTo("2222");
		assertThat(provider.isEnabled()).isTrue();

		// 硬约束：env key 不落库（密文列为空，且整表没有任何一列含 env key）
		assertThat(provider.getKeyCipher()).isNull();
		assertThat(this.jdbcTemplate.queryForList("SELECT key_cipher FROM ai_provider", String.class))
			.containsExactly((String) null);

		List<AiProviderModel> models = this.modelRepository.findByProviderIdOrderBySortOrderAscIdAsc(provider.getId());
		assertThat(models).hasSize(1);
		AiProviderModel model = models.get(0);
		assertThat(model.getModelId()).isEqualTo("seed-model");
		assertThat(model.isDefault()).isTrue();
		assertThat(model.isEnabled()).isTrue();
		assertThat(model.isSupportsToolCalling()).isTrue();
		assertThat(model.getMaxContext()).isEqualTo(AiConfigService.ENV_SEED_MAX_CONTEXT);

		// 种子写路径必须发布版本（run 40182796512 的根因 A）：迁移常量是 1，种完必须是 2。
		// 停在 1 ⇒ 别的实例的版本比对永远相等 ⇒ 它们的 provider 快照永久为空（env 合成行）。
		assertThat(configVersion()).as("种子写必须 bump ai_setting['config.version']（同一事务）")
			.isEqualTo(2L);
	}

	@Test
	@Order(2)
	void healthAndProvidersEndpointsReportTheSeededIdentity() throws Exception {
		assertThat(this.availability.isAvailable()).isTrue();
		assertThat(this.availability.code()).isEqualTo("AI_AVAILABLE");

		// "空表 + env" 下 model/baseUrl 与今天从 spring.ai.openai.* 回读的值逐字相同
		this.mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.code").value("AI_AVAILABLE"))
			.andExpect(jsonPath("$.model").value("seed-model"))
			.andExpect(jsonPath("$.baseUrl").value("http://seed-endpoint:18399"))
			.andExpect(jsonPath("$.defaultModelId").value("seed-model"))
			.andExpect(jsonPath("$.providerId").isNumber())
			.andExpect(jsonPath("$.providers[0].code").value("deepseek"))
			.andExpect(jsonPath("$.providers[0].status").value("OK"))
			.andExpect(jsonPath("$.providers[0].keyConfigured").value(true))
			.andExpect(jsonPath("$.providers[0].keyRef").value("ENV"))
			.andExpect(jsonPath("$.message").doesNotExist());

		MvcResult result = this.mockMvc.perform(get("/api/ai/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].code").value("deepseek"))
			.andExpect(jsonPath("$.data[0].keyLast4").value("2222"))
			.andExpect(jsonPath("$.data[0].models[0].modelId").value("seed-model"))
			.andReturn();
		JsonNode body = this.objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.toString()).doesNotContain(ENV_KEY).doesNotContain("seed-env-placeholder");
	}

	// ── B8：幂等（再跑一次行数不变） ──────────────────────────────────────────

	@Test
	@Order(3)
	void secondStartupDoesNotWriteAgain() {
		long providers = this.providerRepository.count();
		long models = this.modelRepository.count();

		assertThat(this.seeder.seed()).as("strict seed：表非空 ⇒ 本次不写").isFalse();

		assertThat(this.providerRepository.count()).isEqualTo(providers);
		assertThat(this.modelRepository.count()).isEqualTo(models);
	}

	// ── B9：strict seed（表非空 ⇒ env 完全不参与） ────────────────────────────

	@Test
	@Order(4)
	void strictSeedKeepsTheDatabaseValueEvenWhenEnvChanged() {
		AiProvider provider = this.providerRepository.findByCode("deepseek").orElseThrow();
		String dbValueBefore = provider.getBaseUrl();
		String envValueBefore = this.chatProperties.getBaseUrl();
		try {
			// 模拟"运维改了 env 后重启"：DB 已被用户改过，env 也换了
			provider.setBaseUrl("http://user-edited-endpoint");
			this.providerRepository.saveAndFlush(provider);
			this.chatProperties.setBaseUrl("http://another-env-endpoint");

			assertThat(this.seeder.seed()).isFalse();

			assertThat(this.providerRepository.findByCode("deepseek").orElseThrow().getBaseUrl())
				.as("strict seed：env 不覆盖 DB").isEqualTo("http://user-edited-endpoint");
			assertThat(this.providerRepository.count()).isEqualTo(1);
		}
		finally {
			provider.setBaseUrl(dbValueBefore);
			this.providerRepository.saveAndFlush(provider);
			this.chatProperties.setBaseUrl(envValueBefore);
		}
	}

	// ── seed-force：显式重灌只刷新 ENV_SEED 行 ─────────────────────────────────

	@Test
	@Order(5)
	void seedForceIsTheOnlyWayToRefillFromEnv() {
		AiProvider provider = this.providerRepository.findByCode("deepseek").orElseThrow();
		String dbValueBefore = provider.getBaseUrl();
		String envValueBefore = this.chatProperties.getBaseUrl();
		boolean forceBefore = this.properties.getConfig().isSeedForce();
		try {
			this.chatProperties.setBaseUrl("http://forced-endpoint");
			this.properties.getConfig().setSeedForce(true);

			assertThat(this.seeder.seed()).isTrue();

			assertThat(this.providerRepository.findByCode("deepseek").orElseThrow().getBaseUrl())
				.isEqualTo("http://forced-endpoint");
			assertThat(this.providerRepository.count()).isEqualTo(1);
		}
		finally {
			this.chatProperties.setBaseUrl(envValueBefore);
			this.properties.getConfig().setSeedForce(forceBefore);
			// 把被重灌的值也还原，避免顺序变化影响后续用例
			AiProvider restored = this.providerRepository.findByCode("deepseek").orElseThrow();
			restored.setBaseUrl(dbValueBefore);
			this.providerRepository.saveAndFlush(restored);
		}
	}

	// ── B11：并发双启动（唯一约束兜底，恰 1 行、无异常外抛） ──────────────────

	@Test
	@Order(6)
	void concurrentDoubleStartupSeedsExactlyOneRow() throws Exception {
		this.providerRepository.deleteAll();
		this.modelRepository.deleteAll();
		assertThat(this.providerRepository.count()).isZero();
		long versionBefore = configVersion();

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = new ArrayList<>();
		try {
			for (int i = 0; i < 2; i++) {
				pool.submit(() -> {
					try {
						start.await();
						this.seeder.seed();
					}
					catch (Throwable ex) {
						synchronized (failures) {
							failures.add(ex);
						}
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures).as("并发双启动不得把异常抛给启动路径").isEmpty();
		assertThat(this.providerRepository.findAllByOrderBySortOrderAscIdAsc()).hasSize(1);
		assertThat(this.modelRepository.count()).isEqualTo(1);
		// 输掉竞争的一方整个事务回滚（版本 +1 也在其中）⇒ 版本恰好只推进一次：
		// 既不能"两人都 bump"（版本跳两格），也不能"都以为别人写了"（版本一格没动 ⇒ 别的实例看不到行）。
		assertThat(configVersion()).as("并发双启动的版本推进必须恰好一次").isEqualTo(versionBefore + 1);
		// 复原：让上下文回到"已种好"的状态（后续若有用例依赖）
		this.seeder.seed();
	}

	/** {@code ai_setting['config.version']} 的当前值（读库，不读快照）。 */
	private long configVersion() {
		return Long.parseLong(
				this.jdbcTemplate.queryForObject("SELECT value_json FROM ai_setting WHERE setting_key = ?",
						String.class, "config.version"));
	}

}
