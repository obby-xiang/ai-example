package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiConfigWriter;
import com.example.configmgr.ai.config.AiConnectionProbe;
import com.example.configmgr.ai.config.AiModelCatalog;
import com.example.configmgr.ai.web.dto.AvailableModelView;
import com.example.configmgr.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/ai/models}（批③ 面板的选模型数据源；设计稿 §3.5）。
 *
 * <h2>为什么用新端点而不是复用 {@code /providers}</h2>
 * 见 {@link AiConfigService#availableModels()} 的方法注：面板只要"能选什么"，过滤口径必须只有一份。
 *
 * <h2>键集即契约</h2>
 * 前端 {@code api/aiConfig.ts} 的 {@code AiModelView} 与本响应逐键对应；少键 = 面板渲染 undefined，
 * 多键 = 两套真相。故这里断言<b>序列化后的键集</b>，而不是只在类型层面自证。
 */
class AiModelsEndpointTest {

	private final AiConfigService configService = mock(AiConfigService.class);

	@Test
	void modelsEndpointReturnsOnlyUsableModels() throws Exception {
		when(this.configService.availableModels()).thenReturn(List.of(
				new AiConfigService.AvailableModel(7L, "deepseek", "DeepSeek", "deepseek-flash", "Flash", true,
						false, false, 128000, 8192, true),
				new AiConfigService.AvailableModel(7L, "deepseek", "DeepSeek", "deepseek-pro", "Pro", false,
						true, true, 128000, 8192, false)));

		ApiResponse<List<AvailableModelView>> response = controller().models();

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.getData()).hasSize(2);
		assertThat(response.getData().get(0).modelId()).isEqualTo("deepseek-flash");
		assertThat(response.getData().get(1).supportsToolCalling()).as("能力位随行返回（面板据此出降级黄条）").isFalse();
		assertThat(response.getData().get(0).isDefault()).isTrue();
	}

	@Test
	void wireKeySetIsFrozen() throws Exception {
		when(this.configService.availableModels()).thenReturn(List.of(
				new AiConfigService.AvailableModel(7L, "deepseek", "DeepSeek", "deepseek-flash", "Flash", true,
						false, false, 128000, 8192, true)));
		ObjectMapper mapper = new ObjectMapper();
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String json = mapper.writeValueAsString(controller().models().getData().get(0));

		assertThat(json).contains("\"modelId\"", "\"displayName\"", "\"providerId\"", "\"supportsToolCalling\"",
				"\"maxContext\"", "\"maxOutput\"", "\"isDefault\"");
	}

	@Test
	void emptyWhenNothingIsUsable() {
		when(this.configService.availableModels()).thenReturn(List.of());

		ApiResponse<List<AvailableModelView>> response = controller().models();

		assertThat(response.isSuccess()).as("没有可用模型是<b>状态</b>不是错误：空数组，不是 500").isTrue();
		assertThat(response.getData()).isEmpty();
	}

	private AiConfigController controller() {
		return new AiConfigController(this.configService, mock(AiConfigWriter.class), mock(AiConnectionProbe.class),
				mock(AiModelCatalog.class));
	}

}
