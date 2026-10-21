package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import org.junit.jupiter.api.Test;

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

}
