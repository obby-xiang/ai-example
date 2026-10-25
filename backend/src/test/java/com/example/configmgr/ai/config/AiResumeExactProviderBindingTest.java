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

		ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		this.service = new ResumeService(this.store, mock(RunRegistry.class), this.codec, this.chatService,
				mock(SpToolCallingManager.class), executor, mock(StringRedisTemplate.class), this.properties,
				mock(CancellationRegistry.class), mock(ToolResultLimiter.class));
		ReflectionTestUtils.setField(this.service, "aiConfigService", this.configService);
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
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setProviderId(providerId);
		snapshot.setModelId(providerId == null ? null : SHARED_MODEL);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("你好"))));
		this.store.save(snapshot);
	}

	private void disableModel(Long providerId, String modelId) {
		this.fixture.models.removeIf(model -> providerId.equals(model.getProviderId())
				&& modelId.equals(model.getModelId()));
		this.configService.reload();
	}

}
