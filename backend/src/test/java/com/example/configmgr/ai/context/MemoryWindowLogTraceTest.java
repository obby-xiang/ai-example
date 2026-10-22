package com.example.configmgr.ai.context;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.observe.AiTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆窗口事件（{@code event=memory_window}）的日志用例。
 *
 * <p>
 * 与 {@code event=compaction} 的分工：{@code compaction} 回答"模型上下文占比到了哪一级、压了多少"，
 * {@code memory_window} 回答"<b>从记忆里读出来的历史为什么变短了</b>"。两者都带
 * {@code before/after} 计数，故排障时先看窗口（读入）再看压缩（按占比），不会把
 * "窗口本来就只有这么长"误读成"压缩把历史吃掉了"。
 *
 * <p>事件名是 <b>additive</b> 新增（既有事件字段一个不动）。
 */
class MemoryWindowLogTraceTest {

	private static final String RUN_ID = "run-mw-log";

	private static final String SESSION_ID = "s-mw-log";

	private static final MemoryWindow.Budget BUDGET = new MemoryWindow.Budget(6_000, 2, 40, 5);

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
	void trimmedWindowEmitsBeforeAfterCountsAndTurnDrop() {
		MemoryWindow.Plan plan = MemoryWindow.plan(turns(10, 1700), BUDGET, Set.of());

		MemoryWindow.emit(RUN_ID, SESSION_ID, plan, BUDGET);
		List<String> lines = eventLines();
		String line = lines.stream()
			.filter(item -> item.startsWith("event=memory_window "))
			.findFirst()
			.orElseThrow(() -> new AssertionError("缺少事件行 event=memory_window；实收 = " + lines));

		assertThat(line).contains("runId=" + RUN_ID).contains("sessionId=" + SESSION_ID)
			.contains("reason=turn_trim")
			.contains("before=20")
			.contains("after=6")
			.contains("tokensBefore=20000")
			.contains("tokensAfter=6000")
			.contains("budget=6000")
			.contains("droppedTurns=7")
			.contains("clearedToolResults=0")
			.contains("stillOver=false");
	}

	@Test
	void unchangedWindowEmitsNothing() {
		MemoryWindow.Plan plan = MemoryWindow.plan(turns(1, 40), BUDGET, Set.of());

		MemoryWindow.emit(RUN_ID, SESSION_ID, plan, BUDGET);

		assertThat(eventLines()).as("未改动就不该有 memory_window 行（「没发生」与「发生 0 次」必须可区分）")
			.isEmpty();
	}

	private static List<Message> turns(int count, int cjkChars) {
		String text = "问" + "长".repeat(cjkChars - 1);
		List<Message> messages = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			messages.add(new UserMessage(text));
			messages.add(new AssistantMessage(text));
		}
		return messages;
	}

	private List<String> eventLines() {
		List<String> out = new ArrayList<>();
		for (ILoggingEvent event : new ArrayList<>(this.appender.list)) {
			String message = event.getFormattedMessage();
			if (message.startsWith("event=")) {
				out.add(message);
			}
		}
		return out;
	}

}
