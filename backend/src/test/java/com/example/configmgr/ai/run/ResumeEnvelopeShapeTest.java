package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M8：续跑重建的 {@code role:tool} 模型文本必须与首轮<b>同形</b>（统一信封 JSON）。
 *
 * <p>首轮由 {@code SpToolCallingManager} 用
 * {@code ToolResultEnvelope.of(outcome, code, message, arguments).toModelJson(limiter)} 回填；
 * 续跑（{@code ResumeService}）若只回填 {@code resultText} 原文，模型会看到两种历史形态 ——
 * 同一轮里前半段是信封、后半段是纯文本，工具结果口径随之漂移。
 *
 * <p>本用例不驱动模型：{@link ResilientChatService} 与 {@link SpToolCallingManager#resolvePending}
 * 都是桩，断言对象是 {@code ResumeService} 重建进 {@code messageJson} 与 {@code driveResumed}
 * 入参里的那条 TOOL 消息。
 */
class ResumeEnvelopeShapeTest {

	private static final String RUN_ID = "run-resume-envelope";

	private static final String SESSION_ID = "s-resume-envelope";

	private static final String CALL_ID = "call-resume-1";

	private static final String ARGS = "{\"taskId\":7}";

	private static final String MESSAGE = "导入作业已启动（作业 #4242）";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

	@Test
	void resumedToolMessageUsesSameEnvelopeShapeAsFirstRound() {
		seedSuspendedRun();
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn(MESSAGE);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		// 执行器就地执行：让 driveResumed 的入参可被断言（生产上它是异步的，内容不变）
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("resumed", true)
			.containsEntry("promptRoles", List.of("USER", "ASSISTANT", "TOOL"));

		RunSnapshot snapshot = this.store.get(RUN_ID);
		ToolResponseMessage toolMessage = toolMessage(this.codec.deserializeAll(snapshot.getMessageJson()));
		String modelText = toolMessage.getResponses().get(0).responseData();

		// 同形锁：与首轮回填逐字节一致（同一个工厂 + 同一个 limiter 的产物）
		String firstRound = ToolResultEnvelope.of(ToolOutcome.OK, null, MESSAGE, ARGS).toModelJson(this.limiter);
		assertThat(modelText).isEqualTo(firstRound);

		ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(modelText);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.OK.wire());
		assertThat(envelope.code()).isNull();
		assertThat(envelope.message()).isEqualTo(MESSAGE);
		assertThat(envelope.target()).isEqualTo(Map.of("taskId", 7));

		// 交出去的续跑历史里也必须是信封（不只是落盘的快照）
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), history.capture(),
				eq(this.emitter));
		assertThat(toolMessage(history.getValue()).getResponses().get(0).responseData()).isEqualTo(firstRound);
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 7L, null).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请启动导出"))));
		PendingToolCall inFlight = entry();
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(inFlight)));
		this.store.save(snapshot);
		this.store.seedPending(RUN_ID, entry());
	}

	private static PendingToolCall entry() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setType("function");
		pending.setArguments(ARGS);
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText(MESSAGE);
		pending.setExecuted(true);
		pending.setExecutedBy("instance-old");
		return pending;
	}

	private static ToolResponseMessage toolMessage(List<Message> history) {
		return history.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("续跑历史里没有 role:tool 消息：" + history));
	}

}
