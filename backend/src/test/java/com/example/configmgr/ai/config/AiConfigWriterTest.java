package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiConfigAudit;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批② 写路径（设计稿 §3.4 / §3.5）的<b>落库面</b>：加密、版本号 + Redis 失效信号、审计、校验。
 *
 * <p>
 * 用批① 的 {@link AiConfigTestFixture}（内存仓储 + 真实服务/密码器），只有 Redis 与事务管理器是桩：
 * <ul>
 * <li>版本号写路径：DB {@code config.version}+1 后 {@code INCR ai:cfg:ver}（{@link #createProviderBumpsTheVersionAndSignalsRedis()}）；</li>
 * <li>审计：{@code diff_json} 对 key 只写 {@code last4} + 动作（{@link #auditNeverCarriesTheKeyOrTheCipher()}）；</li>
 * <li>KEK 缺失：400 {@code AI_SECRET_KEK_MISSING} 且<b>表里不留半行</b>（B5）；</li>
 * <li>写时拒脏值：baseUrl/私网/参数范围/能力位（与批① M3 的"读时容错"呼应）；</li>
 * <li>读侧节流：本实例写完好立刻可见（写路径主动 reload）；别的实例写完要等节流窗过期。</li>
 * </ul>
 */
class AiConfigWriterTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> redisOps = mock(ValueOperations.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private AiConfigWriter writer(String kek) {
		return writer(kek, false);
	}

	private AiConfigWriter writer(String kek, AiConfigService service) {
		when(this.redis.opsForValue()).thenReturn(this.redisOps);
		when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		this.fixture.properties.getConfig().setAllowPrivateEndpoints(false);
		return new AiConfigWriter(this.fixture.providerRepository, this.fixture.modelRepository,
				this.fixture.settingRepository, this.fixture.auditRepository, new AiKeyCipher(kek),
				new AiEndpointGuard(this.fixture.properties), this.redis, this.transactionManager, service,
				new ObjectMapper(), null);
	}

	private AiConfigWriter writer(String kek, boolean allowPrivate) {
		when(this.redis.opsForValue()).thenReturn(this.redisOps);
		when(this.transactionManager.getTransaction(any()))
			.thenReturn(new SimpleTransactionStatus());
		this.fixture.properties.getConfig().setAllowPrivateEndpoints(allowPrivate);
		return new AiConfigWriter(this.fixture.providerRepository, this.fixture.modelRepository,
				this.fixture.settingRepository, this.fixture.auditRepository, new AiKeyCipher(kek),
				new AiEndpointGuard(this.fixture.properties), this.redis,
				this.transactionManager, this.fixture.service(kek),
				new ObjectMapper(), null);
	}

	private static AiConfigWriter.ProviderCommand provider(String code, String baseUrl, String apiKey) {
		return new AiConfigWriter.ProviderCommand(code, "端点 " + code, null, baseUrl, null, null, apiKey, null,
				null, null);
	}

	// ── 新建：加密 + 版本号 + 审计 ────────────────────────────────────────────

	@Test
	void createProviderEncryptsTheKeyAndBumpsTheVersion() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		String key = "probe-key-1234abcd";

		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", key));

		assertThat(created.getId()).isNotNull();
		assertThat(created.getKeyRef()).isEqualTo(AiProvider.KEY_REF_DB);
		assertThat(created.getKeyCipher()).startsWith("v1.k1.");
		assertThat(created.getKeyLast4()).isEqualTo("abcd");
		assertThat(created.getKeyFingerprint()).hasSize(16);
		assertThat(created.getSource()).isEqualTo(AiProvider.SOURCE_DB);
		assertThat(created.isEnabled()).isTrue();
		assertThat(created.getProtocol()).isEqualTo("OPENAI_COMPAT");
		assertThat(created.getCompletionsPath()).isEqualTo("/chat/completions");
		assertThat(created.getModelsPath()).isEqualTo("/models");
		// 版本号 = DB 真源 +1，并且 INCR 了跨实例失效信号
		assertThat(this.fixture.version()).isEqualTo(2);
		verify(this.redisOps).increment(AiConfigService.REDIS_VERSION_KEY);
		// 单实例语义：写路径主动 reload ⇒ 读侧<b>下一个请求</b>就能拿到新配置
		assertThat(this.fixture.service().current().providers()).extracting(AiProvider::getCode)
			.contains("custom-1");
	}

	@Test
	void auditNeverCarriesTheKeyOrTheCipher() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		String key = "probe-key-1234abcd";
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", key));

		String diff = this.fixture.audits.get(0).getDiffJson();
		assertThat(diff).contains("\"last4\":\"abcd\"").doesNotContain(key).doesNotContain("probe-key")
			.doesNotContain(created.getKeyCipher());
		assertThat(this.fixture.audits.get(0).getAction()).isEqualTo(AiConfigWriter.ACTION_PROVIDER_CREATE);
		assertThat(this.fixture.audits.get(0).getTargetType()).isEqualTo("provider");
	}

	@Test
	void providerWithoutKeyIsCreatedWithKeyRefNone() {
		AiProvider created = writer(AiConfigTestFixture.KEK_A)
			.createProvider(provider("custom-2", "https://api.deepseek.com", null));
		assertThat(created.getKeyRef()).isEqualTo(AiProvider.KEY_REF_NONE);
		assertThat(created.getKeyCipher()).isNull();
		assertThat(created.getKeyLast4()).isNull();
		assertThat(this.fixture.audits.get(0).getDiffJson()).contains("\"key\":\"none\"");
	}

	@Test
	void kekMissingIsRejectedAndNothingIsPersisted() {
		AiConfigWriter writer = writer("");

		assertThatThrownBy(() -> writer.createProvider(provider("custom-1", "https://api.deepseek.com",
				"probe-key-1234abcd")))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiKeyCipher.CODE_KEK_MISSING);

		assertThat(this.fixture.providers).isEmpty();
		assertThat(this.fixture.version()).isEqualTo(1);
		assertThat(this.fixture.audits).isEmpty();
		verify(this.redisOps, never()).increment(any());
	}

	@Test
	void tooShortKeyIsRejectedRatherThanStoredAsItsOwnLast4() {
		// N1（批① 登记）：长度 ≤4 的 key 会把整串当 last4 展示 ⇒ 写路径必须拒
		assertThatThrownBy(() -> writer(AiConfigTestFixture.KEK_A)
			.createProvider(provider("custom-1", "https://api.deepseek.com", "abc")))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
		assertThat(this.fixture.providers).isEmpty();
	}

	@Test
	void duplicateCodeIsRejected() {
		writer(AiConfigTestFixture.KEK_A).createProvider(provider("custom-1", "https://api.deepseek.com", null));
		assertThatThrownBy(
				() -> writer(AiConfigTestFixture.KEK_A).createProvider(provider("custom-1", "https://api.deepseek.com", null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_PROVIDER_EXISTS);
	}

	// ── 写时拒脏值（URL 闸 / 参数范围） ───────────────────────────────────────

	@Test
	void privateBaseUrlIsRejectedOnWriteAndNothingIsPersisted() {
		assertThatThrownBy(() -> writer(AiConfigTestFixture.KEK_A)
			.createProvider(provider("custom-1", "http://127.0.0.1:18399", null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_BLOCKED_PRIVATE_ENDPOINT);
		assertThat(this.fixture.providers).isEmpty();
		assertThat(this.fixture.version()).isEqualTo(1);
	}

	@Test
	void malformedBaseUrlIsRejectedOnWrite() {
		assertThatThrownBy(() -> writer(AiConfigTestFixture.KEK_A)
			.createProvider(provider("custom-1", "api.deepseek.com", null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_INVALID_BASE_URL);
	}

	@Test
	void invalidCodePatternIsRejected() {
		assertThatThrownBy(() -> writer(AiConfigTestFixture.KEK_A)
			.createProvider(provider("bad code!", "https://api.deepseek.com", null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
	}

	@Test
	void codeCannotBeChangedInPlaceBecauseTheCipherIsBoundToIt() {
		AiProvider created = writer(AiConfigTestFixture.KEK_A)
			.createProvider(provider("custom-1", "https://api.deepseek.com", "probe-key-1234abcd"));
		assertThatThrownBy(() -> writer(AiConfigTestFixture.KEK_A).updateProvider(created.getId(),
				provider("custom-2", "https://api.deepseek.com", null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_CODE_IMMUTABLE);
	}

	// ── 更新：key 语义三态 ────────────────────────────────────────────────────

	@Test
	void updateWithoutApiKeyKeepsTheCipher() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com",
				"probe-key-1234abcd"));
		String cipher = created.getKeyCipher();

		AiProvider updated = writer.updateProvider(created.getId(),
				new AiConfigWriter.ProviderCommand(null, "新名字", null, "https://gw.example.com", null, null, null,
						null, Boolean.FALSE, null));

		assertThat(updated.getDisplayName()).isEqualTo("新名字");
		assertThat(updated.getBaseUrl()).isEqualTo("https://gw.example.com");
		assertThat(updated.getKeyCipher()).isEqualTo(cipher);
		assertThat(updated.getKeyRef()).isEqualTo(AiProvider.KEY_REF_DB);
		assertThat(updated.isEnabled()).isFalse();
		assertThat(this.fixture.audits.get(1).getDiffJson()).doesNotContain("key").doesNotContain(cipher);
	}

	@Test
	void blankApiKeyOnUpdateMeansUnchanged() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com",
				"probe-key-1234abcd"));
		AiProvider updated = writer.updateProvider(created.getId(),
				new AiConfigWriter.ProviderCommand(null, null, null, null, null, null, "   ", null, null, null));
		assertThat(updated.getKeyCipher()).isEqualTo(created.getKeyCipher());
	}

	@Test
	void explicitClearKeyWipesTheCipher() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com",
				"probe-key-1234abcd"));

		AiProvider updated = writer.updateProvider(created.getId(),
				new AiConfigWriter.ProviderCommand(null, null, null, null, null, null, null, Boolean.TRUE, null, null));

		assertThat(updated.getKeyCipher()).isNull();
		assertThat(updated.getKeyLast4()).isNull();
		assertThat(updated.getKeyFingerprint()).isNull();
		assertThat(updated.getKeyRef()).isEqualTo(AiProvider.KEY_REF_NONE);
		assertThat(this.fixture.audits.get(1).getDiffJson()).contains("\"key\":\"cleared\"");
	}

	@Test
	void updatingAMissingProviderIsA404Code() {
		assertThatThrownBy(() -> writer(AiConfigTestFixture.KEK_A)
			.updateProvider(999L, provider("x", "https://api.deepseek.com", null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_PROVIDER_NOT_FOUND);
	}

	// ── 删除：软删默认 / 硬删带引用闸 ────────────────────────────────────────

	@Test
	void deleteIsSoftByDefaultAndKeepsTheRow() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));

		AiProvider deleted = writer.deleteProvider(created.getId(), false);

		assertThat(deleted.isEnabled()).isFalse();
		assertThat(this.fixture.providers).extracting(AiProvider::getId).contains(created.getId());
		// 新建 +1（1→2）、软删再 +1（2→3）：每一次写都要让读侧看见
		assertThat(this.fixture.version()).isEqualTo(3);
	}

	@Test
	void hardDeleteIsRefusedWhileModelsReferenceTheProvider() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, null, null, null, null, null,
				null, null, null));

		assertThatThrownBy(() -> writer.deleteProvider(created.getId(), true))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_PROVIDER_IN_USE);
		assertThat(this.fixture.providers).hasSize(1);
	}

	@Test
	void hardDeleteRemovesTheRowWhenNothingReferencesIt() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));

		writer.deleteProvider(created.getId(), true);

		assertThat(this.fixture.providers).isEmpty();
		assertThat(this.fixture.audits.get(1).getAction()).isEqualTo(AiConfigWriter.ACTION_PROVIDER_DELETE);
	}

	// ── 模型 allowlist ──────────────────────────────────────────────────────

	@Test
	void modelRangesAreValidatedOnWrite() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		Long id = created.getId();

		// max_output > max_context
		assertThatThrownBy(() -> writer.addModel(id, new AiConfigWriter.ModelCommand("m-1", null, 1000, 2000, null,
				null, null, null, null, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
		// 非正窗口
		assertThatThrownBy(() -> writer.addModel(id,
				new AiConfigWriter.ModelCommand("m-1", null, 0, null, null, null, null, null, null, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
		// 空白 model_id
		assertThatThrownBy(() -> writer.addModel(id,
				new AiConfigWriter.ModelCommand("m 1", null, null, null, null, null, null, null, null, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_MODEL);
		assertThat(this.fixture.models).isEmpty();
	}

	@Test
	void duplicateModelIsRejected() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, null, null, null, null, null,
				null, null, null));
		assertThatThrownBy(() -> writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, null,
				null, null, null, null, null, null, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_MODEL_EXISTS);
	}

	@Test
	void modelUpdateTogglesCapabilityBitsAndDeleteRemovesTheRow() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, 8000, 2048, null, null, null,
				null, null, null));

		AiProviderModel updated = writer.updateModel(created.getId(), "m-1",
				new AiConfigWriter.ModelCommand(null, "显示名", null, null, Boolean.FALSE, Boolean.TRUE, null, null,
						null, null));
		assertThat(updated.isSupportsToolCalling()).isFalse();
		assertThat(updated.getSupportsVision()).isTrue();
		assertThat(updated.getDisplayName()).isEqualTo("显示名");
		assertThat(updated.getMaxContext()).isEqualTo(8000);

		writer.deleteModel(created.getId(), "m-1");
		assertThat(this.fixture.models).isEmpty();
		assertThat(this.fixture.audits).extracting(AiConfigAudit::getAction)
			.contains(AiConfigWriter.ACTION_MODEL_ADD, AiConfigWriter.ACTION_MODEL_UPDATE,
					AiConfigWriter.ACTION_MODEL_DELETE);
	}

	// ── 全局默认参数 ─────────────────────────────────────────────────────────

	@Test
	void defaultsAreRangeCheckedOnWriteAndStoredAsSettings() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, 8000, 2048, null, null, null,
				null, null, null));

		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand(null, 2.5d, null, null,
				null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand(null, null, 1.2d, null,
				null)))
			.isInstanceOf(AiConfigException.class);
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand(null, null, null, 0, null)))
			.isInstanceOf(AiConfigException.class);
		assertThatThrownBy(() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("nope", null, null, null,
				null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_MODEL_NOT_FOUND);

		long before = this.fixture.version();
		writer.updateDefaults(new AiConfigWriter.DefaultsCommand("m-1", 0.3d, 0.9d, 1024, Boolean.FALSE));
		assertThat(this.fixture.settings).containsEntry(AiSetting.DEFAULT_MODEL_ID, "m-1")
			.containsEntry(AiSetting.DEFAULT_TEMPERATURE, "0.3")
			.containsEntry(AiSetting.DEFAULT_TOP_P, "0.9")
			.containsEntry(AiSetting.DEFAULT_MAX_TOKENS, "1024")
			.containsEntry(AiSetting.ALLOW_USER_PARAMS, "false");
		assertThat(this.fixture.version()).isEqualTo(before + 1);
	}

	@Test
	void maxTokensAboveTheModelCeilingIsRejected() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, 8000, 2048, null, null, null,
				null, null, null));

		assertThatThrownBy(
				() -> writer.updateDefaults(new AiConfigWriter.DefaultsCommand("m-1", null, null, 4096, null)))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
	}

	// ── 读侧节流与"写后立即可见" ──────────────────────────────────────────────

	@Test
	void theWritingInstanceSeesItsOwnChangeImmediatelyDespiteTheThrottle() {
		this.fixture.properties.getConfig().setReloadInterval(Duration.ofSeconds(1));
		AiConfigService service = this.fixture.service();
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A, service);
		service.current();
		long before = service.current().version();

		writer.updateDefaults(new AiConfigWriter.DefaultsCommand(null, 0.7d, null, null, null));

		assertThat(service.current().version()).isEqualTo(before + 1);
		assertThat(service.current().settings()).containsEntry(AiSetting.DEFAULT_TEMPERATURE, "0.7");
	}

	@Test
	void anotherInstancesChangeIsPickedUpOnlyAfterTheThrottleWindow() throws Exception {
		this.fixture.properties.getConfig().setReloadInterval(Duration.ofMillis(120));
		java.util.concurrent.atomic.AtomicReference<String> redisVersion = new java.util.concurrent.atomic.AtomicReference<>("1");
		AiConfigService service = this.fixture.serviceWithRedis(AiConfigTestFixture.redisReturning(redisVersion));
		assertThat(service.current().version()).isEqualTo(1);

		// 模拟"另一个实例"写完：DB 版本 +1 + INCR ai:cfg:ver
		this.fixture.bumpVersion();
		redisVersion.set("2");

		assertThat(service.current().version()).as("节流窗内不重载").isEqualTo(1);
		Thread.sleep(160L);
		assertThat(service.current().version()).as("窗过期后重载").isEqualTo(2);
	}

	@Test
	void redisFailureDoesNotBreakTheWriteAndTheDbVersionStillMoves() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		when(this.redis.opsForValue()).thenThrow(new IllegalStateException("Redis 不可用"));

		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));

		assertThat(created.getId()).isNotNull();
		assertThat(this.fixture.version()).isEqualTo(2);
	}

	// ── 审计读取面 ──────────────────────────────────────────────────────────

	@Test
	void auditsAreRecordedForEveryMutation() {
		AiConfigWriter writer = writer(AiConfigTestFixture.KEK_A);
		AiProvider created = writer.createProvider(provider("custom-1", "https://api.deepseek.com", null));
		writer.addModel(created.getId(), new AiConfigWriter.ModelCommand("m-1", null, null, null, null, null, null,
				null, null, null));
		writer.updateDefaults(new AiConfigWriter.DefaultsCommand(null, 0.1d, null, null, null));
		writer.deleteProvider(created.getId(), false);

		assertThat(this.fixture.audits).extracting(AiConfigAudit::getAction).containsExactly(
				AiConfigWriter.ACTION_PROVIDER_CREATE, AiConfigWriter.ACTION_MODEL_ADD,
				AiConfigWriter.ACTION_DEFAULTS_UPDATE, AiConfigWriter.ACTION_PROVIDER_DELETE);
		assertThat(this.fixture.audits).extracting(AiConfigAudit::getTargetType)
			.containsExactly("provider", "model", "setting", "provider");
		// M-3（复审）：每一次写入都必须 INCR 一次跨实例失效信号 —— 次数与变更次数逐一对齐
		//（少 INCR = 别的实例要等 DB 版本比对才看得见；多 INCR = 无意义的重载信号）
		verify(this.redisOps, org.mockito.Mockito.times(4)).increment(AiConfigService.REDIS_VERSION_KEY);
	}

	// ── I-1（复审必修）：用户一改即脱离种子 ─────────────────────────────────

	/**
	 * I-1：env 种子行（{@code source=ENV_SEED}）被用户在设置页改过一次后，必须变成 {@code source=DB} ——
	 * 否则 {@code app.ai.config.seed-force=true} 重启时 {@code AiConfigSeeder#writeSeed} 会按
	 * "来源=ENV_SEED" 把用户改过的 base-url <b>覆盖回去</b>。
	 *
	 * <p>
	 * 连锁面一并锁住：seed-force 的 {@code setKeyLast4} 也只在"仍是 ENV_SEED"的分支里执行 ——
	 * source 翻转后，连 env key 换了新值都不会再改写 {@code key_last4}（测试用两个不同 env key 证明）。
	 *
	 * <p>
	 * <b>证红</b>：去掉 {@code updateProvider} 里的 source 翻转 ⇒ baseUrl 被 env 覆盖回
	 * {@code http://env-endpoint:1234}、keyLast4 被改成新 env key 的末 4 位 ⇒ 两条断言同时红。
	 */
	@Test
	void userUpdateDetachesTheSeedRowSoSeedForceCannotOverwriteIt() {
		this.fixture.chatProperties.setApiKey("env-probe-1111");
		this.fixture.properties.getConfig().setSeedForce(false);
		AiConfigSeeder seeder = seeder(this.fixture.service(AiConfigTestFixture.KEK_A));
		assertThat(seeder.seed()).as("空表 + env ⇒ 写入种子").isTrue();
		AiProvider seeded = this.fixture.providers.get(0);
		assertThat(seeded.getSource()).isEqualTo(AiProvider.SOURCE_ENV_SEED);
		assertThat(seeded.getKeyRef()).isEqualTo(AiProvider.KEY_REF_ENV);
		assertThat(seeded.getKeyLast4()).isEqualTo("1111");
		assertThat(seeded.getBaseUrl()).isEqualTo("http://env-endpoint:1234");

		AiProvider updated = writer(AiConfigTestFixture.KEK_A)
			.updateProvider(seeded.getId(),
					new AiConfigWriter.ProviderCommand(null, null, null, "https://user-edited.example.com", null,
							null, null, null, null, null));
		assertThat(updated.getSource()).as("用户一改即脱离种子").isEqualTo(AiProvider.SOURCE_DB);

		// seed-force 重启：env 已换成另一把 key + 另一个 base-url
		this.fixture.chatProperties.setApiKey("env-probe-2222");
		this.fixture.connectionProperties.setBaseUrl("http://new-env-host:2222");
		this.fixture.properties.getConfig().setSeedForce(true);
		seeder.seed();

		AiProvider afterForce = this.fixture.providers.get(0);
		assertThat(afterForce.getBaseUrl()).as("seed-force 不覆盖用户改过的行")
			.isEqualTo("https://user-edited.example.com");
		assertThat(afterForce.getKeyLast4()).as("key_last4 的 seed-force 赋值同属 ENV_SEED 分支 ⇒ 一并跳过")
			.isEqualTo("1111");
		assertThat(afterForce.getSource()).isEqualTo(AiProvider.SOURCE_DB);
	}

	private AiConfigSeeder seeder(AiConfigService service) {
		return new AiConfigSeeder(this.fixture.providerRepository, this.fixture.modelRepository, service,
				this.fixture.properties, this.transactionManager);
	}

}
