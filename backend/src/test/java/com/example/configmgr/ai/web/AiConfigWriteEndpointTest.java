package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiConfigException;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiConfigWriter;
import com.example.configmgr.ai.config.AiConnectionProbe;
import com.example.configmgr.ai.config.AiEndpointGuard;
import com.example.configmgr.ai.config.AiKeyCipher;
import com.example.configmgr.ai.config.AiModelCatalog;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批② 写端点的<b>契约面</b>：业务信封 + 稳定 code + 泄密面（B4）+ 字段白名单（M7）。
 *
 * <p>
 * 这里的服务/写路径全是桩：本类只锁 HTTP 契约与序列化面（"响应里出现什么"）；真落库面在
 * {@code AiConfigWriterTest}（单测）与 {@code AiConfigWritePersistenceTest}（真 Spring + H2）。
 *
 * <p>
 * <b>证红</b>
 * <ul>
 * <li>B4：给 {@code ProviderView} 加回 {@code keyCipher}/{@code apiKey} 成分 ⇒ 键集与"值不含密文"两段红；</li>
 * <li>B5：去掉 {@code AI_SECRET_KEK_MISSING} 的 400 映射（落成 500 或 200）⇒ 红；</li>
 * <li>M7：改用"只断言存在某些键"（{@code contains}）⇒ 精确键集断言失去意义，本类用例红。</li>
 * </ul>
 */
class AiConfigWriteEndpointTest {

	private static final String PLAINTEXT_KEY = "write-probe-key-001122334455";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiConfigService configService = mock(AiConfigService.class);

	private final AiConfigWriter writer = mock(AiConfigWriter.class);

	private final AiConnectionProbe probe = mock(AiConnectionProbe.class);

	private final AiModelCatalog catalog = mock(AiModelCatalog.class);

	private final MockMvc mockMvc = MockMvcBuilders
		.standaloneSetup(new AiConfigController(this.configService, this.writer, this.probe, this.catalog))
		.build();

	// ── 新建 ────────────────────────────────────────────────────────────────

	@Test
	void createReturnsTheSameWhitelistedViewAsTheReadEndpoint() throws Exception {
		String envelope = new AiKeyCipher(java.util.Base64.getEncoder().encodeToString(fill((byte) 0x2a)))
			.encrypt(PLAINTEXT_KEY, "custom-1", AiProvider.KEY_REF_DB);
		AiProvider created = provider(envelope);
		when(this.writer.createProvider(any())).thenReturn(created);
		when(this.configService.modelsOf(any())).thenReturn(List.of(model()));
		when(this.configService.resolveKey(any()))
			.thenReturn(keyOk(PLAINTEXT_KEY, "fp-0011223344"));
		when(this.configService.keyStatus(any())).thenReturn("OK");

		MvcResult result = this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"custom-1\",\"displayName\":\"自定义\",\"baseUrl\":\"https://api.deepseek.com\","
						+ "\"apiKey\":\"" + PLAINTEXT_KEY + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.code").value("custom-1"))
			.andExpect(jsonPath("$.data.keyConfigured").value(true))
			.andExpect(jsonPath("$.data.keyLast4").value("4455"))
			.andExpect(jsonPath("$.data.keyRef").value("DB"))
			.andExpect(jsonPath("$.data.keyStatus").value("OK"))
			.andExpect(jsonPath("$.data.source").value("DB"))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain(PLAINTEXT_KEY).doesNotContain(envelope).doesNotContain("v1.k1.");
		assertExactProviderKeySet(this.mapper.readTree(body).path("data"));
	}

	@Test
	void createWithoutAnyKeyStillReturnsTheView() throws Exception {
		AiProvider created = provider(null);
		created.setKeyRef(AiProvider.KEY_REF_NONE);
		created.setKeyLast4(null);
		when(this.writer.createProvider(any())).thenReturn(created);
		when(this.configService.modelsOf(any())).thenReturn(List.of());
		when(this.configService.resolveKey(any())).thenReturn(keyMissing());
		when(this.configService.keyStatus(any())).thenReturn("MISSING");

		this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"custom-1\",\"displayName\":\"自定义\",\"baseUrl\":\"https://api.deepseek.com\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.keyConfigured").value(false))
			.andExpect(jsonPath("$.data.keyStatus").value("MISSING"));
	}

	@Test
	void kekMissingSaveIsA400WithTheStableCode() throws Exception {
		when(this.writer.createProvider(any()))
			.thenThrow(new AiConfigException(AiKeyCipher.CODE_KEK_MISSING, "未配置可用的 AI_SECRET_KEK"));

		MvcResult result = this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"custom-1\",\"displayName\":\"自定义\",\"baseUrl\":\"https://api.deepseek.com\","
						+ "\"apiKey\":\"" + PLAINTEXT_KEY + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(AiKeyCipher.CODE_KEK_MISSING))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain(PLAINTEXT_KEY);
	}

	@Test
	void blockedPrivateEndpointIsA400WithTheStableCode() throws Exception {
		when(this.writer.createProvider(any())).thenThrow(
				new AiConfigException(AiEndpointGuard.CODE_BLOCKED_PRIVATE_ENDPOINT, "默认禁止私网/回环地址"));

		this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"custom-1\",\"displayName\":\"自定义\",\"baseUrl\":\"http://127.0.0.1:18399\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(AiEndpointGuard.CODE_BLOCKED_PRIVATE_ENDPOINT));
	}

	@Test
	void invalidBaseUrlIsA400WithTheStableCode() throws Exception {
		when(this.writer.createProvider(any()))
			.thenThrow(new AiConfigException(AiEndpointGuard.CODE_INVALID_BASE_URL, "baseUrl 必须是 http(s) 绝对地址"));

		this.mockMvc
			.perform(post("/api/ai/providers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"custom-1\",\"baseUrl\":\"api.deepseek.com\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(AiEndpointGuard.CODE_INVALID_BASE_URL));
	}

	// ── 更新 / 删除 ─────────────────────────────────────────────────────────

	@Test
	void updatePassesThePathIdAndTheBodyThrough() throws Exception {
		AiProvider updated = provider(null);
		updated.setKeyRef(AiProvider.KEY_REF_DB);
		when(this.writer.updateProvider(eq(7L), any())).thenReturn(updated);
		when(this.configService.modelsOf(any())).thenReturn(List.of());
		when(this.configService.resolveKey(any()))
			.thenReturn(keyOk(PLAINTEXT_KEY, "fp-0011223344"));
		when(this.configService.keyStatus(any())).thenReturn("OK");

		this.mockMvc
			.perform(put("/api/ai/providers/7").contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"改过\",\"enabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(7));
		verify(this.writer).updateProvider(eq(7L), any());
	}

	@Test
	void updateOfAMissingProviderIsA404() throws Exception {
		when(this.writer.updateProvider(anyLong(), any()))
			.thenThrow(new AiConfigException(AiConfigWriter.CODE_PROVIDER_NOT_FOUND, "provider 不存在"));

		this.mockMvc.perform(put("/api/ai/providers/999").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(AiConfigWriter.CODE_PROVIDER_NOT_FOUND));
	}

	@Test
	void deleteDefaultsToSoftAndHardDeleteRefusalIsA409() throws Exception {
		AiProvider disabled = provider(null);
		disabled.setEnabled(false);
		when(this.writer.deleteProvider(eq(7L), eq(false))).thenReturn(disabled);
		when(this.configService.modelsOf(any())).thenReturn(List.of());
		when(this.configService.resolveKey(any())).thenReturn(keyMissing());
		when(this.configService.keyStatus(any())).thenReturn("MISSING");

		this.mockMvc.perform(delete("/api/ai/providers/7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.enabled").value(false));
		verify(this.writer).deleteProvider(7L, false);

		when(this.writer.deleteProvider(eq(7L), eq(true)))
			.thenThrow(new AiConfigException(AiConfigWriter.CODE_PROVIDER_IN_USE, "该 provider 仍有模型引用"));
		this.mockMvc.perform(delete("/api/ai/providers/7").param("hard", "true"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(AiConfigWriter.CODE_PROVIDER_IN_USE));
	}

	// ── 测试连接 / 拉取模型 ──────────────────────────────────────────────────

	@Test
	void testEndpointReturnsTheProbeResultAsA200Payload() throws Exception {
		AiProvider provider = provider(null);
		when(this.configService.provider(7L)).thenReturn(java.util.Optional.of(provider));
		when(this.configService.resolveKey(any())).thenReturn(keyMissing());
		when(this.configService.keyStatus(any())).thenReturn("MISSING");
		when(this.probe.fetchModels(eq("https://api.deepseek.com"), eq("/models"), any(), anyBoolean()))
			.thenReturn(AiConnectionProbe.ProbeResult.failed(AiConnectionProbe.CODE_AUTH_FAILED, 401, 12L,
					"鉴权失败，请检查 key"));

		this.mockMvc.perform(post("/api/ai/providers/7/test"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ok").value(false))
			.andExpect(jsonPath("$.data.status").value(401))
			.andExpect(jsonPath("$.data.code").value(AiConnectionProbe.CODE_AUTH_FAILED));
	}

	/**
	 * M-1（复审）：key 声明了但本进程解不开（KEK 缺失/变更/密文损坏）⇒ {@code /test} 直接回
	 * {@code KEY_UNREADABLE}，<b>不</b>拿 null key 去打上游换一个误导性的 AUTH_FAILED
	 * （用户会去改 key，真正要修的是 KEK）。
	 */
	@Test
	void testEndpointShortCircuitsUnreadableKeysWithoutProbingUpstream() throws Exception {
		AiProvider provider = provider(null);
		when(this.configService.provider(7L)).thenReturn(java.util.Optional.of(provider));
		when(this.configService.resolveKey(any())).thenReturn(
				new AiConfigService.KeyResolution(AiConfigService.KeyStatus.UNREADABLE, null, null));

		this.mockMvc.perform(post("/api/ai/providers/7/test"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ok").value(false))
			.andExpect(jsonPath("$.data.code").value(AiConfigService.CODE_KEY_UNREADABLE))
			.andExpect(jsonPath("$.data.modelCount").value(0));
		verify(this.probe, org.mockito.Mockito.never()).fetchModels(any(), any(), any(), anyBoolean());
	}

	@Test
	void testOfAMissingProviderIsA404() throws Exception {
		when(this.configService.provider(9L)).thenReturn(java.util.Optional.empty());
		this.mockMvc.perform(post("/api/ai/providers/9/test"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(AiConfigWriter.CODE_PROVIDER_NOT_FOUND));
	}

	@Test
	void envSeededProviderIsProbedAsTrustedSoThePrivateStubWorks() throws Exception {
		AiProvider seeded = provider(null);
		seeded.setSource(AiProvider.SOURCE_ENV_SEED);
		seeded.setBaseUrl("http://127.0.0.1:18399");
		when(this.configService.provider(7L)).thenReturn(java.util.Optional.of(seeded));
		when(this.configService.resolveKey(any()))
			.thenReturn(keyOk(PLAINTEXT_KEY, "fp-0011223344"));
		when(this.configService.keyStatus(any())).thenReturn("OK");
		when(this.probe.fetchModels(any(), any(), any(), eq(true)))
			.thenReturn(AiConnectionProbe.ProbeResult.ok(200, 3L, List.of("m-1")));

		this.mockMvc.perform(post("/api/ai/providers/7/test"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ok").value(true))
			.andExpect(jsonPath("$.data.modelCount").value(1));
		verify(this.probe).fetchModels(seeded.getBaseUrl(), seeded.getModelsPath(), PLAINTEXT_KEY, true);
	}

	@Test
	void modelsFetchReturnsTheListAndTheCacheFlag() throws Exception {
		when(this.catalog.fetch(7L, true)).thenReturn(AiModelCatalog.CatalogResult.ok(List.of("m-1", "m-2"), false));
		this.mockMvc.perform(post("/api/ai/providers/7/models/fetch"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.models[0]").value("m-1"))
			.andExpect(jsonPath("$.data.cached").value(false));
	}

	@Test
	void modelsFetchFailureCarriesTheStableCode() throws Exception {
		when(this.catalog.fetch(7L, true)).thenReturn(new AiModelCatalog.CatalogResult(false, List.of(), false,
				AiConnectionProbe.CODE_MODELS_UNSUPPORTED, "该端点未实现 /models，可手填模型 ID"));
		this.mockMvc.perform(post("/api/ai/providers/7/models/fetch"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ok").value(false))
			.andExpect(jsonPath("$.data.code").value(AiConnectionProbe.CODE_MODELS_UNSUPPORTED));
	}

	// ── 模型 allowlist ──────────────────────────────────────────────────────

	@Test
	void modelListIsTheSameModelViewAsTheProviderList() throws Exception {
		when(this.configService.provider(7L)).thenReturn(java.util.Optional.of(provider(null)));
		when(this.configService.modelsOf(7L)).thenReturn(List.of(model()));

		MvcResult result = this.mockMvc.perform(get("/api/ai/providers/7/models"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].modelId").value("deepseek-flash"))
			.andReturn();
		assertExactModelKeySet(this.mapper.readTree(result.getResponse().getContentAsString()).path("data").get(0));
	}

	@Test
	void modelAddUpdateDeleteMapToTheWriter() throws Exception {
		AiProviderModel added = model();
		when(this.writer.addModel(eq(7L), any())).thenReturn(added);
		when(this.writer.updateModel(eq(7L), eq("deepseek-flash"), any())).thenReturn(added);
		when(this.writer.deleteModel(eq(7L), eq("deepseek-flash"))).thenReturn(added);
		when(this.configService.provider(7L)).thenReturn(java.util.Optional.of(provider(null)));

		this.mockMvc
			.perform(post("/api/ai/providers/7/models").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modelId\":\"deepseek-flash\",\"maxContext\":128000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.modelId").value("deepseek-flash"));

		this.mockMvc
			.perform(put("/api/ai/providers/7/models/deepseek-flash").contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}"))
			.andExpect(status().isOk());

		this.mockMvc.perform(delete("/api/ai/providers/7/models/deepseek-flash")).andExpect(status().isOk());
		verify(this.writer).deleteModel(7L, "deepseek-flash");
	}

	@Test
	void modelWriteErrorsCarryTheirStatus() throws Exception {
		when(this.writer.addModel(eq(7L), any()))
			.thenThrow(new AiConfigException(AiConfigWriter.CODE_MODEL_EXISTS, "该模型已在 allowlist 中"));
		this.mockMvc
			.perform(post("/api/ai/providers/7/models").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modelId\":\"m-1\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(AiConfigWriter.CODE_MODEL_EXISTS));

		when(this.writer.addModel(eq(7L), any()))
			.thenThrow(new AiConfigException(AiConfigWriter.CODE_INVALID_PARAM, "maxOutput 不能大于 maxContext"));
		this.mockMvc
			.perform(post("/api/ai/providers/7/models").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modelId\":\"m-1\",\"maxContext\":10,\"maxOutput\":20}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(AiConfigWriter.CODE_INVALID_PARAM));
	}

	// ── 全局默认参数 ─────────────────────────────────────────────────────────

	@Test
	void defaultsReadReturnsTheStoredValues() throws Exception {
		when(this.configService.defaults())
			.thenReturn(new AiConfigService.Defaults("m-1", 0.3d, 0.9d, 1024, Boolean.TRUE, 4L));

		this.mockMvc.perform(get("/api/ai/settings/defaults"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.modelId").value("m-1"))
			.andExpect(jsonPath("$.data.temperature").value(0.3))
			.andExpect(jsonPath("$.data.topP").value(0.9))
			.andExpect(jsonPath("$.data.maxTokens").value(1024))
			.andExpect(jsonPath("$.data.allowUserParams").value(true))
			.andExpect(jsonPath("$.data.version").value(4));
	}

	@Test
	void defaultsWriteReturnsTheFreshValuesAndMapsRangeErrors() throws Exception {
		when(this.writer.updateDefaults(any()))
			.thenReturn(new AiConfigService.Defaults(null, 2.5d, null, null, null, 5L));

		this.mockMvc
			.perform(put("/api/ai/settings/defaults").contentType(MediaType.APPLICATION_JSON)
				.content("{\"temperature\":2.5}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.version").value(5));

		when(this.writer.updateDefaults(any()))
			.thenThrow(new AiConfigException(AiConfigWriter.CODE_INVALID_PARAM, "temperature 必须在 [0,2]"));
		this.mockMvc
			.perform(put("/api/ai/settings/defaults").contentType(MediaType.APPLICATION_JSON)
				.content("{\"temperature\":3.0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(AiConfigWriter.CODE_INVALID_PARAM));
	}

	// ── 夹具与"精确键集"断言（M7） ───────────────────────────────────────────

	/** M7：顶层键集<b>精确</b>相等（多一个键、少一个键都红），而不是"含有某几个键"。 */
	private static void assertExactProviderKeySet(JsonNode provider) {
		Set<String> keys = fieldNames(provider);
		assertThat(keys).containsExactlyInAnyOrder("id", "code", "displayName", "protocol", "baseUrl",
				"completionsPath", "modelsPath", "keyConfigured", "keyLast4", "keyRef", "keyStatus", "source",
				"enabled", "models");
		assertExactModelKeySet(provider.path("models").get(0));
	}

	/** M7 第二层：{@code models[]} 每行的键集同样精确相等。 */
	private static void assertExactModelKeySet(JsonNode model) {
		assertThat(fieldNames(model)).containsExactlyInAnyOrder("id", "modelId", "displayName",
				"supportsToolCalling", "supportsVision", "supportsReasoning", "maxContext", "maxOutput", "enabled",
				"isDefault", "sortOrder");
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> keys = new TreeSet<>();
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		keys.addAll(names);
		return keys;
	}


	private static AiConfigService.KeyResolution keyOk(String apiKey, String fingerprint) {
		return new AiConfigService.KeyResolution(AiConfigService.KeyStatus.OK, apiKey, fingerprint);
	}

	private static AiConfigService.KeyResolution keyMissing() {
		return new AiConfigService.KeyResolution(AiConfigService.KeyStatus.MISSING, null, null);
	}

	private static byte[] fill(byte value) {
		byte[] bytes = new byte[32];
		java.util.Arrays.fill(bytes, value);
		return bytes;
	}

	private static AiProvider provider(String envelope) {
		AiProvider provider = new AiProvider();
		provider.setId(7L);
		provider.setCode("custom-1");
		provider.setDisplayName("自定义");
		provider.setProtocol("OPENAI_COMPAT");
		provider.setBaseUrl("https://api.deepseek.com");
		provider.setCompletionsPath("/chat/completions");
		provider.setModelsPath("/models");
		provider.setKeyRef(envelope == null ? AiProvider.KEY_REF_NONE : AiProvider.KEY_REF_DB);
		provider.setKeyCipher(envelope);
		provider.setKeyLast4(envelope == null ? null : "4455");
		provider.setSource(AiProvider.SOURCE_DB);
		provider.setEnabled(true);
		return provider;
	}

	private static AiProviderModel model() {
		AiProviderModel model = new AiProviderModel();
		model.setId(9L);
		model.setProviderId(7L);
		model.setModelId("deepseek-flash");
		model.setDisplayName("deepseek-flash");
		model.setMaxContext(128000);
		model.setMaxOutput(4096);
		model.setSupportsToolCalling(true);
		model.setSupportsVision(Boolean.FALSE);
		model.setSupportsReasoning(Boolean.FALSE);
		model.setEnabled(true);
		model.setDefault(true);
		return model;
	}

}
