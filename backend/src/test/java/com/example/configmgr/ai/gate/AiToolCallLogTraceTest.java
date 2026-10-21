package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具链路的日志用例（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>工具是"这一轮做了什么"的重心：{@code tool_call_start}/{@code tool_call_end} 两端成对，
 * 同一个 {@code toolCallId} 串联，{@code ms/ok/outcome/approval} 直接回答"哪个工具慢、失败没、要不要人批"。
 *
 * <table>
 * <caption>用例矩阵</caption>
 * <tr><td>T1</td><td>后端工具执行 ⇒ 两端各一行，toolCallId 对齐，{@code approval=false}</td></tr>
 * <tr><td>T2</td><td>入参/出参全文<b>不进日志</b>（负断言：参数值与结果文本都不出现）</td></tr>
 * </table>
 */
class AiToolCallLogTraceTest {

	private static final String RUN_ID = "run-toollog";

	private static final String SESSION_ID = "s-toollog";

	private static final String CALL_ID = "call-toollog-1";

	/** 参数与结果里的可识别串：它们绝不能出现在事件行里。 */
	private static final String ARG_MARKER = "taskId-marker-7788";

	private static final String RESULT_MARKER = "结果正文-不要进日志";

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final EchoTool tool = new EchoTool();

	private InMemoryRunStore store;

	private RunRegistry registry;

	private SpToolCallingManager manager;

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		this.store = new InMemoryRunStore();
		this.registry = new RunRegistry(this.store, new ObjectMapper(), null);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of());
		this.manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	@Test
	void toolExecutionLeavesAPairOfTraceLinesKeyedByToolCallId() {
		ExecuteResult result = execute("echo", "{\"text\":\"" + ARG_MARKER + "\"}");

		List<String> names = result.names();
		assertThat(names).as("每次工具调用两端各一行；实收=" + names).contains("tool_call_start", "tool_call_end");

		String start = result.line("tool_call_start");
		assertThat(start).contains("runId=" + RUN_ID).contains("sessionId=" + SESSION_ID)
			.contains("toolCallId=" + CALL_ID).contains("tool=echo").contains("kind=BACKEND")
			.contains("approval=false");

		String end = result.line("tool_call_end");
		assertThat(end).contains("toolCallId=" + CALL_ID).contains("tool=echo").containsPattern("ms=\\d+")
			.contains("ok=true").contains("outcome=ok").contains("executed=true");
	}

	@Test
	void toolArgumentsAndResultsNeverReachTheTraceLines() {
		ExecuteResult result = execute("echo", "{\"text\":\"" + ARG_MARKER + "\"}");

		assertThat(result.lines()).isNotEmpty();
		assertThat(result.lines()).allSatisfy(line -> assertThat(line)
			.as("工具入参/出参全文不得进日志（只记元数据）: %s", line)
			.doesNotContain(ARG_MARKER)
			.doesNotContain(RESULT_MARKER));
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private record ExecuteResult(List<String> lines) {

		List<String> names() {
			return this.lines.stream().map(line -> line.substring("event=".length()).split(" ")[0]).toList();
		}

		String line(String name) {
			return this.lines.stream().filter(line -> line.startsWith("event=" + name + " ")).findFirst()
				.orElseThrow(() -> new AssertionError("缺少事件行 event=" + name + "；实收 = " + this.lines));
		}

	}

	private ExecuteResult execute(String toolName, String args) {
		SseChatEmitter out = this.registry.of(RUN_ID);
		out.start(SESSION_ID);
		this.manager.executeToolCalls(prompt(), response(toolName, args));
		out.done(Map.of(), "test-model", Map.of("cancelled", false));
		List<String> lines = new ArrayList<>();
		// 事件可能由别的线程正在追加：先做一次快照（toArray 语义，不经迭代器 ⇒ 不会 CME）
		for (ILoggingEvent event : new ArrayList<>(this.appender.list)) {
			if (event.getFormattedMessage().startsWith("event=")) {
				lines.add(event.getFormattedMessage());
			}
		}
		return new ExecuteResult(lines);
	}

	private Prompt prompt() {
		List<ToolCallback> callbacks = List.of(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
			.toolObjects(this.tool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请回显")), options);
	}

	private static ChatResponse response(String tool, String args) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	/** 受控替身：回显入参（回显文本本身也不得出现在日志里）。 */
	static class EchoTool {

		final AtomicInteger invocations = new AtomicInteger();

		@org.springframework.ai.tool.annotation.Tool(name = "echo", description = "受控替身：回显入参")
		public String echo(
				@org.springframework.ai.tool.annotation.ToolParam(description = "文本") String text) {
			this.invocations.incrementAndGet();
			return RESULT_MARKER;
		}

	}

}
