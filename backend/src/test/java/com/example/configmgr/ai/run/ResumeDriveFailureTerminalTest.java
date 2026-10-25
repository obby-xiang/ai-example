package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>I6：续跑驱动阶段的身份失效必须落成终态</b>。
 *
 * <h2>缺陷</h2>
 * {@code ResilientChatService.runtimeForSnapshot} 在"快照 provider/model 此刻不可用"时抛错
 * （不跨 provider 续跑）；旧实现里 {@code ResumeService} 的驱动任务只 {@code out.error(...)} 了事 ——
 * 快照<b>仍是 RUNNING</b>、没有驱动方，会话门也被释放 ⇒ 这一轮变成"半死轮"：既不会被判终态，
 * 又没有人能推进。
 *
 * <h2>本批口径</h2>
 * 驱动任务捕获异常后：① 发既有 {@code AI_RESUME_FAILED} 错误帧（协议不变）；② 把快照落成
 * <b>FAILED</b> 终态（身份失效时错误码带 {@code PROVIDER_GONE} 前缀，机器可读）；③ 清空
 * in-flight（不再有"挂起中的工具"）；④ 会话门在 finally 释放。
 */
class ResumeDriveFailureTerminalTest {

	private static final String RUN_ID = "run-drive-failure";

	private static final String SESSION_ID = "s-drive-failure";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			mock(SpToolCallingManager.class), this.executor, this.redis, this.properties, this.cancellations,
			this.limiter);

	@BeforeEach
	void setUp() {
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		ReflectionTestUtils.setField(this.service, "sessionGate", this.sessionGate);
		seedSuspendedRun();
	}

	@Test
	void providerGoneAtDriveTimeLandsAsFailedTerminalState() {
		org.mockito.Mockito.doThrow(new ProviderGoneException(RUN_ID, 7L, "model-x"))
			.when(this.chatService)
			.driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class),
					org.mockito.ArgumentMatchers.<List<Message>>any(), eq(7L), eq("model-x"), eq(this.emitter));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("身份失效必须落 FAILED 终态，不能停在 RUNNING")
			.isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).as("机器可读的错误码前缀").contains("PROVIDER_GONE");
		assertThat(snapshot.getInFlightToolCalls()).as("不再有挂起中的工具").isEmpty();
		verify(this.emitter).error(eq(com.example.configmgr.common.ErrorCodes.AI_RESUME_FAILED), anyString());
		verify(this.sessionGate).release(eq(SESSION_ID), eq(RUN_ID), anyString());
	}

	@Test
	void genericDriveFailureAlsoLandsAsFailedTerminalState() {
		org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
			.when(this.chatService)
			.driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class),
					org.mockito.ArgumentMatchers.<List<Message>>any(), eq(7L), eq("model-x"), eq(this.emitter));

		this.service.resume(RUN_ID);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).contains(com.example.configmgr.common.ErrorCodes.AI_RESUME_FAILED);
		verify(this.sessionGate).release(eq(SESSION_ID), eq(RUN_ID), anyString());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setProviderId(7L);
		snapshot.setModelId("model-x");
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("问题"))));
		this.store.save(snapshot);
	}

}
