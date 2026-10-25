package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批③ 逐请求 options（{@link AiProviderRegistry#forRequest} / {@code Overrides}）。
 *
 * <h2>钉住的两件事</h2>
 * <ol>
 * <li><b>缓存不抖动</b>：同一 provider 交替选两个模型，缓存条目数恒为 1，默认路径的模型名不被
 *     请求级模型污染（这是批① {@code runtimeFor} 直接套用到逐请求模型的真实故障面）；</li>
 * <li><b>覆盖只碰三值</b>：temperature/top_p/max_tokens 覆盖全局默认，{@code streamUsage} 不动
 *     （{@code done.usage} 的唯一来源）；越界请求值逐项忽略而不是抛出。</li>
 * </ol>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 {@code forRequest} 改成直接调 {@code runtimeFor(resolved with model)} ⇒
 *     {@code alternatingModelsKeepOneCachedRuntime} 里"默认模型未被污染"那半条红；</li>
 * <li>在覆盖段里顺手 {@code setStreamUsage(false)} ⇒
 *     {@code requestParamsKeepStreamUsageForTheUsageMeter} 红。</li>
 * </ul>
 */
class AiProviderRegistryParamsTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	@Test
	void alternatingModelsKeepOneCachedRuntime() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.addModel(provider.getId(), "deepseek-pro", false);
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		ProviderRuntime flash = registry.forRequest(resolved, "deepseek-flash",
				AiProviderRegistry.Overrides.NONE);
		ProviderRuntime pro = registry.forRequest(resolved, "deepseek-pro", AiProviderRegistry.Overrides.NONE);
		ProviderRuntime flashAgain = registry.forRequest(resolved, "deepseek-flash",
				AiProviderRegistry.Overrides.NONE);

		assertThat(flash.modelId()).isEqualTo("deepseek-flash");
		assertThat(pro.modelId()).isEqualTo("deepseek-pro");
		assertThat(flashAgain.modelId()).isEqualTo("deepseek-flash");
		assertThat(pro.api()).as("复用同一个 HTTP 客户端（只换 model/client）").isSameAs(flash.api());
		assertThat(registry.runtimeCount()).as("缓存条目 = RuntimeKey 个数，不随模型抖动").isEqualTo(1);
		assertThat(registry.current().modelId()).as("默认路径不被请求级模型污染").isEqualTo("deepseek-flash");
	}

	@Test
	void requestParamsOverrideGlobalDefaultsAndKeepStreamUsage() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.settings.put("default.temperature", "0.7");
		this.fixture.settings.put("default.topP", "0.9");
		this.fixture.settings.put("default.maxTokens", "2048");
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		ProviderRuntime runtime = registry.forRequest(resolved, "deepseek-flash",
				new AiProviderRegistry.Overrides(0.1d, 0.3d, 256));

		assertThat(runtime.options().getTemperature()).isEqualTo(0.1d);
		assertThat(runtime.options().getTopP()).isEqualTo(0.3d);
		assertThat(runtime.options().getMaxTokens()).isEqualTo(256);
		assertThat(runtime.options().getStreamUsage()).as("计量源不许因为用户调参而消失").isTrue();
	}

	@Test
	void outOfRangeRequestParamsAreDroppedNotFatal() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.settings.put("default.temperature", "0.7");
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		ProviderRuntime runtime = registry.forRequest(resolved, "deepseek-flash",
				new AiProviderRegistry.Overrides(5d, -1d, 0));

		assertThat(runtime.options().getTemperature()).as("越界项被忽略 ⇒ 回到全局默认").isEqualTo(0.7d);
		assertThat(runtime.options().getTopP()).as("全局未设置 ⇒ 沿用 spring.ai 缺省").isNull();
		assertThat(runtime.options().getMaxTokens()).isEqualTo(4096);
	}

	@Test
	void noOverrideAndSameModelReusesTheCachedInstance() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);

		ProviderRuntime base = registry.forRequest(service.resolveChatTarget(null).orElseThrow().provider(),
				null, AiProviderRegistry.Overrides.NONE);

		assertThat(base).isSameAs(registry.current());
	}

	// ── L-26：续跑按快照冻结值派生运行态 ────────────────────────────────────────

	/**
	 * 冻结值<b>赢过</b>之后才改的全局默认：这是 L-26 的核心 —— 同一轮前后半段必须跑在同一组参数上。
	 */
	@Test
	void frozenParamsWinOverLaterGlobalDefaultChanges() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.settings.put("default.temperature", "0.2");
		this.fixture.settings.put("default.topP", "0.1");
		this.fixture.settings.put("default.maxTokens", "4096");
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		// 生效值 = 0.1 / 0.3 / 256（首轮冻结）；之后运维把全局默认改成 0.9 / 0.8 / 8192
		this.fixture.settings.put("default.temperature", "0.9");
		this.fixture.settings.put("default.topP", "0.8");
		this.fixture.settings.put("default.maxTokens", "8192");

		ProviderRuntime runtime = registry.forFrozenRequest(resolved, "deepseek-flash",
				new AiProviderRegistry.Overrides(0.1d, 0.3d, 256));

		assertThat(runtime.options().getTemperature()).isEqualTo(0.1d);
		assertThat(runtime.options().getTopP()).isEqualTo(0.3d);
		assertThat(runtime.options().getMaxTokens()).isEqualTo(256);
		assertThat(runtime.options().getStreamUsage()).as("计量源与冻结无关，继续为 true").isTrue();
		assertThat(runtime.modelId()).isEqualTo("deepseek-flash");
	}

	/** 冻结的 null 必须<b>清空</b> option，不能被此刻的全局默认填上。 */
	@Test
	void frozenNullsAreNotFilledByGlobalDefaults() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.settings.put("default.temperature", "0.7");
		this.fixture.settings.put("default.topP", "0.6");
		this.fixture.settings.put("default.maxTokens", "2048");
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		ProviderRuntime runtime = registry.forFrozenRequest(resolved, "deepseek-flash",
				new AiProviderRegistry.Overrides(null, null, null));

		assertThat(runtime.options().getTemperature()).as("冻结 null ⇒ 就是没有该项").isNull();
		assertThat(runtime.options().getTopP()).isNull();
		assertThat(runtime.options().getMaxTokens())
			.as("冻结 null = 清空（不发该字段），不是被此刻的全局默认 2048 填上")
			.isNull();
	}

	/**
	 * 跨供应方推理隔离的<b>接线</b>：模型实例必须携带当前供应方主键（隔离判定的"当前"一极）。
	 *
	 * <p>为什么这条要单独锁：隔离逻辑本身在补丁模型里（单测覆盖），但若 {@code newModel} 忘了把
	 * providerId 传下去，生产行为会静默退回"未知当前供应方 ⇒ 一律回放"—— 单测全绿、线上不隔离。
	 */
	@Test
	void modelCarriesTheProviderIdentityForReasoningIsolation() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		ProviderRuntime cached = registry.current();
		ProviderRuntime frozen = registry.forFrozenRequest(resolved, "deepseek-flash",
				new AiProviderRegistry.Overrides(0.1d, null, null));

		assertThat(cached.chatModel()).isInstanceOf(ReasoningAwareOpenAiChatModel.class);
		assertThat(((ReasoningAwareOpenAiChatModel) cached.chatModel()).currentProviderId())
			.isEqualTo(provider.getId());
		assertThat(((ReasoningAwareOpenAiChatModel) frozen.chatModel()).currentProviderId())
			.as("续跑派生实例同样带供应方身份（否则续跑轮的隔离失效）").isEqualTo(provider.getId());
	}

	/** 冻结派生是<b>一次性</b>运行态：不写缓存、不污染默认路径的模型名。 */
	@Test
	void frozenRequestDoesNotPolluteTheCachedRuntime() {
		AiProvider provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(provider.getId(), "deepseek-flash", true);
		this.fixture.addModel(provider.getId(), "deepseek-pro", false);
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("deepseek-flash")
			.orElseThrow()
			.provider();

		ProviderRuntime resumed = registry.forFrozenRequest(resolved, "deepseek-pro",
				new AiProviderRegistry.Overrides(0.1d, null, null));

		assertThat(resumed.modelId()).isEqualTo("deepseek-pro");
		assertThat(registry.current().modelId()).as("默认路径不被续跑轮的模型污染").isEqualTo("deepseek-flash");
		assertThat(registry.current().options().getTemperature()).as("默认路径的参数不被续跑轮覆盖")
			.isNotEqualTo(0.1d);
		assertThat(registry.runtimeCount()).isEqualTo(1);
	}

}
