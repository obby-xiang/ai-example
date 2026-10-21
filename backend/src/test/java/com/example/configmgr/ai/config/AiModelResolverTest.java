package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProviderModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B12（解析优先级）：{@code 单轮/会话模型 > ai_model.is_default > 该 provider 首个 enabled 模型
 * > ai_setting['default.modelId'] > env AI_MODEL}。
 *
 * <p>
 * 单轮/会话两级在批③（{@code AiChatRequest#modelId}）才接入；本批把两级合并成一个
 * {@code explicitModelId} 入参 —— 语义相同（"请求侧显式指定的模型"），批③ 调用时传真实值即可，
 * 不会再来改这条链。
 *
 * <p>
 * <b>证红</b>：交换任意相邻两级 ⇒ 对应断言变红。
 */
class AiModelResolverTest {

	private static AiProviderModel model(String id, boolean isDefault, boolean enabled) {
		AiProviderModel model = new AiProviderModel();
		model.setModelId(id);
		model.setDefault(isDefault);
		model.setEnabled(enabled);
		return model;
	}

	@Test
	void explicitRequestModelWinsOverEverything() {
		assertThat(AiModelResolver.resolve("request-model",
				List.of(model("flagged", true, true), model("first", false, true)), "setting-model", "env-model"))
			.isEqualTo("request-model");
	}

	@Test
	void defaultFlagWinsOverFirstEnabledAndSettings() {
		assertThat(AiModelResolver.resolve(null, List.of(model("first", false, true), model("flagged", true, true)),
				"setting-model", "env-model")).isEqualTo("flagged");
	}

	@Test
	void firstEnabledModelWinsWhenNoDefaultFlag() {
		assertThat(AiModelResolver.resolve(null, List.of(model("disabled", false, false), model("enabled", false, true)),
				"setting-model", "env-model")).isEqualTo("enabled");
	}

	@Test
	void settingsDefaultWinsOverEnvWhenTheProviderHasNoEnabledModel() {
		assertThat(AiModelResolver.resolve(null, List.of(model("disabled", true, false)), "setting-model",
				"env-model")).isEqualTo("setting-model");
		assertThat(AiModelResolver.resolve(null, List.of(), "setting-model", "env-model")).isEqualTo("setting-model");
	}

	@Test
	void envModelIsTheLastResort() {
		assertThat(AiModelResolver.resolve(null, List.of(), null, "env-model")).isEqualTo("env-model");
		assertThat(AiModelResolver.resolve("  ", List.of(), " ", "env-model")).isEqualTo("env-model");
	}

	@Test
	void blankExplicitModelIsIgnoredRatherThanTreatedAsAModelName() {
		assertThat(AiModelResolver.resolve("", List.of(model("flagged", true, true)), null, "env-model"))
			.isEqualTo("flagged");
	}

}
