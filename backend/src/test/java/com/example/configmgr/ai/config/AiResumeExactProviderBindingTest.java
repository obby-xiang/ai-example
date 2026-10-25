package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * <b>I3 端到端</b>：同名模型挂在 A/B 两家时，续跑必须只到<b>快照那一家</b>（本类用<b>真实</b>
 * {@link AiConfigService} 解析，续跑驱动本身是桩）。
 *
 * <h2>为什么这条不能再"按模型名猜第一家"</h2>
 * 旧实现的 {@code PROVIDER_GONE} 判据是"按 {@code modelId} 解析出的 provider 是否等于快照 provider"。
 * 同名模型时它解析到库序第一家（A）≠ 快照（B）⇒ 误判 GONE；反过来，如果哪天真按名字放行，
 * 就会把 B 的一轮发到 A。两种症状同一个根因：<b>解析没有带 providerId</b>。
 *
 * <h2>证红方式</h2>
 * 把 {@code ResumeService} 的判据换回 {@code resolveChatTarget(modelId)} ⇒ 第 1 例变 GONE（红）；
 * 让 {@code resolveChatTarget(Long,String)} 在绑定 provider 缺模型时跨家回落 ⇒ 第 2 例变 ACCEPTED（红）。
 *
 * <p>
 * <b>复核 Critical-1（B2 三态）</b>：本类另有四个"确定 vs 不可知"的用例 —— 陈旧但库里有行 ⇒ ACCEPTED；
 * 刷新后仍没有行 ⇒ {@code PROVIDER_GONE} + FAILED；<b>读库失败</b> ⇒ {@code STATE_UNKNOWN}（保持
 * SUSPENDED）；<b>行在但 key 解不开</b>（KEK 轮换）⇒ 同样 {@code STATE_UNKNOWN}。把它们退回
 * "刷新失败也当空解析"的第一版实现 ⇒ 后两条变 PROVIDER_GONE/FAILED（红）。
 */
class AiResumeExactProviderBindingTest {

	private static final String RUN_ID = "run-exact-binding";

	private static final String SESSION_ID = "s-exact-binding";

	private static final String SHARED_MODEL = "shared-model";

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final AiProperties properties = new AiProperties();

	private AiProvider providerA;

	private AiProvider providerB;

	private AiConfigService configService;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		this.providerA = this.fixture.addEnvProvider("provider-a", "http://a-endpoint");
		this.providerB = this.fixture.addEnvProvider("provider-b", "http://b-endpoint");
		this.fixture.addModel(this.providerA.getId(), SHARED_MODEL, true);
		this.fixture.addModel(this.providerB.getId(), SHARED_MODEL, true);
		// 全局默认指向 A：于是"按模型名找第一家"会解析到 A —— 正是旧实现的误判形态
		this.fixture.settings.put(AiSetting.DEFAULT_PROVIDER_ID, String.valueOf(this.providerA.getId()));
		this.fixture.settings.put(AiSetting.DEFAULT_MODEL_ID, SHARED_MODEL);
		this.configService = this.fixture.service();
		this.service = resumeService(this.configService);
	}

	@Test
	void resumeBindsToTheSnapshotProviderEvenWhenAnotherProviderOwnsTheSameModelName() {
		seedSuspendedSnapshot(this.providerB.getId());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("同名模型不是跨家理由：按快照 providerId 精确解析")
			.isEqualTo(ResumeService.Outcome.ACCEPTED);
		ArgumentCaptor<Long> providerId = ArgumentCaptor.forClass(Long.class);
		ArgumentCaptor<String> modelId = ArgumentCaptor.forClass(String.class);
		verify(this.chatService).driveResumed(anyString(), anyString(), any(AiContext.class),
				org.mockito.ArgumentMatchers.<List<Message>>any(), providerId.capture(), modelId.capture(), any());
		assertThat(providerId.getValue()).as("只到快照那一家（B），不是库序第一家（A）")
			.isEqualTo(this.providerB.getId());
		assertThat(modelId.getValue()).isEqualTo(SHARED_MODEL);
	}

	@Test
	void providerGoneWhenTheSnapshotProviderLostTheModelEvenIfAnotherProviderHasTheSameName() {
		disableModel(this.providerB.getId(), SHARED_MODEL);
		seedSuspendedSnapshot(this.providerB.getId());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("B 不再允许该模型 ⇒ GONE；不得去 A 借用同名模型")
			.isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void providerGoneWhenTheSnapshotProviderIsDisabled() {
		this.providerB.setEnabled(false);
		this.configService.reload();
		seedSuspendedSnapshot(this.providerB.getId());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
	}

	@Test
	void staleSnapshotDoesNotYieldProviderGoneWhenTheRowIsStillInTheDatabase() {
		// 现场还原（run 40182796512 的 B/C）：本实例的快照是<b>种子之前</b>加载的（库当时为空），
		// 另一个实例随后把 provider 行写进了共享库 —— 但版本信号没动，所以本实例的快照仍是空的。
		AiConfigTestFixture shared = new AiConfigTestFixture();
		AiConfigService staleReader = shared.service();
		assertThat(staleReader.current().providers()).as("冷启动先加载：库当时为空").isEmpty();
		AiProvider seededElsewhere = shared.addEnvProvider("deepseek", "http://env-endpoint");
		shared.addModel(seededElsewhere.getId(), SHARED_MODEL, true);
		assertThat(shared.version()).as("版本没动（根因 A 的历史现场：种子不发布版本）").isEqualTo(1);

		seedSuspendedSnapshot(seededElsewhere.getId(), SHARED_MODEL);
		ResumeService staleService = resumeService(staleReader);

		ResumeService.ResumeResult result = staleService.resume(RUN_ID);

		assertThat(result.outcome())
			.as("库里有这一行、只是快照旧 ⇒ 硬否定之前必须先重载，不得误判 PROVIDER_GONE")
			.isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(staleReader.current().providers()).as("强制重载之后快照必须看到库行").hasSize(1);
	}

	@Test
	void genuinelyDeletedProviderStillYieldsProviderGoneAfterTheForcedReload() {
		// 反例：行真的没了。强制重载只救"缓存旧"，不能把"库里没有"洗成可用。
		this.fixture.providers.removeIf(provider -> this.providerB.getId().equals(provider.getId()));
		this.fixture.models.removeIf(model -> this.providerB.getId().equals(model.getProviderId()));
		this.configService.reload();
		seedSuspendedSnapshot(this.providerB.getId(), SHARED_MODEL);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("库里确实没有这一行 ⇒ 仍是 PROVIDER_GONE")
			.isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		assertThat(this.store.get(RUN_ID).getStatus())
			.as("刷新成功 + 行确实不在 ⇒ 这才是可以判死的'确定'（FAILED 终态）")
			.isEqualTo(RunSnapshot.FAILED);
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any(), any(), any());
	}

	// ── 复核 Critical-1：读库失败 / key 解不开 都不是"端点被删" ──────────────────

	/**
	 * 读库抖动：{@code load()} 吞掉异常（{@code ok=false}），{@code reload()} 既不抛也不报。
	 * 若把这当成"确定不可用"，一次 Hikari 超时就会把这一轮写成永久 FAILED。
	 */
	@Test
	void unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown() {
		AiConfigTestFixture shared = new AiConfigTestFixture();
		AiConfigService reader = shared.service();
		assertThat(reader.current().providers()).as("冷启动先加载：库当时为空").isEmpty();
		AiProvider seededElsewhere = shared.addEnvProvider("deepseek", "http://env-endpoint");
		shared.addModel(seededElsewhere.getId(), SHARED_MODEL, true);
		seedSuspendedSnapshot(seededElsewhere.getId(), SHARED_MODEL);
		// 复核 M-7：<b>持续</b>读库失败（不是一次），否则旧码会在解析内部再读一次并成功、
		// 这条用例就退化成"旧码靠隐式重试偷偷放行"，无法证明"读库失败 ⇒ 永久 FAILED"。
		shared.pendingProviderLoadFailures.set(Integer.MAX_VALUE);
		ResumeService readerService = resumeService(reader);

		ResumeService.ResumeResult result = readerService.resume(RUN_ID);

		assertThat(result.outcome()).as("刷新没成功 ⇒ 不可知（503），不是 PROVIDER_GONE")
			.isEqualTo(ResumeService.Outcome.STATE_UNKNOWN);
		assertThat(result.body()).containsEntry("reason", "STATE_UNKNOWN");
		assertThat(this.store.get(RUN_ID).getStatus()).as("不得判死：快照保持 SUSPENDED，抖动过去就能续")
			.isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.events(RUN_ID)).as("不可知 ⇒ 不得写业务帧").isEmpty();
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any(), any(), any());

		// 持续性：同一抖动窗口里再试一次也不得翻转成终态（旧码在这里是第二次判死机会）
		ResumeService.ResumeResult retry = resumeService(reader).resume(RUN_ID);

		assertThat(retry.outcome()).isEqualTo(ResumeService.Outcome.STATE_UNKNOWN);
		assertThat(this.store.get(RUN_ID).getStatus())
			.as("持续读库失败也不得把这一轮写成永久 FAILED").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.events(RUN_ID)).as("仍然不得写业务帧").isEmpty();
	}

	/**
	 * key 解不开（KEK 轮换/缺失/密文损坏）：行在、模型在，解析仍为空 —— 但这不是"端点被删"。
	 *
	 * <p>用真实 {@link AiConfigService}：同一份库、写侧用 KEK_A 加密，读侧换成 KEK_B。
	 */
	@Test
	void unreadableKeyYieldsStateUnknownNotProviderGone() {
		AiConfigTestFixture shared = new AiConfigTestFixture();
		String cipher = new AiKeyCipher(AiConfigTestFixture.KEK_A).encrypt("sk-rotated-0123456789", "rotated",
				AiProvider.KEY_REF_DB);
		AiProvider rotated = shared.addDbProvider("rotated", "https://rotated-endpoint", cipher);
		shared.addModel(rotated.getId(), SHARED_MODEL, true);
		AiConfigService foreignKek = shared.service(AiConfigTestFixture.KEK_B);
		assertThat(foreignKek.resolveChatTarget(rotated.getId(), SHARED_MODEL))
			.as("前置：KEK 不符 ⇒ 解析为空").isEmpty();
		assertThat(foreignKek.bindingState(rotated.getId(), SHARED_MODEL))
			.as("前置：行与模型都还在，只是 key 解不开（KEY_UNREADABLE）")
			.isEqualTo(AiConfigService.BindingState.KEY_UNREADABLE);
		seedSuspendedSnapshot(rotated.getId(), SHARED_MODEL);
		ResumeService foreignService = resumeService(foreignKek);

		ResumeService.ResumeResult result = foreignService.resume(RUN_ID);

		assertThat(result.outcome()).as("key 不可用是'不可知'，不是'端点被删'")
			.isEqualTo(ResumeService.Outcome.STATE_UNKNOWN);
		assertThat(this.store.get(RUN_ID).getStatus()).as("KEK 修好这一轮就能续 ⇒ 不得判死")
			.isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.events(RUN_ID)).as("不可知 ⇒ 不得写业务帧").isEmpty();
	}

	/**
	 * 复核 I-1（旧/冻结快照）：{@code providerId=null} 的快照也要分得清"从未声明 key"与"key 解不开"。
	 *
	 * <p>行/模型都在、但这台 provider <b>从没声明过 key</b>（{@code key_ref=NONE}）⇒ 与
	 * {@code providerId!=null} 分支同一口径：确定不可用，收敛 {@code PROVIDER_GONE} + FAILED，
	 * 不落进永不收敛的 {@code STATE_UNKNOWN}。
	 */
	@Test
	void legacyFrozenSnapshotWithUndeclaredKeyIsKnownAbsentNotUnknown() {
		AiConfigTestFixture shared = new AiConfigTestFixture();
		AiProvider stored = shared.addProvider(providerWithoutKey("legacy-no-key", "https://legacy-no-key.example"));
		shared.addModel(stored.getId(), SHARED_MODEL, true);
		AiConfigService reader = shared.service();
		assertThat(reader.bindingState(null, SHARED_MODEL))
			.as("前置：旧快照维度也必须是'确定不可用'（不是不可知）")
			.isEqualTo(AiConfigService.BindingState.ABSENT);
		seedFrozenSnapshot(null, SHARED_MODEL);
		ResumeService readerService = resumeService(reader);

		ResumeService.ResumeResult result = readerService.resume(RUN_ID);

		assertThat(result.outcome()).as("从未声明 key = 确定不可用（旧快照也一样）")
			.isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/**
	 * 复核 I-1 的反面（同一分支的另一半）：行/模型都在、key 声明了却解不开 ⇒ 仍是"不可知"，
	 * 旧快照不得因此被判死。
	 */
	@Test
	void legacyFrozenSnapshotWithUnreadableKeyStaysUnknown() {
		AiConfigTestFixture shared = new AiConfigTestFixture();
		String cipher = new AiKeyCipher(AiConfigTestFixture.KEK_A).encrypt("sk-legacy-0123456789", "legacy-rotated",
				AiProvider.KEY_REF_DB);
		AiProvider stored = shared.addDbProvider("legacy-rotated", "https://legacy-rotated.example", cipher);
		shared.addModel(stored.getId(), SHARED_MODEL, true);
		AiConfigService foreignKek = shared.service(AiConfigTestFixture.KEK_B);
		assertThat(foreignKek.resolveChatTarget(SHARED_MODEL)).as("前置：KEK 不符 ⇒ 按名字解析也为空").isEmpty();
		assertThat(foreignKek.bindingState(null, SHARED_MODEL))
			.as("前置：旧快照维度 = 声明了但解不开")
			.isEqualTo(AiConfigService.BindingState.KEY_UNREADABLE);
		seedFrozenSnapshot(null, SHARED_MODEL);
		ResumeService foreignService = resumeService(foreignKek);

		ResumeService.ResumeResult result = foreignService.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.STATE_UNKNOWN);
		assertThat(this.store.get(RUN_ID).getStatus()).as("KEK 修好就能续 ⇒ 不得判死")
			.isEqualTo(RunSnapshot.SUSPENDED);
	}

	/**
	 * 边界（与上一条成对）：<b>从未声明 key</b>是确定的配置状态（{@code key_ref=NONE}），不是"解不开" ——
	 * 它沿用既有口径（{@code PROVIDER_GONE} + FAILED），不落进"不可知"。
	 */
	@Test
	void missingKeyDeclarationIsKnownAbsentAndStillConverges() {
		AiConfigTestFixture shared = new AiConfigTestFixture();
		AiProvider stored = shared.addProvider(providerWithoutKey("no-key", "https://no-key-endpoint"));
		shared.addModel(stored.getId(), SHARED_MODEL, true);
		AiConfigService reader = shared.service();
		assertThat(reader.bindingState(stored.getId(), SHARED_MODEL))
			.as("前置：没声明 key ⇒ 行级判据是确定不可用（不是不可知）")
			.isEqualTo(AiConfigService.BindingState.ABSENT);
		seedSuspendedSnapshot(stored.getId(), SHARED_MODEL);
		ResumeService readerService = resumeService(reader);

		ResumeService.ResumeResult result = readerService.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/** 旧快照（无 providerId）：不判身份，按模型名兼容 + 五参旧签名。 */
	@Test
	void legacySnapshotWithoutProviderKeepsTheNameBasedCompatibility() {
		seedSuspendedSnapshot(null);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService).driveResumed(anyString(), anyString(), any(AiContext.class),
				org.mockito.ArgumentMatchers.<List<Message>>any(), any());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedSuspendedSnapshot(Long providerId) {
		seedSuspendedSnapshot(providerId, providerId == null ? null : SHARED_MODEL);
	}

	private void seedSuspendedSnapshot(Long providerId, String modelId) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setProviderId(providerId);
		snapshot.setModelId(modelId);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("你好"))));
		this.store.save(snapshot);
	}

	/** 旧/冻结快照：{@code providerId=null} 且 {@code paramsFrozen()=true}（L-26 之后冻结的无身份快照）。 */
	private void seedFrozenSnapshot(Long providerId, String modelId) {
		seedSuspendedSnapshot(providerId, modelId);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, false));
		this.store.save(snapshot);
	}

	/** 从没声明过 key 的 provider（{@code key_ref} 缺省 = {@code NONE}）。 */
	private static AiProvider providerWithoutKey(String code, String baseUrl) {
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

	private ResumeService resumeService(AiConfigService config) {
		ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		ResumeService resume = new ResumeService(this.store, mock(RunRegistry.class), this.codec, this.chatService,
				mock(SpToolCallingManager.class), executor, mock(StringRedisTemplate.class), this.properties,
				mock(CancellationRegistry.class), mock(ToolResultLimiter.class));
		ReflectionTestUtils.setField(resume, "aiConfigService", config);
		return resume;
	}

	private void disableModel(Long providerId, String modelId) {
		this.fixture.models.removeIf(model -> providerId.equals(model.getProviderId())
				&& modelId.equals(model.getModelId()));
		this.configService.reload();
	}

}
