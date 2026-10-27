package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ResilientChatService.Accumulator} 末帧 tail 与"终态后又收到 finishReason"WARN 的定位字段。
 *
 * <p>只验证日志字段与既有行为（正文累加）不变；Accumulator 是私有静态嵌套类，故用反射构造与调用。
 */
class AccumulatorLocatorLogTest {

	private static final String RUN_ID = "run-acc-locator";

	private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(ResilientChatService.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		this.appender.start();
		this.serviceLogger.addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		this.serviceLogger.detachAppender(this.appender);
	}

	@Test
	void tailAndTerminalOverwriteWarnCarryShardIndexOffsetAndToolCalls() throws Exception {
		Class<?> accumulatorType = Class.forName("com.example.configmgr.ai.run.ResilientChatService$Accumulator");
		Constructor<?> constructor = accumulatorType.getDeclaredConstructor(String.class);
		constructor.setAccessible(true);
		Object accumulator = constructor.newInstance(RUN_ID);
		Method accept = accumulatorType.getDeclaredMethod("accept", ChatResponse.class);
		accept.setAccessible(true);
		Method tailSummary = accumulatorType.getDeclaredMethod("tailSummary");
		tailSummary.setAccessible(true);
		Method text = accumulatorType.getDeclaredMethod("text");
		text.setAccessible(true);

		accept.invoke(accumulator, chunk("he", null, false));
		accept.invoke(accumulator, chunk("llo", null, false));
		accept.invoke(accumulator, chunk("", "stop", false));
		accept.invoke(accumulator, chunk("", "tool_calls", true));

		String tail = (String) tailSummary.invoke(accumulator);
		assertThat(tail).contains("i=1|dMs=0|finishReason=null|textLen=2|hasToolCalls=false|");
		assertThat(tail).contains("i=2|dMs=");
		assertThat(tail).contains("|finishReason=null|textLen=3|hasToolCalls=false|");
		assertThat(tail).contains("i=3|dMs=");
		assertThat(tail).contains("|finishReason=stop|textLen=0|hasToolCalls=false|");
		assertThat(tail).contains("i=4|dMs=");
		assertThat(tail).contains("|finishReason=tool_calls|textLen=0|hasToolCalls=true|");
		assertThat(text.invoke(accumulator)).isEqualTo("hello");

		List<String> warns = this.appender.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.filter(line -> line.contains("终态后又收到 finishReason"))
			.toList();
		assertThat(warns).hasSize(1);
		assertThat(warns.get(0)).contains("terminal=STOP")
			.contains("incoming=tool_calls")
			.contains("hasToolCalls=true")
			.contains("i=4")
			.containsPattern("dMs=\\d+")
			.contains("runId=" + RUN_ID);

		// 再收到一个非空 finishReason：每 attempt 仍只有一条 WARN，且 tail 继续按序号追加。
		accept.invoke(accumulator, chunk("", "length", false));
		assertThat(this.appender.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.filter(line -> line.contains("终态后又收到 finishReason")))
			.hasSize(1);
		assertThat((String) tailSummary.invoke(accumulator)).contains("i=5|dMs=").contains("|finishReason=length|");
	}

	private static ChatResponse chunk(String content, String finishReason, boolean toolCalls) {
		AssistantMessage message = toolCalls
				? AssistantMessage.builder()
					.content(content)
					.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "ui_snapshot", "{}")))
					.build()
				: new AssistantMessage(content);
		Generation generation = finishReason == null ? new Generation(message)
				: new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build());
		return new ChatResponse(List.of(generation));
	}

}
