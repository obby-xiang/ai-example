package com.example.configmgr.ai.observe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志 pattern 的配置锁（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>事件行只解决"这一行说了什么"，"行首是谁"要靠 {@code logging.pattern.level} 把 MDC 渲染出来。
 * 配置是行为测试看不见的那一半，故这里做两件事：
 * <ol>
 * <li><b>字面锁</b>：配置里必须有 {@code %X{runId…}} / {@code %X{sessionId…}}；</li>
 * <li><b>渲染锁</b>：把这个 pattern 真塞进 {@link PatternLayout} 排版一条事件 ——
 *     绑了锚点的行必须出现 runId/sessionId，没绑的行必须**什么都不多出来**
 *     （后半条很重要：多一行噪声就会污染"按行首匹配"的既有脚本）。</li>
 * </ol>
 */
class AiLogPatternConfigTest {

	private static final String RUN_ID = "run-pattern";

	private static final String SESSION_ID = "s-pattern";

	private static final Pattern LEVEL_PATTERN = Pattern.compile("level:\\s*\"([^\"]+)\"");

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	@Test
	void consolePatternDeclaresBothAnchors() throws IOException {
		String pattern = levelPattern();

		assertThat(pattern).as("行首锚点占位符（MDC 可见性的唯一保障）")
			.contains("%X{runId")
			.contains("%X{sessionId");
	}

	@Test
	void boundLinesRenderTheAnchorAndUnboundLinesStayClean() throws IOException {
		PatternLayout layout = new PatternLayout();
		layout.setContext(new LoggerContext());
		layout.setPattern(levelPattern());
		layout.start();
		capture();

		AiTrace.bind(RUN_ID, SESSION_ID);
		AiTrace.event("turn_end", "status", "done");
		AiTrace.clear();
		AiTrace.event("turn_end", "status", "done");

		String bound = layout.doLayout(this.appender.list.get(0));
		assertThat(bound).as("绑了锚点：行首就是这一轮的 runId/sessionId").contains(RUN_ID).contains(SESSION_ID);

		String unbound = layout.doLayout(this.appender.list.get(1));
		assertThat(unbound).as("没绑锚点：不渲染任何占位（既有行形不变）")
			.doesNotContain(RUN_ID).doesNotContain(SESSION_ID).doesNotContain("null");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private void capture() {
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		this.traceLogger.setLevel(Level.INFO);
	}

	/** 从 `application.yml` 的 `logging.pattern.level` 取出 pattern 原文。 */
	private static String levelPattern() throws IOException {
		String yml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
		Matcher matcher = LEVEL_PATTERN.matcher(yml);
		assertThat(matcher.find()).as("application.yml 缺少 logging.pattern.level").isTrue();
		return matcher.group(1);
	}

}
