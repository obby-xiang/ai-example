package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批③ 续跑的<b>模型身份</b>契约（矩阵 B3-21/22/23）：
 * <ol>
 * <li>快照 provider 已删/停用/模型已不在 allowlist ⇒ {@code PROVIDER_GONE}，<b>不驱动</b>；</li>
 * <li>可用 ⇒ 按<b>快照</b> identity 驱动（用户改过默认模型也不影响这一轮）；</li>
 * <li>旧快照（{@code providerId=null}）⇒ 不判 identity，按默认驱动 + WARN（兼容批① 之前的数据）。</li>
 * </ol>
 *
 * <h2>证红方式</h2>
 * 把 {@code PROVIDER_GONE} 检查整段删掉 ⇒ 第 1 例变成 ACCEPTED（红）；
 * 把驱动侧的 identity 实参换回 {@code null}（即批③ 之前的行为）⇒ 第 2 例的 captor 断言红。
 */
class ResumeProviderGoneTest {

	private static final String RUN_ID = "run-provider-gone";

	private static final String SESSION_ID = "s-provider-gone";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final AiConfigService configService = mock(AiConfigService.class);

	private final AiProperties properties = new AiProperties();

	private ResumeService service;

	@BeforeEach
	void setUp() {
		ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
		// 同步执行：续跑驱动在测试线程里跑完，verify 不必等
		org.mockito.Mockito.doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		this.service = new ResumeService(this.store, mock(RunRegistry.class), this.codec, this.chatService,
				mock(SpToolCallingManager.class), executor, mock(StringRedisTemplate.class), this.properties,
				mock(CancellationRegistry.class), mock(ToolResultLimiter.class));
		ReflectionTestUtils.setField(this.service, "aiConfigService", this.configService);
	}

	@Test
	void deletedProviderYieldsProviderGone() {
		seedSnapshot(7L, "deepseek-flash");
		when(this.configService.resolveChatTarget("deepseek-flash")).thenReturn(Optional.empty());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		assertThat(result.body()).containsEntry("reason", "PROVIDER_GONE")
			.containsEntry("providerId", 7L)
			.containsEntry("modelId", "deepseek-flash");
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any(), any(),
				any());
		assertThat(result.outcome().releasesLease()).as("没有人会接手 ⇒ 归还续跑权").isTrue();
	}

	@Test
	void resumeKeepsTheSnapshotModelIdentity() {
		seedSnapshot(7L, "deepseek-flash");
		// 用户已经把默认模型改成 pro：解析出的目标仍是快照那一行（provider 相同、模型名以快照为准）
		when(this.configService.resolveChatTarget("deepseek-flash")).thenReturn(Optional.of(target(7L,
				"deepseek-flash")));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		ArgumentCaptor<Long> providerId = ArgumentCaptor.forClass(Long.class);
		ArgumentCaptor<String> modelId = ArgumentCaptor.forClass(String.class);
		verify(this.chatService).driveResumed(anyString(), anyString(), any(AiContext.class),
				org.mockito.ArgumentMatchers.<List<Message>>any(), providerId.capture(), modelId.capture(), any());
		assertThat(providerId.getValue()).isEqualTo(7L);
		assertThat(modelId.getValue()).as("续跑用的是快照模型，不是此刻的默认模型").isEqualTo("deepseek-flash");
	}

	@Test
	void legacySnapshotWithoutProviderFallsBackWithWarn() {
		seedSnapshot(null, null);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("旧快照没有身份可校验 ⇒ 不拦").isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.configService, never()).resolveChatTarget(any());
		// 旧快照走<b>五参旧签名</b>（语义等价，既有用例零改动）
		verify(this.chatService).driveResumed(anyString(), anyString(), any(AiContext.class),
				org.mockito.ArgumentMatchers.<List<Message>>any(), any());
	}

	@Test
	void providerSwitchOfTheSameModelYieldsProviderGone() {
		seedSnapshot(7L, "deepseek-flash");
		// 同一个模型名现在挂在另一个 provider 上（旧端点被删后重建）⇒ 仍是"快照的 provider 没了"
		when(this.configService.resolveChatTarget("deepseek-flash")).thenReturn(Optional.of(target(9L,
				"deepseek-flash")));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("换了端点就不是'原样续跑'：历史里的 provider 已不存在")
			.isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private void seedSnapshot(Long providerId, String modelId) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setProviderId(providerId);
		snapshot.setModelId(modelId);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(
				new org.springframework.ai.chat.messages.UserMessage("你好"))));
		this.store.save(snapshot);
	}

	private static AiConfigService.ChatTarget target(Long providerId, String modelId) {
		AiProvider provider = new AiProvider();
		provider.setId(providerId);
		AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(providerId, "deepseek",
				"http://placeholder", "/chat/completions", modelId, "sk-test-0123456789", "fp", false);
		return new AiConfigService.ChatTarget(resolved, modelId, true);
	}

}
