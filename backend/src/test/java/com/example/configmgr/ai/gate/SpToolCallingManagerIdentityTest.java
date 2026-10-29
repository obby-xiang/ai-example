package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 官方工具线程的身份恢复（租户任务3）：工具执行发生在 Reactor 上游线程，独立超时执行器又是一次线程跳转。
 *
 * <p>被钉住的形态：执行 {@code executeToolCalls} 的线程<b>没有</b>身份（模拟上游线程），
 * 身份只能从本轮快照/执行信封恢复；工具桩体跑在 {@code ai-tool-timeout-*} 池线程上，
 * 那里同样必须看到本轮身份（提交侧抓取、执行侧重放）。
 */
class SpToolCallingManagerIdentityTest {

	private static final String RUN_ID = "run-tool-identity";

	private static final String SESSION_ID = "s-tool-identity";

	private static final String TOOL = "list_tasks";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = mock(RunRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final AtomicReference<TenantUser> observed = new AtomicReference<>();

	private final AtomicReference<String> executedOnThread = new AtomicReference<>();

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of(), null, null, null,
			null, this.properties) {

		@Override
		String delegateSingle(Prompt prompt, PendingToolCall pending) {
			SpToolCallingManagerIdentityTest.this.observed.compareAndSet(null,
					RequestContextUtils.currentOrNull());
			SpToolCallingManagerIdentityTest.this.executedOnThread.set(Thread.currentThread().getName());
			return "执行成功";
		}
	};

	@AfterEach
	void tearDown() {
		// 管理器自建的超时池是守护线程且可空闲回收，无需显式关闭
	}

	@Test
	void toolBodyRunsWithTheRunIdentityEvenOnTheTimeoutExecutorThread() {
		RunSnapshot snapshot;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
			snapshot.setTenantId(9001L);
			snapshot.setUserId(7L);
			this.store.save(snapshot);
		}
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.categoryOf(anyString())).thenReturn(ToolMeta.Category.J);
		assertThat(RequestContextUtils.currentOrNull()).as("前置：调用线程模拟上游线程，无身份").isNull();

		this.manager.executeToolCalls(prompt(), response(call("c-1", TOOL, "{}")));

		assertThat(this.observed.get()).as("官方工具线程（超时执行器）必须带本轮身份")
			.isEqualTo(new TenantUser(9001L, 7L));
		assertThat(this.executedOnThread.get()).as("工具桩体应跑在独立超时执行器线程上")
			.startsWith("ai-tool-timeout-");
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("列一下任务")), options);
	}

	private static AssistantMessage.ToolCall call(String id, String name, String args) {
		return new AssistantMessage.ToolCall(id, "function", name, args);
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
