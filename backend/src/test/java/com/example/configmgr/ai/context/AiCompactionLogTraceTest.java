package com.example.configmgr.ai.context;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩事件的日志用例（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>{@code event=compaction} 回答"这一轮的历史为什么变了、省了多少 token"：
 * {@code level/reason/before/after/savedTokens/limit} 与 {@code context} 帧、{@code ai:ctx:ledger:} 同源。
 */
class AiCompactionLogTraceTest {

	private static final String SESSION_ID = "s-compact-log";

	private static final String RUN_ID = "run-compact-log";

	private final AiProperties properties = new AiProperties();

	private final InMemoryContextStateStore store = new InMemoryContextStateStore();

	private final ContextCompactor compactor = new ContextCompactor(this.properties,
			new ContextLimitResolver(this.properties), this.store);

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	@Test
	void compactionEmitsLevelReasonAndSavedTokens() {
		this.compactor.compact(request(turns(6), 81000));

		String line = eventLines().stream().filter(item -> item.startsWith("event=compaction "))
			.findFirst()
			.orElseThrow(() -> new AssertionError("缺少事件行 event=compaction；实收 = " + eventLines()));
		assertThat(line).contains("runId=" + RUN_ID).contains("sessionId=" + SESSION_ID)
			.contains("level=L1").containsPattern("reason=[a-z_]+").containsPattern("savedTokens=\\d+")
			.containsPattern("before=\\d+").containsPattern("after=\\d+").containsPattern("limit=\\d+");
	}

	@Test
	void belowThresholdEmitsNothing() {
		this.compactor.compact(request(turns(2), 1000));

		assertThat(eventLines()).as("未压缩就不该有 compaction 行（「没发生」与「发生 0 次」必须可区分）").isEmpty();
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private List<String> eventLines() {
		List<String> out = new ArrayList<>();
		// 事件可能由别的线程正在追加：先做一次快照（toArray 语义，不经迭代器 ⇒ 不会 CME）
		for (ILoggingEvent event : new ArrayList<>(this.appender.list)) {
			String message = event.getFormattedMessage();
			if (message.startsWith("event=")) {
				out.add(message);
			}
		}
		return out;
	}

	private ContextCompactor.Request request(List<Message> messages, int usedTokens) {
		return new ContextCompactor.Request(SESSION_ID, RUN_ID, "test-model", 1L, messages,
				List.of("[{\"m\":1}]"), usedTokens, 0, 0, 0, 3000, 4000, Set.of());
	}

	private static List<Message> turns(int pairs) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= pairs; i++) {
			messages.add(new UserMessage("第 " + i + " 个问题"));
			messages.add(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c-" + i, "function", "list_tasks", "{}")))
				.build());
			messages.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("c-" + i, "list_tasks",
						"任务列表内容".repeat(50) + i)))
				.build());
		}
		return messages;
	}

}
