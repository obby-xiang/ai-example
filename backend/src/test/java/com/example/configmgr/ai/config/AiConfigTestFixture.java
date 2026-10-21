package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiConfigAudit;
import com.example.configmgr.ai.config.db.AiConfigAuditRepository;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.config.db.AiSettingRepository;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型配置批① 的读路径测试夹具：两个"内存仓储"（Mockito 桩）+ 一个真实的
 * {@link AiConfigService} / {@link AiProviderRegistry}。
 *
 * <h2>为什么不用 {@code @SpringBootTest}</h2>
 * 读路径的语义几乎全在"快照 + 版本号 + key 解析"上，用真 Spring 只会把断言拖进启动开销。
 * 这里让仓储变成可控的内存表（顺序/唯一键语义与真实派生查询同形），服务与注册表跑<b>真实代码</b>。
 * 种子与"空表 + 无 env ⇒ 503"的整链路面另有两个 {@code @SpringBootTest}（{@code AiConfigSeederTest}
 * 与 {@code AiConfigUnavailableSpringTest}）。
 */
final class AiConfigTestFixture {

	/** 测试 KEK A（base64 的 32 字节 0x2a）。 */
	static final String KEK_A = kek(0x2a);

	/** 测试 KEK B（"换过 KEK"的场景；解不开 A 的密文）。 */
	static final String KEK_B = kek(0x2b);

	static String kek(int fill) {
		byte[] bytes = new byte[32];
		java.util.Arrays.fill(bytes, (byte) fill);
		return Base64.getEncoder().encodeToString(bytes);
	}

	final List<AiProvider> providers = new CopyOnWriteArrayList<>();

	final List<AiProviderModel> models = new CopyOnWriteArrayList<>();

	final Map<String, String> settings = new ConcurrentHashMap<>();

	final OpenAiConnectionProperties connectionProperties = new OpenAiConnectionProperties();

	final OpenAiChatProperties chatProperties = new OpenAiChatProperties();

	final AiProviderRepository providerRepository = mock(AiProviderRepository.class);

	final AiProviderModelRepository modelRepository = mock(AiProviderModelRepository.class);

	final AiSettingRepository settingRepository = mock(AiSettingRepository.class);

	/** 审计表（批② 写路径的伴生面）：内存收集，便于断言"key 只写 last4"。 */
	final AiConfigAuditRepository auditRepository = mock(AiConfigAuditRepository.class);

	final List<AiConfigAudit> audits = new CopyOnWriteArrayList<>();

	final AiProperties properties = new AiProperties();

	/** &gt;0 时接下来的 N 次 provider 查询抛错（模拟读库抖动；M2 的重试用例）。 */
	final java.util.concurrent.atomic.AtomicInteger pendingProviderLoadFailures = new java.util.concurrent.atomic.AtomicInteger();

	private final AtomicLong ids = new AtomicLong(1);

	AiConfigTestFixture() {
		// 与 application.yml 的现态逐值对齐（"空表 + env" 等价性的比对基准）
		this.connectionProperties.setBaseUrl("http://env-endpoint:1234");
		this.connectionProperties.setApiKey("env-probe-1111");
		this.chatProperties.setCompletionsPath("/chat/completions");
		this.chatProperties.getOptions().setModel("deepseek-flash");
		this.chatProperties.getOptions().setTemperature(0.2d);
		this.chatProperties.getOptions().setMaxTokens(4096);
		this.chatProperties.getOptions().setStreamUsage(Boolean.TRUE);
		// 版本比对每次都做（确定性，不靠 sleep）
		this.properties.getConfig().setReloadInterval(Duration.ZERO);
		this.settings.put(AiSetting.CONFIG_VERSION, "1");

		when(this.providerRepository.findAllByOrderBySortOrderAscIdAsc()).thenAnswer(invocation -> {
			if (this.pendingProviderLoadFailures.get() > 0) {
				this.pendingProviderLoadFailures.decrementAndGet();
				throw new IllegalStateException("DB 不可用（测试注入的读库失败）");
			}
			return copyProviders(sortedProviders(this.providers, false));
		});
		when(this.providerRepository.findByEnabledTrueOrderBySortOrderAscIdAsc())
			.thenAnswer(invocation -> copyProviders(sortedProviders(this.providers, true)));
		when(this.providerRepository.count()).thenAnswer(invocation -> (long) this.providers.size());
		when(this.providerRepository.findById(any())).thenAnswer(invocation -> this.providers.stream()
			.filter(provider -> provider.getId().equals(invocation.getArgument(0)))
			.findFirst()
			.map(AiConfigTestFixture::copyProvider));
		when(this.providerRepository.findByCode(anyString())).thenAnswer(invocation -> this.providers.stream()
			.filter(provider -> provider.getCode().equals(invocation.getArgument(0)))
			.findFirst()
			.map(AiConfigTestFixture::copyProvider));
		when(this.providerRepository.saveAndFlush(any(AiProvider.class))).thenAnswer(invocation -> {
			AiProvider provider = invocation.getArgument(0);
			if (provider.getId() == null) {
				provider.setId(this.ids.getAndIncrement());
			}
			// 与 JPA 同语义：同 id 覆盖（更新），同 code 不同 id 视为唯一约束冲突的替代
			//（旧实现只删"同 code 不同 id"，于是 update 之后列表里会出现同一 id 的两份 ——
			//  findByCode/findById 拿到的是旧的 ENV_SEED 那份，I-1 的回归用例正是靠它暴露的）
			this.providers.removeIf(existing -> existing.getId().equals(provider.getId())
					|| existing.getCode().equals(provider.getCode()));
			this.providers.add(provider);
			return provider;
		});

		when(this.modelRepository.findByProviderIdInOrderBySortOrderAscIdAsc(any()))
			.thenAnswer(invocation -> {
				Collection<Long> ids = invocation.getArgument(0);
				return copyModels(sortedModels(this.models.stream()
					.filter(model -> ids.contains(model.getProviderId()))
					.toList()));
			});
		when(this.modelRepository.findByProviderIdOrderBySortOrderAscIdAsc(any()))
			.thenAnswer(invocation -> copyModels(sortedModels(this.models.stream()
				.filter(model -> model.getProviderId().equals(invocation.getArgument(0)))
				.toList())));
		when(this.modelRepository.findByProviderIdAndEnabledTrueOrderBySortOrderAscIdAsc(any()))
			.thenAnswer(invocation -> sortedModels(this.models.stream()
				.filter(model -> model.getProviderId().equals(invocation.getArgument(0)))
				.filter(AiProviderModel::isEnabled)
				.toList()));
		when(this.modelRepository.findByProviderId(any())).thenAnswer(invocation -> this.models.stream()
			.filter(model -> model.getProviderId().equals(invocation.getArgument(0)))
			.toList());
		when(this.modelRepository.findByProviderIdAndModelId(any(), anyString()))
			.thenAnswer(invocation -> this.models.stream()
				.filter(model -> model.getProviderId().equals(invocation.getArgument(0)))
				.filter(model -> model.getModelId().equals(invocation.getArgument(1)))
				.findFirst()
				.map(AiConfigTestFixture::copyModel));
		when(this.modelRepository.saveAndFlush(any(AiProviderModel.class))).thenAnswer(invocation -> {
			AiProviderModel model = invocation.getArgument(0);
			if (model.getId() == null) {
				model.setId(this.ids.getAndIncrement());
			}
			this.models.removeIf(existing -> existing.getId().equals(model.getId()));
			this.models.add(model);
			return model;
		});

		when(this.settingRepository.findAll()).thenAnswer(invocation -> this.settings.entrySet()
			.stream()
			.map(entry -> {
				AiSetting setting = new AiSetting();
				setting.setSettingKey(entry.getKey());
				setting.setValueJson(entry.getValue());
				return setting;
			})
			.toList());
		when(this.settingRepository.findBySettingKey(anyString())).thenAnswer(invocation -> Optional
			.ofNullable(this.settings.get(invocation.getArgument(0)))
			.map(value -> {
				AiSetting setting = new AiSetting();
				setting.setSettingKey(invocation.getArgument(0));
				setting.setValueJson(value);
				return setting;
			}));
		when(this.settingRepository.save(any(AiSetting.class))).thenAnswer(invocation -> {
			AiSetting setting = invocation.getArgument(0);
			this.settings.put(setting.getSettingKey(), setting.getValueJson());
			return setting;
		});

		// 批②：写路径的删除面（软删走 save、硬删走 delete）与审计表
		// void 方法不能用 when(...)：走 doAnswer.doThrow 形态（Mockito 的显式约定）
		doAnswer(invocation -> {
			AiProviderModel removed = invocation.getArgument(0);
			this.models.removeIf(existing -> existing.getId().equals(removed.getId()));
			return null;
		}).when(this.modelRepository).delete(any(AiProviderModel.class));
		doAnswer(invocation -> {
			Long removedId = invocation.getArgument(0);
			this.providers.removeIf(existing -> existing.getId().equals(removedId));
			return null;
		}).when(this.providerRepository).deleteById(any());
		when(this.auditRepository.save(any(AiConfigAudit.class))).thenAnswer(invocation -> {
			AiConfigAudit audit = invocation.getArgument(0);
			audit.setId((long) (this.audits.size() + 1));
			this.audits.add(audit);
			return audit;
		});
		when(this.auditRepository.findAllByOrderByIdDesc())
			.thenAnswer(invocation -> List.copyOf(this.audits.reversed()));
	}

	// ── 数据 ────────────────────────────────────────────────────────────────

	AiProvider addEnvProvider(String code, String baseUrl) {
		AiProvider provider = baseProvider(code, baseUrl);
		provider.setKeyRef(AiProvider.KEY_REF_ENV);
		provider.setKeyLast4("e11n");
		provider.setSource(AiProvider.SOURCE_ENV_SEED);
		return add(provider);
	}

	AiProvider addDbProvider(String code, String baseUrl, String keyCipher) {
		AiProvider provider = baseProvider(code, baseUrl);
		provider.setKeyRef(AiProvider.KEY_REF_DB);
		provider.setKeyCipher(keyCipher);
		provider.setKeyLast4("ab12");
		provider.setSource(AiProvider.SOURCE_DB);
		return add(provider);
	}

	AiProvider addProvider(AiProvider provider) {
		return add(provider);
	}

	private AiProvider add(AiProvider provider) {
		provider.setId(this.ids.getAndIncrement());
		this.providers.add(provider);
		return provider;
	}

	AiProviderModel addModel(Long providerId, String modelId, boolean isDefault) {
		return addModel(providerId, modelId, isDefault, true);
	}

	AiProviderModel addModel(Long providerId, String modelId, boolean isDefault, boolean enabled) {
		AiProviderModel model = new AiProviderModel();
		model.setId(this.ids.getAndIncrement());
		model.setProviderId(providerId);
		model.setModelId(modelId);
		model.setDisplayName(modelId);
		model.setDefault(isDefault);
		model.setEnabled(enabled);
		model.setMaxContext(128000);
		this.models.add(model);
		return model;
	}

	/** 清空所有表（"空表"场景）。 */
	void clearAll() {
		this.providers.clear();
		this.models.clear();
	}

	/** env key 置空（"空表 + 无 env" 场景）。 */
	void withoutEnvKey() {
		this.chatProperties.setApiKey(null);
		this.connectionProperties.setApiKey(null);
	}

	/** env key 恢复（"运行中补上 key"场景）。 */
	void withEnvKey() {
		this.connectionProperties.setApiKey("env-probe-1111");
	}

	int version() {
		return Integer.parseInt(this.settings.get(AiSetting.CONFIG_VERSION));
	}

	/** 模拟写路径的 "UPDATE version=version+1 + INCR ai:cfg:ver" 里的前半段。 */
	void bumpVersion() {
		this.settings.put(AiSetting.CONFIG_VERSION, String.valueOf(version() + 1));
	}

	// ── 服务/注册表 ──────────────────────────────────────────────────────────

	AiConfigService service() {
		return service(KEK_A);
	}

	AiConfigService service(String kekBase64) {
		return service(kekBase64, null);
	}

	/** 带 Redis 版本信号的服务（M2 的重试用例走这条：版本比对只看 {@code ai:cfg:ver}）。 */
	AiConfigService serviceWithRedis(org.springframework.data.redis.core.StringRedisTemplate redis) {
		return service(KEK_A, redis);
	}

	private AiConfigService service(String kekBase64,
			org.springframework.data.redis.core.StringRedisTemplate redis) {
		return new AiConfigService(this.providerRepository, this.modelRepository, this.settingRepository,
				mock(org.springframework.core.env.Environment.class), new AiKeyCipher(kekBase64), this.properties,
				this.connectionProperties, this.chatProperties, redis);
	}

	/**
	 * {@code StringRedisTemplate} 的最小桩：{@code opsForValue().get("ai:cfg:ver")} 读一个可变的版本值
	 * （{@code null} = 键不存在 ⇒ 服务退化到 DB 版本比对）。
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static org.springframework.data.redis.core.StringRedisTemplate redisReturning(
			java.util.concurrent.atomic.AtomicReference<String> version) {
		org.springframework.data.redis.core.StringRedisTemplate redis = mock(
				org.springframework.data.redis.core.StringRedisTemplate.class);
		org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(
				org.springframework.data.redis.core.ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(anyString())).thenAnswer(invocation -> version.get());
		return redis;
	}

	AiProviderRegistry registry(AiConfigService service) {
		return new AiProviderRegistry(service, mock(SpToolCallingManager.class),
				mock(MessageChatMemoryAdvisor.class), provider(RetryTemplate::new),
				provider(() -> ObservationRegistry.NOOP), this.chatProperties);
	}

	/** {@code ObjectProvider} 的最小桩：{@code getIfAvailable(Supplier)} 走 supplier。 */
	@SuppressWarnings("unchecked")
	static <T> ObjectProvider<T> provider(Supplier<T> fallback) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable(any(Supplier.class))).thenAnswer(invocation -> {
			Supplier<T> supplier = invocation.getArgument(0);
			return supplier.get();
		});
		when(provider.getIfAvailable()).thenAnswer(invocation -> fallback.get());
		return provider;
	}

	private AiProvider baseProvider(String code, String baseUrl) {
		AiProvider provider = new AiProvider();
		provider.setCode(code);
		provider.setDisplayName(code);
		provider.setBaseUrl(baseUrl);
		provider.setCompletionsPath("/chat/completions");
		provider.setModelsPath("/models");
		provider.setEnabled(true);
		provider.setProtocol("OPENAI_COMPAT");
		return provider;
	}

	private static List<AiProvider> sortedProviders(List<AiProvider> source, boolean enabledOnly) {
		List<AiProvider> out = new ArrayList<>(source);
		if (enabledOnly) {
			out.removeIf(provider -> !provider.isEnabled());
		}
		out.sort(Comparator.comparingInt(AiProvider::getSortOrder).thenComparing(AiProvider::getId));
		return out;
	}

	/** 与 JPA 同语义：每次查询返回<b>新实例</b>（否则"改了实体但没重载快照"的用例会假绿）。 */
	static AiProvider copyProvider(AiProvider source) {
		AiProvider copy = new AiProvider();
		copy.setId(source.getId());
		copy.setCode(source.getCode());
		copy.setDisplayName(source.getDisplayName());
		copy.setProtocol(source.getProtocol());
		copy.setBaseUrl(source.getBaseUrl());
		copy.setCompletionsPath(source.getCompletionsPath());
		copy.setModelsPath(source.getModelsPath());
		copy.setKeyCipher(source.getKeyCipher());
		copy.setKeyLast4(source.getKeyLast4());
		copy.setKeyFingerprint(source.getKeyFingerprint());
		copy.setKeyRef(source.getKeyRef());
		copy.setSource(source.getSource());
		copy.setEnabled(source.isEnabled());
		copy.setSortOrder(source.getSortOrder());
		copy.setCreatedAt(source.getCreatedAt());
		copy.setUpdatedAt(source.getUpdatedAt());
		return copy;
	}

	static AiProviderModel copyModel(AiProviderModel source) {
		AiProviderModel copy = new AiProviderModel();
		copy.setId(source.getId());
		copy.setProviderId(source.getProviderId());
		copy.setModelId(source.getModelId());
		copy.setDisplayName(source.getDisplayName());
		copy.setMaxContext(source.getMaxContext());
		copy.setMaxOutput(source.getMaxOutput());
		copy.setSupportsToolCalling(source.isSupportsToolCalling());
		copy.setSupportsVision(source.getSupportsVision());
		copy.setSupportsReasoning(source.getSupportsReasoning());
		copy.setEnabled(source.isEnabled());
		copy.setDefault(source.isDefault());
		copy.setSortOrder(source.getSortOrder());
		copy.setCreatedAt(source.getCreatedAt());
		copy.setUpdatedAt(source.getUpdatedAt());
		return copy;
	}

	private static List<AiProvider> copyProviders(List<AiProvider> source) {
		return source.stream().map(AiConfigTestFixture::copyProvider).toList();
	}

	private static List<AiProviderModel> copyModels(List<AiProviderModel> source) {
		return source.stream().map(AiConfigTestFixture::copyModel).toList();
	}

	private static List<AiProviderModel> sortedModels(List<AiProviderModel> source) {
		List<AiProviderModel> out = new ArrayList<>(source);
		out.sort(Comparator.comparingInt(AiProviderModel::getSortOrder).thenComparing(AiProviderModel::getId));
		return out;
	}

}
