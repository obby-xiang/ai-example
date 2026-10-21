package com.example.configmgr.ai.observe;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiTrace} 的设施级用例（AI 可观测性专项·日志链路追踪批）。
 *
 * <table>
 * <caption>用例矩阵</caption>
 * <tr><td>T1</td><td>bind 装 MDC；同 run 内重复 bind 幂等；runId 变化摘掉旧 sessionId</td></tr>
 * <tr><td>T2</td><td>clear 摘干净（不把上一个请求的锚点留给池线程）</td></tr>
 * <tr><td>T3</td><td>wrap 把提交时刻的 MDC 带进另一条线程，且任务结束后该线程恢复原状</td></tr>
 * <tr><td>T4</td><td>event 行形态：<code>event=xxx runId=.. sessionId=.. k=v</code></td></tr>
 * <tr><td>T5</td><td>event 值单行化 + 截断（一条事件绝不折行）</td></tr>
 * <tr><td>T6</td><td>MDC 缺失时渲染 <code>-</code>（漏绑可见，不静默补空值）</td></tr>
 * </table>
 */
class AiTraceTest {

	private static final String RUN_ID = "run-trace-unit";

	private static final String SESSION_ID = "s-trace-unit";

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
	void bindPutsBothKeysAndIsIdempotent() {
		AiTrace.bind(RUN_ID, SESSION_ID);

		assertThat(MDC.get(AiTrace.RUN_ID)).isEqualTo(RUN_ID);
		assertThat(MDC.get(AiTrace.SESSION_ID)).isEqualTo(SESSION_ID);

		AiTrace.bind(RUN_ID, SESSION_ID);
		assertThat(MDC.get(AiTrace.RUN_ID)).isEqualTo(RUN_ID);
		assertThat(MDC.get(AiTrace.SESSION_ID)).isEqualTo(SESSION_ID);
	}

	@Test
	void bindWithAnotherRunDropsTheStaleSession() {
		AiTrace.bind(RUN_ID, SESSION_ID);
		AiTrace.bind("run-other", null);

		assertThat(MDC.get(AiTrace.RUN_ID)).isEqualTo("run-other");
		assertThat(MDC.get(AiTrace.SESSION_ID)).as("上一轮的会话不得落在这一轮的行首").isNull();
	}

	@Test
	void blankRunIdNeverOverwritesTheAnchor() {
		AiTrace.bind(RUN_ID, SESSION_ID);
		AiTrace.bind(null, "s-other");
		AiTrace.bind("  ", "s-other");

		assertThat(MDC.get(AiTrace.RUN_ID)).isEqualTo(RUN_ID);
		assertThat(MDC.get(AiTrace.SESSION_ID)).isEqualTo(SESSION_ID);
	}

	@Test
	void clearRemovesBothKeys() {
		AiTrace.bind(RUN_ID, SESSION_ID);
		AiTrace.clear();

		assertThat(MDC.get(AiTrace.RUN_ID)).isNull();
		assertThat(MDC.get(AiTrace.SESSION_ID)).isNull();
	}

	@Test
	void wrapCarriesMdcIntoAnotherThreadAndRestoresIt() throws Exception {
		AiTrace.bind(RUN_ID, SESSION_ID);
		ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "trace-wrap-worker");
			thread.setDaemon(true);
			return thread;
		});
		try {
			AtomicReference<String> seenRunId = new AtomicReference<>();
			AtomicReference<String> seenSessionId = new AtomicReference<>();
			AtomicReference<String> leftoverRunId = new AtomicReference<>("unset");
			AtomicReference<String> callerRunId = new AtomicReference<>();
			CountDownLatch done = new CountDownLatch(2);

			pool.execute(AiTrace.wrap(() -> {
				seenRunId.set(MDC.get(AiTrace.RUN_ID));
				seenSessionId.set(MDC.get(AiTrace.SESSION_ID));
				callerRunId.set(MDC.get(AiTrace.RUN_ID));
				done.countDown();
			}));
			// 同一个池线程上的下一个任务（不包装）：必须看不到上一轮留下的锚点
			pool.execute(() -> {
				leftoverRunId.set(MDC.get(AiTrace.RUN_ID));
				done.countDown();
			});

			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(seenRunId.get()).as("MDC 跨线程不丢").isEqualTo(RUN_ID);
			assertThat(seenSessionId.get()).isEqualTo(SESSION_ID);
			assertThat(leftoverRunId.get()).as("任务结束后池线程不留锚点（不串下一轮）").isNull();
			assertThat(MDC.get(AiTrace.RUN_ID)).as("包装体不污染提交方线程").isEqualTo(RUN_ID);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void eventLineHasTheAgreedShape() {
		AiTrace.bind(RUN_ID, SESSION_ID);

		AiTrace.event("turn_start", "model", "m-1", "attempt", 1);

		List<ILoggingEvent> events = this.appender.list;
		assertThat(events).hasSize(1);
		ILoggingEvent event = events.get(0);
		assertThat(event.getLevel().toString()).isEqualTo("INFO");
		assertThat(event.getFormattedMessage())
				.isEqualTo("event=turn_start runId=" + RUN_ID + " sessionId=" + SESSION_ID + " model=m-1 attempt=1");
	}

	@Test
	void eventValuesAreFlattenedToOneLineAndTruncated() {
		AiTrace.bind(RUN_ID, SESSION_ID);

		AiTrace.event("note", "detail", "第一行\n第二行\r\n第三行", "huge", "x".repeat(400));

		String line = this.appender.list.get(0).getFormattedMessage();
		assertThat(line).doesNotContain("\n").doesNotContain("\r");
		assertThat(line).contains("detail=第一行 第二行  第三行");
		assertThat(line.length()).as("单值截断（160 字符 + 省略号）").isLessThan(320);
	}

	@Test
	void missingMdcRendersDashSoUnboundLinesAreVisible() {
		AiTrace.event("turn_end", "status", "done");

		assertThat(this.appender.list.get(0).getFormattedMessage())
				.isEqualTo("event=turn_end runId=- sessionId=- status=done");
	}

}
