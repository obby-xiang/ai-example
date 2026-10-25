package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.db.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批② 写路径的<b>真落库面</b>（真 Spring + Flyway V4 + JPA + H2 mem + MockMvc）。
 *
 * <p>
 * 与 {@code AiConfigWriterTest}（内存仓储）分工：这里证明"HTTP → 事务 → 四张表 → 读路径快照"整链，
 * 以及安全面（H2 文件/表里不出现明文 key、审计只写 last4）。
 *
 * <p>
 * <b>证红</b>：把 key 明文写进 {@code key_last4} 以外的任一列 ⇒ 本类的脱敏 grep 变红；去掉 URL 闸 ⇒
 * {@code privateBaseUrlIsRejectedByTheRealEndpoint()} 红；去掉版本号 +1 ⇒ 版本断言红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:ai-config-write;DB_CLOSE_DELAY=-1",
		"spring.ai.openai.api-key=",
		"app.ai.config.reload-interval=0s",
		"app.ai.resume.on-startup=false",
		"AI_SECRET_KEK=WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiConfigWritePersistenceTest {

	/** base64 的 32 字节（0x5a）：测试用 KEK，与生产无关（常量内联：@TestPropertySource 只收常量表达式）。 */
	static final String KEK = "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=";

	private static final String KEY = "persist-probe-key-77889900";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AiConfigService configService;

	@Autowired
	private AiProviderRegistry registry;

	private final ObjectMapper mapper = new ObjectMapper();

	/** JUnit 5 每个方法一个新实例 ⇒ 跨方法共享的状态必须是 static。 */
	private static Long providerId;

	@Test
	@Order(1)
	void startsEmptyWithTheMigrationVersion() {
		assertThat(this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_provider", Long.class)).isZero();
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT value_json FROM ai_setting WHERE setting_key='config.version'", String.class)).isEqualTo("1");
		assertThat(this.configService.availability().available()).isFalse();
	}

	@Test
	@Order(2)
	void privateBaseUrlIsRejectedByTheRealEndpoint() throws Exception {
		this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"loop\",\"displayName\":\"回环\",\"baseUrl\":\"http://127.0.0.1:18399\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BLOCKED_PRIVATE_ENDPOINT"));
		assertThat(this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_provider", Long.class)).isZero();
	}

	@Test
	@Order(3)
	void createPersistsTheEncryptedKeyAndBumpsTheVersion() throws Exception {
		MvcResult result = this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"custom-1\",\"displayName\":\"自定义端点\",\"baseUrl\":\"https://api.deepseek.com\","
						+ "\"apiKey\":\"" + KEY + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.code").value("custom-1"))
			.andExpect(jsonPath("$.data.keyLast4").value("9900"))
			.andExpect(jsonPath("$.data.keyStatus").value("OK"))
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(KEY).doesNotContain("v1.k1.");
		providerId = this.mapper.readTree(result.getResponse().getContentAsString())
			.path("data")
			.path("id")
			.asLong();
		assertThat(providerId).isPositive();

		Map<String, Object> row = this.jdbcTemplate
			.queryForMap("SELECT key_cipher, key_last4, key_ref, source FROM ai_provider WHERE code='custom-1'");
		assertThat((String) row.get("KEY_CIPHER")).startsWith("v1.k1.");
		assertThat((String) row.get("KEY_LAST4")).isEqualTo("9900");
		assertThat((String) row.get("KEY_REF")).isEqualTo("DB");
		assertThat((String) row.get("SOURCE")).isEqualTo("DB");
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT value_json FROM ai_setting WHERE setting_key='config.version'", String.class)).isEqualTo("2");

		// 读路径快照立刻可用 —— 但默认目标还缺"这一家自己的模型"：明确 NO_MODEL_CONFIGURED，
		// 不借 env / 别家的模型名。
		assertThat(this.configService.availability().available()).isFalse();
		assertThat(this.configService.availability().code()).isEqualTo("NO_MODEL_CONFIGURED");
		assertThat(this.configService.current().providers()).extracting(AiProvider::getCode).contains("custom-1");
		assertThat(this.registry.tryCurrent()).isEmpty();

		// allowlist 一行（后续默认参数与硬删闸都依赖它存在）
		this.mockMvc
			.perform(post("/api/ai/providers/" + providerId + "/models").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modelId\":\"m-1\",\"maxContext\":8000,\"maxOutput\":2048}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.modelId").value("m-1"));
		assertThat(this.configService.availability().available()).isTrue();
		assertThat(this.registry.tryCurrent()).isPresent();
	}

	@Test
	@Order(4)
	void auditRowsCarryOnlyTheLast4AndTheAction() {
		List<Map<String, Object>> audits = this.jdbcTemplate
			.queryForList("SELECT action, target_type, target_id, diff_json FROM ai_config_audit ORDER BY id");
		assertThat(audits).isNotEmpty();
		Map<String, Object> createRow = audits.stream()
			.filter(row -> "PROVIDER_CREATE".equals(row.get("ACTION")))
			.findFirst()
			.orElseThrow();
		assertThat(createRow.get("TARGET_TYPE")).isEqualTo("provider");
		String diff = (String) createRow.get("DIFF_JSON");
		assertThat(diff).contains("\"last4\":\"9900\"").doesNotContain(KEY).doesNotContain("v1.k1.");
		// 全表逐行：任何审计行都不得出现明文或密文信封
		assertThat(audits).allSatisfy(row -> assertThat((String) row.get("DIFF_JSON"))
			.doesNotContain(KEY).doesNotContain("v1.k1."));
	}

	@Test
	@Order(5)
	void noPlaintextKeyLandsInAnyColumnOfAnyConfigTable() {
		String dump = this.jdbcTemplate.queryForObject(
				"SELECT COALESCE((SELECT LISTAGG(COALESCE(key_cipher,'') || '|' || COALESCE(key_last4,'') || '|' "
						+ "|| COALESCE(key_fingerprint,''), ',') FROM ai_provider), '')", String.class);
		assertThat(dump).doesNotContain(KEY).doesNotContain("probe-key");
		String auditDump = this.jdbcTemplate.queryForObject(
				"SELECT COALESCE((SELECT LISTAGG(COALESCE(diff_json,''), ',') FROM ai_config_audit), '')",
				String.class);
		assertThat(auditDump).doesNotContain(KEY).doesNotContain("probe-key");
	}

	@Test
	@Order(6)
	void defaultsRoundTripThroughTheSettingsEndpoint() throws Exception {
		// 旧客户端只提交 modelId：写路径按唯一所有者推断并落 default.providerId
		this.mockMvc
			.perform(put("/api/ai/settings/defaults").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modelId\":\"m-1\",\"temperature\":0.3,\"topP\":0.9,\"maxTokens\":1024,"
						+ "\"allowUserParams\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.modelId").value("m-1"))
			.andExpect(jsonPath("$.data.providerId").value(providerId));

		this.mockMvc.perform(get("/api/ai/settings/defaults"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.modelId").value("m-1"))
			.andExpect(jsonPath("$.data.providerId").value(providerId))
			.andExpect(jsonPath("$.data.temperature").value(0.3))
			.andExpect(jsonPath("$.data.topP").value(0.9))
			.andExpect(jsonPath("$.data.maxTokens").value(1024))
			.andExpect(jsonPath("$.data.allowUserParams").value(false));
	}

	@Test
	@Order(7)
	void updateAndSoftDeleteKeepTheRowAndClearTheKeyOnDemand() throws Exception {
		this.mockMvc
			.perform(put("/api/ai/providers/" + providerId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"改过名\",\"clearKey\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayName").value("改过名"))
			.andExpect(jsonPath("$.data.keyConfigured").value(false))
			.andExpect(jsonPath("$.data.keyRef").value("NONE"));

		Map<String, Object> row = this.jdbcTemplate
			.queryForMap("SELECT key_cipher, key_last4, key_ref, display_name FROM ai_provider WHERE code='custom-1'");
		assertThat(row.get("KEY_CIPHER")).isNull();
		assertThat(row.get("KEY_LAST4")).isNull();
		assertThat((String) row.get("KEY_REF")).isEqualTo("NONE");
		assertThat((String) row.get("DISPLAY_NAME")).isEqualTo("改过名");

		// 硬删被模型引用挡住
		this.mockMvc.perform(delete("/api/ai/providers/" + providerId).param("hard", "true"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROVIDER_IN_USE"));

		this.mockMvc.perform(delete("/api/ai/providers/" + providerId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.enabled").value(false));
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT enabled FROM ai_provider WHERE code='custom-1'", Boolean.class)).isFalse();
	}

	@Test
	@Order(8)
	void deleteAndDisableAreAudited() {
		List<String> actions = this.jdbcTemplate.queryForList(
				"SELECT action FROM ai_config_audit ORDER BY id", String.class);
		assertThat(actions).contains("PROVIDER_UPDATE", "PROVIDER_DELETE", "DEFAULTS_UPDATE");
	}
}
