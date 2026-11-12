package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiConfigWriter;
import com.example.configmgr.ai.config.AiConnectionProbe;
import com.example.configmgr.ai.config.AiEndpointGuard;
import com.example.configmgr.ai.config.AiKeyCipher;
import com.example.configmgr.ai.config.AiModelCatalog;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.common.ErrorBodyFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B4（泄密面）：{@code GET /api/ai/providers} 的响应体<b>不含</b>任何 key/cipher 字段与值。
 *
 * <p>
 * <b>证红</b>：给 {@code ProviderView} 加回一个 {@code apiKey}/{@code keyCipher} 成分
 * （或在控制器里把 {@link AiProvider} 实体直接序列化）⇒ "键集"与"值不含密文"两段同时变红。
 *
 * <p>
 * 同时锁住批① 的端点契约：业务信封 {@code ApiResponse}（{@code success/data}）、只读
 * （只有 GET 映射）、模型 allowlist 内嵌。
 */
class AiConfigControllerTest {

	private static final String RAW_INTERNAL_DETAIL = "URISyntaxException 127.0.0.1 config-output-test-placeholder";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiConfigService configService = mock(AiConfigService.class);

	private final AiConfigWriter writer = mock(AiConfigWriter.class);

	private final AiConnectionProbe probe = mock(AiConnectionProbe.class);

	private final AiModelCatalog catalog = mock(AiModelCatalog.class);

	private final AiConfigController controller = new AiConfigController(this.configService, this.writer, this.probe,
			this.catalog);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller).build();

	{
		java.lang.reflect.Field factoryField = org.springframework.util.ReflectionUtils.findField(AiConfigController.class,
				"errorBodyFactory");
		if (factoryField != null) {
			org.springframework.util.ReflectionUtils.makeAccessible(factoryField);
			org.springframework.util.ReflectionUtils.setField(factoryField, this.controller, errorBodyFactory());
		}
	}

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	private static ErrorBodyFactory errorBodyFactory() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding(StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
		return new ErrorBodyFactory(beans.getBeanProvider(org.springframework.context.MessageSource.class));
	}

	@Test
	void providersResponseCarriesOnlyTheThreeKeyFacts() throws Exception {
		String secret = "b4-probe-value-0123456789abcdef";
		String envelope = new AiKeyCipher(java.util.Base64.getEncoder().encodeToString(fill((byte) 0x2a)))
			.encrypt(secret, "deepseek", AiProvider.KEY_REF_DB);
		AiProvider provider = provider("deepseek", envelope);
		when(this.configService.listProviders()).thenReturn(List.of(provider));
		when(this.configService.modelsOf(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(model()));
		when(this.configService.resolveKey(provider))
			.thenReturn(keyOk(secret, "fp-0011223344"));
		when(this.configService.keyStatus(provider)).thenReturn("OK");

		MvcResult result = this.mockMvc.perform(get("/api/ai/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].code").value("deepseek"))
			.andExpect(jsonPath("$.data[0].keyConfigured").value(true))
			.andExpect(jsonPath("$.data[0].keyLast4").value("cdef"))
			.andExpect(jsonPath("$.data[0].keyRef").value("DB"))
			.andExpect(jsonPath("$.data[0].keyStatus").value("OK"))
			.andExpect(jsonPath("$.data[0].source").value("DB"))
			.andExpect(jsonPath("$.data[0].models[0].modelId").value("deepseek-flash"))
			.andExpect(jsonPath("$.data[0].models[0].maxContext").value(128000))
			.andExpect(jsonPath("$.data[0].models[0].supportsToolCalling").value(true))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain(secret).doesNotContain(envelope).doesNotContain("v1.k1.");

		Set<String> keys = new TreeSet<>();
		collectKeys(this.mapper.readTree(body), keys);
		assertThat(keys).as("只允许三件套：keyConfigured / keyLast4 / keyRef")
			.noneMatch(key -> key.equalsIgnoreCase("key") || key.equalsIgnoreCase("apiKey")
					|| key.equalsIgnoreCase("keyCipher") || key.equalsIgnoreCase("cipher")
					|| key.equalsIgnoreCase("keyFingerprint") || key.equalsIgnoreCase("secret"));
		assertThat(keys).contains("keyConfigured", "keyLast4", "keyRef");

		// M7（批① 复审登记，批② 收口）：字段白名单从"含有某几个键"升级为**精确键集**——
		// 顶层一层 + models[] 一层；多一个键（例如哪天把 keyCipher 加回 DTO）或少一个键都红。
		JsonNode providerNode = this.mapper.readTree(body).path("data").get(0);
		assertThat(fieldNames(providerNode)).as("顶层键集精确相等")
			.containsExactlyInAnyOrder("id", "code", "displayName", "protocol", "baseUrl", "completionsPath",
					"modelsPath", "keyConfigured", "keyLast4", "keyRef", "keyStatus", "source", "enabled", "models");
		assertThat(fieldNames(providerNode.path("models").get(0))).as("models[] 键集精确相等")
			.containsExactlyInAnyOrder("id", "modelId", "displayName", "supportsToolCalling", "supportsVision",
					"supportsReasoning", "maxContext", "maxOutput", "enabled", "isDefault", "sortOrder");
	}

	@Test
	void emptyConfigReturnsAnEmptyListRatherThanAnError() throws Exception {
		when(this.configService.listProviders()).thenReturn(List.of());

		this.mockMvc.perform(get("/api/ai/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").isArray())
			.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	void configRejectionUsesStableCodeAndLocalizedMessageWithoutInternalDetails() throws Exception {
		when(this.writer.createProvider(any())).thenThrow(new AiEndpointGuard.GuardException(
				AiEndpointGuard.CODE_INVALID_BASE_URL, RAW_INTERNAL_DETAIL));

		MvcResult zh = this.mockMvc.perform(post("/api/ai/providers")
				.header("Accept-Language", "zh-CN")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(AiEndpointGuard.CODE_INVALID_BASE_URL))
			.andExpect(jsonPath("$.message").value("接口地址不合法"))
			.andReturn();
		assertThat(zh.getResponse().getContentAsString()).doesNotContain("URISyntaxException", "127.0.0.1",
				"config-output-test-placeholder", RAW_INTERNAL_DETAIL);

		MvcResult en = this.mockMvc.perform(post("/api/ai/providers")
				.header("Accept-Language", "en-US")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(AiEndpointGuard.CODE_INVALID_BASE_URL))
			.andExpect(jsonPath("$.message").value("Base URL is invalid"))
			.andReturn();
		assertThat(en.getResponse().getContentAsString()).doesNotContain("URISyntaxException", "127.0.0.1",
				"config-output-test-placeholder", RAW_INTERNAL_DETAIL);
	}

	@Test
	void probeFailuresKeepOkFalseSemanticsWithoutRawResultMessages() throws Exception {
		AiProvider provider = provider("deepseek", "not-a-secret-envelope");
		when(this.configService.provider(7L)).thenReturn(java.util.Optional.of(provider));
		when(this.configService.resolveKey(provider)).thenReturn(keyOk("config-output-test-placeholder", "fp-safe"));
		when(this.probe.fetchModels(anyString(), anyString(), anyString(), anyBoolean()))
			.thenReturn(new AiConnectionProbe.ProbeResult(false, 401, 3L, AiConnectionProbe.CODE_AUTH_FAILED,
					RAW_INTERNAL_DETAIL, 0, List.of()));
		when(this.catalog.fetch(anyLong(), anyBoolean()))
			.thenReturn(new AiModelCatalog.CatalogResult(false, List.of(), false,
					AiConnectionProbe.CODE_MODELS_INVALID, RAW_INTERNAL_DETAIL));

		MvcResult test = this.mockMvc.perform(post("/api/ai/providers/7/test")
				.header("Accept-Language", "zh-CN"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.ok").value(false))
			.andExpect(jsonPath("$.data.status").value(401))
			.andExpect(jsonPath("$.data.code").value(AiConnectionProbe.CODE_AUTH_FAILED))
			.andExpect(jsonPath("$.data.message").value("上游鉴权失败"))
			.andReturn();
		assertThat(test.getResponse().getContentAsString()).doesNotContain("URISyntaxException", "127.0.0.1",
				"config-output-test-placeholder", RAW_INTERNAL_DETAIL);

		MvcResult fetch = this.mockMvc.perform(post("/api/ai/providers/7/models/fetch")
				.header("Accept-Language", "en-US"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.ok").value(false))
			.andExpect(jsonPath("$.data.code").value(AiConnectionProbe.CODE_MODELS_INVALID))
			.andExpect(jsonPath("$.data.message").value("Model list format is invalid"))
			.andReturn();
		assertThat(fetch.getResponse().getContentAsString()).doesNotContain("URISyntaxException", "127.0.0.1",
				"config-output-test-placeholder", RAW_INTERNAL_DETAIL);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────


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

	private static AiProvider provider(String code, String envelope) {
		AiProvider provider = new AiProvider();
		provider.setId(7L);
		provider.setCode(code);
		provider.setDisplayName("DeepSeek");
		provider.setProtocol("OPENAI_COMPAT");
		provider.setBaseUrl("https://api.deepseek.com");
		provider.setCompletionsPath("/chat/completions");
		provider.setModelsPath("/models");
		provider.setKeyRef(AiProvider.KEY_REF_DB);
		provider.setKeyCipher(envelope);
		provider.setKeyLast4("cdef");
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
		model.setEnabled(true);
		model.setDefault(true);
		return model;
	}

	private static void collectKeys(JsonNode node, Set<String> out) {
		if (node.isObject()) {
			List<String> names = new ArrayList<>();
			node.fieldNames().forEachRemaining(names::add);
			out.addAll(names);
			node.elements().forEachRemaining(child -> collectKeys(child, out));
		}
		else if (node.isArray()) {
			node.elements().forEachRemaining(child -> collectKeys(child, out));
		}
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> keys = new TreeSet<>();
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		keys.addAll(names);
		return keys;
	}

}
