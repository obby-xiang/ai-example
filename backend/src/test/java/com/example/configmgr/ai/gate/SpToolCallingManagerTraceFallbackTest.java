package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.AiStateUnavailableException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C1：{@code traceToolAborted} 是诊断路径，读待决条目失败时不得二次抛（否则原始异常被替换，
 * 连 {@code tool_call_end} 都写不出）。本用例直接打到真实私有方法，不依赖完整工具链路。
 */
class SpToolCallingManagerTraceFallbackTest {

	private static final String RUN_ID = "run-trace-fallback";

	private static final String SESSION_ID = "s-trace-fallback";

	@Test
	void unreadablePendingDoesNotReplaceTheOriginalException() throws Exception {
		RunStore store = mock(RunStore.class);
		when(store.pendingLenient(anyString(), anyString()))
			.thenThrow(new AiStateUnavailableException("模拟 Redis 读失败"));
		SpToolCallingManager manager = new SpToolCallingManager(store, null, null, null, null, null,
				mock(CancellationRegistry.class), new ToolResultLimiter(new AiProperties()), List.of());
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-1");
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		RuntimeException original = new RuntimeException("原始执行异常");

		Logger logger = (Logger) LoggerFactory.getLogger(com.example.configmgr.ai.observe.AiTrace.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			Method trace = SpToolCallingManager.class.getDeclaredMethod("traceToolAborted", String.class,
					String.class, PendingToolCall.class, long.class, RuntimeException.class);
			trace.setAccessible(true);

			assertThatCode(() -> trace.invoke(manager, RUN_ID, SESSION_ID, pending, System.currentTimeMillis(),
					original)).as("诊断路径读失败不得二次抛").doesNotThrowAnyException();
			assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
				.contains("event=tool_call_end").contains("toolCallId=call-1"));
		}
		finally {
			logger.detachAppender(appender);
		}
	}

}
