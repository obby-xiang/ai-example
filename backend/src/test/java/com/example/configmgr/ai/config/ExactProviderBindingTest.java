package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>I3 续跑精确绑定</b>：快照里有 {@code providerId} 时，解析必须落在<b>这一家</b> provider 上
 * （启用 + key 可解析 + 模型在该家 allowlist），失败即空 —— <b>不跨 provider、也不换模型</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 旧解析只有 {@code resolveChatTarget(modelId)}：同名模型在 A/B 两家都存在时，它按库序返回<b>第一家</b>；
 * 续跑因此可能把 B 的一轮发到 A（历史、{@code done.model}、加密思考的来源全部说谎）。
 *
 * <h2>两条路径</h2>
 * <ul>
 * <li>{@code providerId != null} ⇒ 精确绑定（本类主体）；</li>
 * <li>{@code providerId == null} ⇒ 旧的按模型名兼容（首轮/旧快照/旧客户端不改）。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * 让 {@code resolveChatTarget(Long,String)} 在绑定 provider 不可用时回落到"按模型名找第一家" ⇒
 * 第 2/3 例红（应当为空却拿到 A）。
 */
class ExactProviderBindingTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private AiProvider providerA;

	private AiProvider providerB;

	private AiConfigService service;

	@BeforeEach
	void setUp() {
		this.providerA = this.fixture.addEnvProvider("provider-a", "http://a-endpoint");
		this.providerB = this.fixture.addEnvProvider("provider-b", "http://b-endpoint");
		// 两家都有同名模型：只凭模型名无法区分
		this.fixture.addModel(this.providerA.getId(), "shared-model", true);
		this.fixture.addModel(this.providerA.getId(), "a-only", false);
		this.fixture.addModel(this.providerB.getId(), "shared-model", true);
		this.fixture.addModel(this.providerB.getId(), "b-only", false);
		this.service = this.fixture.service();
	}

	@Test
	void boundProviderWinsOverTheSameModelNameOnAnotherProvider() {
		AiConfigService.ChatTarget target = this.service
			.resolveChatTarget(this.providerB.getId(), "shared-model")
			.orElseThrow();

		assertThat(target.provider().providerId()).as("同名模型也必须落到快照那一家").isEqualTo(this.providerB.getId());
		assertThat(target.provider().baseUrl()).isEqualTo("http://b-endpoint");
		assertThat(target.modelId()).isEqualTo("shared-model");
	}

	@Test
	void modelNotAllowedOnTheBoundProviderDoesNotCrossProviders() {
		// a-only 只挂在 A 上；绑定了 B 却要求 a-only ⇒ 空（不能去 A 找）
		assertThat(this.service.resolveChatTarget(this.providerB.getId(), "a-only")).isEmpty();
		// 完全未知的模型同样为空
		assertThat(this.service.resolveChatTarget(this.providerB.getId(), "no-such-model")).isEmpty();
	}

	@Test
	void unusableBoundProviderIsEmptyEvenWhenAnotherProviderIsUsable() {
		this.providerB.setEnabled(false);
		this.service.reload();

		assertThat(this.service.resolveChatTarget(this.providerB.getId(), "shared-model"))
			.as("绑定 provider 停用 ⇒ 空（不静默换到 A）").isEmpty();

		// 反向确认 A 此刻确实可用（否则上面的空没有意义）
		assertThat(this.service.resolveChatTarget(this.providerA.getId(), "shared-model")).isPresent();
	}

	@Test
	void unknownProviderIdIsEmpty() {
		assertThat(this.service.resolveChatTarget(999_999L, "shared-model")).isEmpty();
	}

	/** 未指定模型时用<b>绑定 provider 自己的</b>默认模型，不借用别家/全局同名模型。 */
	@Test
	void blankModelUsesTheBoundProvidersOwnDefault() {
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "a-only");
		this.service.reload();

		AiConfigService.ChatTarget target = this.service.resolveChatTarget(this.providerB.getId(), null)
			.orElseThrow();

		assertThat(target.provider().providerId()).isEqualTo(this.providerB.getId());
		assertThat(target.modelId()).as("全局默认模型属于 A ⇒ B 用自己的默认模型").isIn("shared-model", "b-only");
	}

	/** 没有 providerId（首轮/旧快照/旧客户端）时保持旧行为：按模型名找第一家。 */
	@Test
	void nullProviderIdKeepsTheLegacyModelNameResolution() {
		Optional<AiConfigService.ChatTarget> legacy = this.service.resolveChatTarget(null, "shared-model");

		assertThat(legacy).isPresent();
		assertThat(legacy.orElseThrow().provider().providerId())
			.as("旧路径按库序取第一家（兼容口径，本批不改）").isEqualTo(this.providerA.getId());
	}

}
