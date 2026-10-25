package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiSetting;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认目标必须是一个<b>真实存在的 (providerId, modelId) 对</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 设置页默认模型下拉跨 provider，但旧写路径只保存 {@code default.modelId}；读路径取"排序第一的
 * enabled+key provider"——于是选了 B 的模型却发给 A（同名模型时静默错配，不同名时上游 404）。
 * 同一处的第二面：provider 没有任何模型时，旧解析链会回落到 {@code default.modelId} / env
 * {@code AI_MODEL}，把别家的模型名发给当前 provider。
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>去掉 {@code default.providerId} 的绑定（读路径退回"排序第一"）⇒ 第 1/2 条红；</li>
 * <li>让 {@code ownModelId} 在无自身模型时回落 env ⇒ 第 3 条红（A 会拿着
 *     {@code deepseek-flash} 打出去）；</li>
 * <li>写路径不淘汰运行态缓存 ⇒ 第 4 条红（请求体仍是旧的 temperature）；</li>
 * <li>max_tokens 上限按"全库第一个同名模型"取 ⇒ 第 5 条红（A 同名的 2048 会挡住 B 的 4096）。</li>
 * </ul>
 */
class AiDefaultTargetBindingTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final List<StubEndpoint> stubs = new ArrayList<>();

	@AfterEach
	void tearDown() {
		this.stubs.forEach(StubEndpoint::stop);
	}

	// ── 1. 保存 B ⇒ 真实请求只到 B（同名模型也不串台） ───────────────────────

	@Test
	void savingProviderBModelSendsTheNextRealChatToBOnly() throws Exception {
		StubEndpoint a = stub();
		StubEndpoint b = stub();
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", a.baseUrl());
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", b.baseUrl());
		// 两家都有同名模型 —— 旧实现只凭 modelId 无法区分，必须靠 providerId 绑定
		this.fixture.addModel(providerA.getId(), "shared-model", true);
		this.fixture.addModel(providerA.getId(), "a-only", false);
		this.fixture.addModel(providerB.getId(), "shared-model", true);
		this.fixture.addModel(providerB.getId(), "b-only", false);

		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigWriter writer = writer(service, registry);

		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", 0.4d, null, null, null,
				providerB.getId()));

		assertThat(this.fixture.settings)
			.containsEntry(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(providerB.getId()))
			.containsEntry(AiSetting.DEFAULT_MODEL_ID, "shared-model");
		assertThat(service.defaults().providerId()).isEqualTo(providerB.getId());
		assertThat(service.resolveDefault()).get().satisfies(resolved -> {
			assertThat(resolved.providerId()).isEqualTo(providerB.getId());
			assertThat(resolved.baseUrl()).isEqualTo(b.baseUrl());
			assertThat(resolved.modelId()).isEqualTo("shared-model");
		});

		chat(registry.current());

		assertThat(b.hits()).as("下一轮真实请求打到 B").isEqualTo(1);
		assertThat(a.hits()).as("排序第一的 A 不再被误打").isZero();
		assertThat(b.lastBody()).contains("\"model\":\"shared-model\"");
	}

	// ── 2. 旧 default.modelId（无 providerId）只认它的所有者 ─────────────────

	@Test
	void legacyDefaultModelIdRoutesToTheProviderThatOwnsIt() throws Exception {
		StubEndpoint a = stub();
		StubEndpoint b = stub();
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", a.baseUrl());
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", b.baseUrl());
		this.fixture.addModel(providerA.getId(), "a-model", true);
		this.fixture.addModel(providerB.getId(), "b-model", true);
		// 旧配置：只有 modelId，没有 providerId
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "b-model");

		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);

		assertThat(service.resolveDefault()).get().satisfies(resolved -> {
			assertThat(resolved.providerId()).isEqualTo(providerB.getId());
			assertThat(resolved.modelId()).isEqualTo("b-model");
		});

		chat(registry.current());
		assertThat(b.hits()).isEqualTo(1);
		assertThat(a.hits()).isZero();
		assertThat(b.lastBody()).contains("\"model\":\"b-model\"");
	}

	// ── 3. A 无模型 ⇒ NO_MODEL_CONFIGURED，不借 B 的模型 ─────────────────────

	@Test
	void providerWithoutItsOwnModelsNeverBorrowsAnotherProvidersModel() throws Exception {
		StubEndpoint a = stub();
		StubEndpoint b = stub();
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", a.baseUrl()); // 没有任何模型
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", b.baseUrl());
		this.fixture.addModel(providerB.getId(), "b-model", true);
		// 绑定 A，但旧 modelId 属于 B：绝不能把 b-model 发给 A
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(providerA.getId()));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "b-model");

		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);

		assertThat(service.resolveDefault()).as("A 自己没有可用模型 ⇒ 不解析目标").isEmpty();
		assertThat(service.resolveChatTarget(null)).isEmpty();
		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_NO_MODEL_CONFIGURED);
		assertThat(service.availability().reason()).contains("设置 → AI");
		assertThatThrownBy(registry::current).isInstanceOf(AiUnavailableException.class)
			.extracting(ex -> ((AiUnavailableException) ex).getCode())
			.isEqualTo(AiConfigService.CODE_NO_MODEL_CONFIGURED);

		assertThat(a.hits()).isZero();
		assertThat(b.hits()).as("既不借 B 的模型，也不偷偷换到 B").isZero();
	}

	@Test
	void unboundLegacyModelWithoutAnyOwnerDoesNotGetPastedOntoTheFirstProvider() {
		AiProvider provider = this.fixture.addEnvProvider("provider-a", "http://placeholder");
		this.fixture.addModel(provider.getId(), "a-model", true);
		// 旧配置里的模型名在 allowlist 里不存在（已删除/手改）：不把它发给 A
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "ghost-model");

		AiConfigService service = this.fixture.service();

		assertThat(service.resolveDefault()).get()
			.extracting(AiConfigService.ResolvedProvider::modelId)
			.as("用 A 自己的模型，而不是 ghost-model")
			.isEqualTo("a-model");
	}

	@Test
	void boundDisabledProviderIsUnavailableInsteadOfFallingBackToAnotherProvider() throws Exception {
		StubEndpoint a = stub();
		StubEndpoint b = stub();
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", a.baseUrl());
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", b.baseUrl());
		this.fixture.addModel(providerA.getId(), "a-model", true);
		this.fixture.addModel(providerB.getId(), "b-model", true);
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(providerA.getId()));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "a-model");

		providerA.setEnabled(false);
		this.fixture.bumpVersion();

		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);

		assertThat(service.resolveDefault()).as("硬绑定：停用后不回退 B").isEmpty();
		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_UNAVAILABLE);
		assertThat(service.availability().reason()).contains("已停用").contains("设置 → AI");
		assertThatThrownBy(registry::current).isInstanceOf(AiUnavailableException.class)
			.extracting(ex -> ((AiUnavailableException) ex).getCode())
			.isEqualTo(AiConfigService.CODE_UNAVAILABLE);
		assertThat(a.hits()).isZero();
		assertThat(b.hits()).as("B 可用也不能被选中").isZero();
	}

	@Test
	void boundProviderWithMissingOrUnreadableKeyIsUnavailableInsteadOfFallingBack() {
		// key 缺失
		AiProvider missingKey = this.fixture.addDbProvider("provider-a", "http://placeholder", null);
		this.fixture.addModel(missingKey.getId(), "a-model", true);
		AiProvider other = this.fixture.addEnvProvider("provider-b", "http://placeholder");
		this.fixture.addModel(other.getId(), "b-model", true);
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(missingKey.getId()));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "a-model");

		AiConfigService service = this.fixture.service();
		assertThat(service.resolveDefault()).isEmpty();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_KEY_MISSING);
		assertThat(service.availability().reason()).contains("设置 → AI");
	}

	@Test
	void boundProviderRowMissingIsUnavailableInsteadOfFallingBack() {
		AiProvider provider = this.fixture.addEnvProvider("provider-a", "http://placeholder");
		this.fixture.addModel(provider.getId(), "a-model", true);
		// 绑定行已被硬删 / 脏配置：不回到"排序第一个可用 provider"
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, "999");
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "a-model");

		AiConfigService service = this.fixture.service();

		assertThat(service.resolveDefault()).isEmpty();
		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_UNAVAILABLE);
		assertThat(service.availability().reason()).contains("不存在").contains("设置 → AI");
	}

	@Test
	void boundProviderWithUnreadableKeyDoesNotFallBackToAUsableProvider() {
		String envelope = new AiKeyCipher(AiConfigTestFixture.KEK_B).encrypt("db-key-probe-abcd",
				"provider-a", AiProvider.KEY_REF_DB);
		AiProvider broken = this.fixture.addDbProvider("provider-a", "http://placeholder", envelope);
		this.fixture.addModel(broken.getId(), "a-model", true);
		AiProvider other = this.fixture.addEnvProvider("provider-b", "http://placeholder");
		this.fixture.addModel(other.getId(), "b-model", true);
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(broken.getId()));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "a-model");

		AiConfigService service = this.fixture.service(AiConfigTestFixture.KEK_A);

		assertThat(service.resolveDefault()).isEmpty();
		assertThat(service.availability().available()).isFalse();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_KEY_UNREADABLE);
	}

	@Test
	void boundModelDisabledOrDeletedDoesNotFallBackWithinOrAcrossProviders() {
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", "http://placeholder");
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", "http://placeholder");
		AiProviderModel providerDefault = this.fixture.addModel(providerA.getId(), "a-default", true);
		AiProviderModel bound = this.fixture.addModel(providerA.getId(), "bound-model", false);
		this.fixture.addModel(providerB.getId(), "b-model", true);
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(providerA.getId()));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "bound-model");

		AiConfigService service = this.fixture.service();
		assertThat(service.resolveDefault()).get()
			.extracting(AiConfigService.ResolvedProvider::modelId)
			.isEqualTo("bound-model");

		// 绑定模型停用 ⇒ 不允许换同 provider 的 a-default，也不允许换 B
		bound.setEnabled(false);
		this.fixture.bumpVersion();
		assertThat(service.resolveDefault()).isEmpty();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_NO_MODEL_CONFIGURED);

		// 绑定模型删除 ⇒ 同上
		this.fixture.models.remove(bound);
		this.fixture.bumpVersion();
		assertThat(service.resolveDefault()).isEmpty();
		assertThat(service.availability().code()).isEqualTo(AiConfigService.CODE_NO_MODEL_CONFIGURED);

		// 只有 modelId 为空时，才允许 provider 自己的默认模型
		this.fixture.settings.remove(AiSetting.DEFAULT_MODEL_ID);
		this.fixture.bumpVersion();
		assertThat(service.resolveDefault()).get().satisfies(resolved -> {
			assertThat(resolved.providerId()).isEqualTo(providerA.getId());
			assertThat(resolved.modelId()).isEqualTo("a-default");
		});
		assertThat(providerDefault.isEnabled()).isTrue();
	}

	// ── 4. 保存 temperature ⇒ 下一轮请求立刻用新值（不重启） ─────────────────

	@Test
	void savedTemperatureAppliesToTheNextRequestWithoutRestart() throws Exception {
		StubEndpoint endpoint = stub();
		AiProvider provider = this.fixture.addEnvProvider("provider-a", endpoint.baseUrl());
		this.fixture.addModel(provider.getId(), "a-model", true);

		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigWriter writer = writer(service, registry);

		ProviderRuntime first = registry.current();
		assertThat(first.options().getTemperature()).isEqualTo(0.2d);
		chat(first);
		assertThat(endpoint.lastBody()).contains("\"temperature\":0.2");

		writer.updateDefaults(new AiConfigWriter.DefaultsCommand(null, 0.85d, null, null, null, null));

		ProviderRuntime second = registry.current();
		assertThat(second.options().getTemperature()).as("写路径淘汰了运行态缓存").isEqualTo(0.85d);
		chat(second);
		assertThat(endpoint.lastBody()).as("下一轮真实请求体用新 temperature")
			.contains("\"temperature\":0.85");
	}

	// ── 5. 写路径：成对校验与 provider 级 max_output 上限 ─────────────────────

	@Test
	void maxTokensCeilingIsCheckedAgainstTheBoundProvidersModelOnly() {
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", "http://a");
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", "http://b");
		this.fixture.addModel(providerA.getId(), "shared-model", true).setMaxOutput(2048);
		this.fixture.addModel(providerB.getId(), "shared-model", true).setMaxOutput(8192);

		AiConfigService service = this.fixture.service();
		AiConfigWriter writer = writer(service, null);

		// 同名模型在 B 的 max_output = 8192 ⇒ 4096 合法（旧实现取全库第一个 = A 的 2048 ⇒ 误拒）
		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", null, null, 4096, null,
				providerB.getId()));
		assertThat(this.fixture.settings).containsEntry(AiSetting.DEFAULT_MAX_TOKENS, "4096")
			.containsEntry(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(providerB.getId()));

		// 绑定的 provider 名下没有这个模型 ⇒ MODEL_NOT_FOUND（不跨 provider 借名）
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", null,
				null, null, null, 999L)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_PROVIDER_NOT_FOUND);
	}

	@Test
	void legacyWriteWithoutProviderIdBindsOnlyAUniqueOwnerAndRejectsAmbiguity() {
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", "http://a");
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", "http://b");
		this.fixture.addModel(providerA.getId(), "shared-model", true);
		this.fixture.addModel(providerB.getId(), "shared-model", true);
		this.fixture.addModel(providerB.getId(), "b-only", true);

		AiConfigService service = this.fixture.service();
		AiConfigWriter writer = writer(service, null);

		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("b-only", null, null, null, null));
		assertThat(this.fixture.settings).containsEntry(AiSetting.DEFAULT_PROVIDER_ID,
				String.valueOf(providerB.getId()));

		assertThatThrownBy(
				() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", null, null, null,
						null)))
			.isInstanceOf(AiConfigException.class)
			.hasMessageContaining("providerId")
			.hasMessageContaining("设置")
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);

		// 旧配置本身就是"同名未绑定"：只改温度时原样保留，不阻断保存、也不替用户选边
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "shared-model");
		this.fixture.settings.remove(AiSetting.DEFAULT_PROVIDER_ID);
		this.fixture.bumpVersion();
		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", 0.55d, null, null, null));
		assertThat(this.fixture.settings).containsEntry(AiSetting.DEFAULT_MODEL_ID, "shared-model")
			.containsEntry(AiSetting.DEFAULT_TEMPERATURE, "0.55")
			.doesNotContainKey(AiSetting.DEFAULT_PROVIDER_ID);
	}

	@Test
	void legacyTemperatureOnlySaveNeverBindsToDisabledProviderThatOwnsTheModel() {
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", "http://a");
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", "http://b");
		// a-model 只属于 A；B 有自己的模型（读侧未绑定回退的目标）
		this.fixture.addModel(providerA.getId(), "a-model", true);
		this.fixture.addModel(providerB.getId(), "b-model", true);
		// 旧配置：只有 modelId，没有 providerId
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "a-model");
		this.fixture.settings.remove(AiSetting.DEFAULT_PROVIDER_ID);

		AiConfigService service = this.fixture.service();
		assertThat(service.availability().available()).as("停用 A 之前：AI 可用").isTrue();

		providerA.setEnabled(false);
		this.fixture.bumpVersion();
		assertThat(service.availability().available())
			.as("A 停用后未绑定 legacy 配置按读侧既有回退仍可用")
			.isTrue();

		AiConfigWriter writer = writer(service, null);
		// 旧客户端只改温度：不带 providerId
		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("a-model", 0.55d, null, null, null));

		assertThat(this.fixture.settings)
			.as("自动归属不得把默认目标硬绑到已停用的供应商")
			.doesNotContainKey(AiSetting.DEFAULT_PROVIDER_ID);
		assertThat(this.fixture.settings).containsEntry(AiSetting.DEFAULT_MODEL_ID, "a-model")
			.containsEntry(AiSetting.DEFAULT_TEMPERATURE, "0.55");

		assertThat(service.resolveDefault()).get()
			.as("未绑定 legacy：按现有读侧回退用 B 自己的模型，不改写默认模型名")
			.extracting(AiConfigService.ResolvedProvider::providerId,
					AiConfigService.ResolvedProvider::modelId)
			.containsExactly(providerB.getId(), "b-model");
		assertThat(service.availability().available())
			.as("只改温度不得把 AI 打成不可用")
			.isTrue();

		// 非"原样回写旧配置"的提交不能被静默放行成"回退到别家模型"：按现有契约拒绝
		this.fixture.settings.remove(AiSetting.DEFAULT_MODEL_ID);
		this.fixture.bumpVersion();
		assertThatThrownBy(
				() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("a-model", null, null, null, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_MODEL_NOT_FOUND);
		assertThat(this.fixture.settings).doesNotContainKey(AiSetting.DEFAULT_PROVIDER_ID);
	}

	@Test
	void savingDisabledProviderOrDisabledModelAsDefaultIsFailClosed() {
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", "http://a");
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", "http://b");
		this.fixture.addModel(providerB.getId(), "b-model", true);
		AiProviderModel model = this.fixture.addModel(providerA.getId(), "a-model", true);
		AiConfigWriter writer = writer(this.fixture.service(), null);

		providerA.setEnabled(false);
		this.fixture.bumpVersion();
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("a-model", null, null,
				null, null, providerA.getId())))
			.isInstanceOf(AiConfigException.class)
			.hasMessageContaining("已停用")
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
		assertThat(this.fixture.settings).doesNotContainKey(AiSetting.DEFAULT_PROVIDER_ID);

		providerA.setEnabled(true);
		model.setEnabled(false);
		this.fixture.bumpVersion();
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("a-model", null, null,
				null, null, providerA.getId())))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_MODEL_NOT_FOUND);
		assertThat(this.fixture.settings).doesNotContainKey(AiSetting.DEFAULT_PROVIDER_ID);
	}

	@Test
	void legacyAmbiguousSameNameUsesTheMostConservativeMaxTokensCeiling() {
		AiProvider providerA = this.fixture.addEnvProvider("provider-a", "http://a");
		AiProvider providerB = this.fixture.addEnvProvider("provider-b", "http://b");
		this.fixture.addModel(providerA.getId(), "shared-model", true).setMaxOutput(2048);
		this.fixture.addModel(providerB.getId(), "shared-model", true).setMaxOutput(8192);
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, "shared-model"); // 旧配置：同名、未绑定

		AiConfigWriter writer = writer(this.fixture.service(), null);

		// 未绑定时按所有者的最小值（2048）校验，不能因为"没绑定"放宽到 8192
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", null,
				null, 4096, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);

		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("shared-model", null, null, 1024, null));
		assertThat(this.fixture.settings).containsEntry(AiSetting.DEFAULT_MAX_TOKENS, "1024")
			.doesNotContainKey(AiSetting.DEFAULT_PROVIDER_ID);
	}

	// ── 桩与装配 ────────────────────────────────────────────────────────────

	private AiConfigWriter writer(AiConfigService service, AiProviderRegistry registry) {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		this.fixture.properties.getConfig().setAllowPrivateEndpoints(true);
		return new AiConfigWriter(this.fixture.providerRepository, this.fixture.modelRepository,
				this.fixture.settingRepository, this.fixture.auditRepository,
				new AiKeyCipher(AiConfigTestFixture.KEK_A), new AiEndpointGuard(this.fixture.properties),
				(StringRedisTemplate) null, transactionManager, service, new com.fasterxml.jackson.databind.ObjectMapper(),
				registry);
	}

	private String chat(ProviderRuntime runtime) {
		List<ChatResponse> responses = runtime.chatModel()
			.stream(new Prompt(List.of(new UserMessage("你好"))))
			.collectList()
			.block(Duration.ofSeconds(15));
		return responses == null ? ""
				: responses.stream()
					.map(response -> String.valueOf(response.getResult().getOutput().getText()))
					.reduce("", String::concat);
	}

	private StubEndpoint stub() throws IOException {
		StubEndpoint endpoint = new StubEndpoint();
		this.stubs.add(endpoint);
		return endpoint;
	}

	/** 最小 OpenAI 兼容流式端点：记命中次数与请求体。 */
	private static final class StubEndpoint {

		private final HttpServer server;

		private final List<String> bodies = new CopyOnWriteArrayList<>();

		StubEndpoint() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", this::handle);
			this.server.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		int hits() {
			return this.bodies.size();
		}

		String lastBody() {
			return this.bodies.isEmpty() ? "" : this.bodies.get(this.bodies.size() - 1);
		}

		void stop() {
			this.server.stop(0);
		}

		private void handle(HttpExchange exchange) throws IOException {
			String body;
			try (InputStream input = exchange.getRequestBody()) {
				body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}
			this.bodies.add(body);
			String chunk = "data: {\"id\":\"stub-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
					+ "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
					+ "\"content\":\"桩回复\"},\"finish_reason\":null}]}\n\n" + "data: {\"id\":\"stub-1\","
					+ "\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub-model\",\"choices\":"
					+ "[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n";
			byte[] payload = chunk.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
		}
	}

}
