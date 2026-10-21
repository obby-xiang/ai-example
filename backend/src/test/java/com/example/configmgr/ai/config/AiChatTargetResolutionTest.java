package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批③ 请求级解析（{@link AiConfigService#resolveChatTarget(String)}）的判据表。
 *
 * <h2>为什么这一层单独成类</h2>
 * "用户选的模型"要穿过三件事才落地：provider 是否启用、key 是否可解析、模型是否在该 provider
 * 的 allowlist 里。把它们拆在控制器/服务/注册表各处，任何一处漏判都会变成"选了却跑别人的模型"。
 * 这里用批① 的真实服务（内存仓储夹具）钉住这四条，控制器只负责把空结果翻成 400。
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 {@code resolveChatTarget} 里的显式分支删掉（直接走 {@code resolveDefault}）⇒
 *     {@code explicitModelWinsOverDefaultFlagAndSetting} 红；</li>
 * <li>把 {@code enabled} / {@code KeyStatus.OK} 两个过滤去掉 ⇒
 *     {@code modelsOfDisabledProvidersAreNotSelectable} 红；</li>
 * <li>把合成行（{@code id=null}）排除在候选之外 ⇒ {@code envSeedModelStaysSelectable} 红
 *     （CI/E2E 的主路径会被判 400）。</li>
 * </ul>
 */
class AiChatTargetResolutionTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	// ── 优先级 ──────────────────────────────────────────────────────────────

	@Test
	void explicitModelWinsOverDefaultFlagAndSetting() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.addModel(provider.getId(), "deepseek-pro", false);
		// 全局默认也指向 flash —— 两级"默认"都不该压过用户的选择
		this.fixture.settings.put("default.modelId", "deepseek-flash");
		AiConfigService service = this.fixture.service();

		Optional<AiConfigService.ChatTarget> target = service.resolveChatTarget("deepseek-pro");

		assertThat(target).isPresent();
		assertThat(target.get().modelId()).isEqualTo("deepseek-pro");
		assertThat(target.get().provider().providerId()).isEqualTo(provider.getId());
	}

	@Test
	void blankModelIdKeepsTheLegacyChain() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.addModel(provider.getId(), "deepseek-pro", false);
		AiConfigService service = this.fixture.service();

		// 行为等价（批③ 之前的口径）：没有请求模型 ⇒ is_default 标记的模型
		assertThat(service.resolveChatTarget(null)).get()
			.extracting(AiConfigService.ChatTarget::modelId)
			.isEqualTo("deepseek-flash");
		assertThat(service.resolveChatTarget("  ")).get()
			.extracting(AiConfigService.ChatTarget::modelId)
			.isEqualTo("deepseek-flash");
	}

	// ── 不可用的三条判据 ─────────────────────────────────────────────────────

	@Test
	void modelsOfDisabledProvidersAreNotSelectable() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();
		assertThat(service.resolveChatTarget("deepseek-flash")).as("停用之前可选").isPresent();

		provider.setEnabled(false);
		this.fixture.bumpVersion();

		assertThat(service.resolveChatTarget("deepseek-flash")).as("停用后判不可选（控制器据此 400）").isEmpty();
	}

	@Test
	void modelsOfProvidersWithUnreadableKeysAreNotSelectable() {
		AiProvider provider = this.fixture.addDbProvider("db-only", "http://placeholder",
				"v1.not-a-real-envelope");
		this.fixture.addModel(provider.getId(), "db-model", true);
		AiConfigService service = this.fixture.service();

		assertThat(this.fixture.settings).containsKey("config.version");
		assertThat(service.availability().available()).isFalse();
		assertThat(service.resolveChatTarget("db-model")).isEmpty();
	}

	@Test
	void unknownModelYieldsEmpty() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);

		assertThat(this.fixture.service().resolveChatTarget("no-such-model")).isEmpty();
	}

	@Test
	void disabledModelRowIsNotSelectable() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.addModel(provider.getId(), "retired-model", false, false);

		assertThat(this.fixture.service().resolveChatTarget("retired-model")).isEmpty();
	}

	// ── 合成行（env 种子）不许被误杀 ─────────────────────────────────────────

	@Test
	void envSeedModelStaysSelectable() {
		// 空表 + env key ⇒ effectiveProviders 合成一行（id=null），模型 = chat.options.model
		AiConfigService service = this.fixture.service();

		Optional<AiConfigService.ChatTarget> target = service.resolveChatTarget("deepseek-flash");

		assertThat(target).as("env 种子模型必须可选：CI/E2E 全靠它").isPresent();
		assertThat(target.get().provider().providerId()).as("合成行没有库内 id").isNull();
		assertThat(target.get().modelId()).isEqualTo("deepseek-flash");
	}

	// ── 能力位 / 参数开关 ────────────────────────────────────────────────────

	@Test
	void toolCallingCapabilityIsCarriedByTheTarget() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		AiProviderModel plain = this.fixture.addModel(provider.getId(), "plain-model", false);
		plain.setSupportsToolCalling(false);
		this.fixture.addModel(provider.getId(), "tool-model", true);
		AiConfigService service = this.fixture.service();

		assertThat(service.resolveChatTarget("plain-model")).get()
			.extracting(AiConfigService.ChatTarget::supportsToolCalling)
			.isEqualTo(false);
		assertThat(service.resolveChatTarget("tool-model")).get()
			.extracting(AiConfigService.ChatTarget::supportsToolCalling)
			.isEqualTo(true);
	}

	@Test
	void allowUserParamsDefaultsToTrueAndRespectsExplicitFalse() {
		AiConfigService service = this.fixture.service();
		assertThat(service.allowUserParams()).as("未设置 = 允许（与设置页 allowUserParams !== false 同口径）").isTrue();

		this.fixture.settings.put("allow.userParams", "false");
		this.fixture.bumpVersion();

		assertThat(this.fixture.service().allowUserParams()).isFalse();
	}

	// ── 面板可选模型清单（GET /api/ai/models 的底层） ─────────────────────────

	@Test
	void availableModelsListsOnlyUsableRows() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.addModel(provider.getId(), "retired-model", false, false);

		assertThat(this.fixture.service().availableModels())
			.extracting(AiConfigService.AvailableModel::modelId)
			.containsExactly("deepseek-flash");
	}

}
