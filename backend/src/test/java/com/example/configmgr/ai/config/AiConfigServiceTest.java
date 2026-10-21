package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiSetting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6（KEK 错误/变更 ⇒ {@code KEY_UNREADABLE}，<b>不</b>回落 env）＋ 读路径的解析与"空表 + env"等价性。
 *
 * <p>
 * <b>证红</b>
 * <ul>
 * <li>B6：在 {@code AiConfigService#resolveKey} 的 {@code KEY_REF_DB} 分支里加一句"解不开就用 env key
 * 兜底" ⇒ {@code dbKeyWithAnotherKekDoesNotFallBackToEnv} 立刻变红；</li>
 * <li>空表 + env 等价性：把合成行改成"baseUrl 用 hard-code"或"model 取硬编码 deepseek-flash"
 * ⇒ {@code emptyTableWithEnvResolvesToTheSameValuesAsToday} 变红；</li>
 * <li>版本热生效：把 {@code shouldReload} 恒 false ⇒ {@code versionBumpReloadsTheSnapshot} 变红。</li>
 * </ul>
 */
class AiConfigServiceTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	// ── B6：KEK 错误/变更 ────────────────────────────────────────────────────

	@Test
	void dbKeyWithAnotherKekIsUnreadableAndDoesNotFallBackToEnv() {
		// 密文由 KEK_B 产生，服务持有 KEK_A；同时 env 里<b>有</b>一个可用 key（回落诱惑）
		String envelope = new AiKeyCipher(AiConfigTestFixture.KEK_B).encrypt("db-key-probe-abcd", "deepseek",
				AiProvider.KEY_REF_DB);
		this.fixture.addDbProvider("deepseek", "http://db-endpoint", envelope);
		AiConfigService service = this.fixture.service(AiConfigTestFixture.KEK_A);

		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_KEY_UNREADABLE);
		assertThat(service.availability().reason()).contains("无法解密");
		// 绝不静默回落到 env key（否则"为什么突然用错 key"无从排查）
		assertThat(service.resolveDefault()).isEmpty();
		assertThat(service.resolveKey(this.fixture.providers.get(0)).status())
			.isEqualTo(AiConfigService.KeyStatus.UNREADABLE);
	}

	@Test
	void dbKeyWithTheRightKekResolvesToTheDecryptedValue() {
		String envelope = new AiKeyCipher(AiConfigTestFixture.KEK_A).encrypt("db-key-probe-abcd", "deepseek",
				AiProvider.KEY_REF_DB);
		this.fixture.addDbProvider("deepseek", "http://db-endpoint", envelope);
		AiConfigService service = this.fixture.service(AiConfigTestFixture.KEK_A);

		assertThat(service.resolveDefault()).get().satisfies(resolved -> {
			assertThat(resolved.apiKey()).isEqualTo("db-key-probe-abcd");
			assertThat(resolved.baseUrl()).isEqualTo("http://db-endpoint");
			assertThat(resolved.envBacked()).isFalse();
		});
		assertThat(service.availability().available()).isTrue();
	}

	@Test
	void providerWithoutAnyKeyMakesTheEndpointUnavailableWithKeyMissingCode() {
		AiProvider provider = this.fixture.addDbProvider("custom", "http://custom", null);
		provider.setKeyRef(AiProvider.KEY_REF_NONE);
		this.fixture.connectionProperties.setApiKey("");
		this.fixture.chatProperties.setApiKey("");
		AiConfigService service = this.fixture.service();

		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_KEY_MISSING);
	}

	// ── 空表 + 无 env / 空表 + env ───────────────────────────────────────────

	@Test
	void emptyTableWithoutEnvKeepsTheLegacyUnavailableContract() {
		this.fixture.connectionProperties.setApiKey("");
		this.fixture.chatProperties.setApiKey("");
		AiConfigService service = this.fixture.service();

		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo("AI_UNAVAILABLE");
		// 与批①之前<b>逐字相同</b>的文案（前端降级提示与 E2E 的兼容基线）
		assertThat(service.availability().reason())
			.isEqualTo("AI 能力未启用：未配置 AI_API_KEY（或 DEEPSEEK_API_KEY）环境变量，AI 端点已惰性降级");
		assertThat(service.listProviders()).isEmpty();
		assertThat(service.describeProviders()).isEmpty();
	}

	@Test
	void emptyTableWithEnvResolvesToTheSameValuesAsToday() {
		AiConfigService service = this.fixture.service();

		// 种子尚未落库时的合成行 = 今天 spring.ai.openai.* 的三键，逐值相同
		assertThat(service.availability().available()).isTrue();
		assertThat(service.resolveDefault()).get().satisfies(resolved -> {
			assertThat(resolved.providerId()).isNull();
			assertThat(resolved.providerCode()).isEqualTo(AiConfigService.ENV_PROVIDER_CODE);
			assertThat(resolved.baseUrl()).isEqualTo("http://env-endpoint:1234");
			assertThat(resolved.completionsPath()).isEqualTo("/chat/completions");
			assertThat(resolved.modelId()).isEqualTo("deepseek-flash");
			assertThat(resolved.apiKey()).isEqualTo("env-probe-1111");
			assertThat(resolved.envBacked()).isTrue();
		});
		// 合成行不进"库里的 provider 列表"（/api/ai/providers 只回真行）
		assertThat(service.listProviders()).isEmpty();
	}

	// ── 模型解析优先级 ───────────────────────────────────────────────────────

	@Test
	void defaultModelFlagWinsOverOrderAndSettings() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://env-endpoint:1234");
		this.fixture.addModel(provider.getId(), "first-model", false);
		this.fixture.addModel(provider.getId(), "flagged-model", true);
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "setting-model");
		AiConfigService service = this.fixture.service();

		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::modelId)
			.isEqualTo("flagged-model");
	}

	@Test
	void firstEnabledModelWinsWhenNoDefaultFlagAndSettingsComeAfter() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://env-endpoint:1234");
		this.fixture.addModel(provider.getId(), "disabled-model", false, false);
		this.fixture.addModel(provider.getId(), "enabled-model", false);
		AiConfigService service = this.fixture.service();
		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::modelId)
			.isEqualTo("enabled-model");

		// 全部停用 ⇒ 落到 ai_setting['default.modelId']（写路径会 +1 版本号 ⇒ 读路径重载快照）
		this.fixture.models.forEach(model -> model.setEnabled(false));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "setting-model");
		this.fixture.bumpVersion();
		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::modelId)
			.isEqualTo("setting-model");

		// 设置也没有 ⇒ 落到 env AI_MODEL（今天的行为）
		this.fixture.settings.remove(AiSetting.DEFAULT_MODEL_ID);
		this.fixture.bumpVersion();
		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::modelId)
			.isEqualTo("deepseek-flash");
	}

	@Test
	void disabledProviderIsSkipped() {
		AiProvider disabled = this.fixture.addEnvProvider("custom", "http://custom");
		disabled.setEnabled(false);
		AiProvider enabled = this.fixture.addEnvProvider("deepseek", "http://env-endpoint:1234");
		this.fixture.addModel(enabled.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();

		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::providerCode)
			.isEqualTo("deepseek");
	}

	@Test
	void firstUsableProviderWinsInSortOrder() {
		AiProvider broken = this.fixture.addDbProvider("broken", "http://broken", null);
		broken.setSortOrder(0);
		AiProvider good = this.fixture.addEnvProvider("deepseek", "http://env-endpoint:1234");
		good.setSortOrder(1);
		this.fixture.addModel(good.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();

		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::providerCode)
			.isEqualTo("deepseek");
	}

	// ── 版本号热生效（读路径） ───────────────────────────────────────────────

	@Test
	void versionBumpReloadsTheSnapshot() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://old-endpoint");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();
		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::baseUrl)
			.isEqualTo("http://old-endpoint");

		// 写路径（批②）会同时改 DB 版本号与 Redis ai:cfg:ver；这里只模拟 DB 真源那一半
		provider.setBaseUrl("http://new-endpoint");
		this.fixture.bumpVersion();

		assertThat(service.current().version()).isEqualTo(2);
		assertThat(service.resolveDefault()).get().extracting(AiConfigService.ResolvedProvider::baseUrl)
			.isEqualTo("http://new-endpoint");
	}

	@Test
	void runtimeKeyIsStableForTheSameRowAndChangesWithBaseUrlOrFingerprint() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://old-endpoint");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();

		String before = service.resolveDefault().orElseThrow().runtimeKey();
		assertThat(service.resolveDefault().orElseThrow().runtimeKey()).isEqualTo(before);

		provider.setBaseUrl("http://new-endpoint");
		this.fixture.bumpVersion();
		String after = service.resolveDefault().orElseThrow().runtimeKey();
		assertThat(after).isNotEqualTo(before).contains("http://new-endpoint").doesNotContain("env-probe-1111");
	}

}
