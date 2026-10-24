package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-08：续跑驱动线程里的未捕获异常，<b>用户面只拿白名单短句</b>；原始异常消息只进日志。
 *
 * <p>此前 {@code out.error(AI_RESUME_FAILED, ex.getMessage())} 会把上游 body / 客户端内部提示
 * 逐字送进 SSE 错误帧。本用例锁两件事：
 * <ol>
 * <li>error 帧的 message 恰好是 {@link ResumeService#AI_RESUME_FAILED_MESSAGE}；</li>
 * <li>帧里不出现原始异常的任一特征串（此处用可识别的上游 body 文本）。</li>
 * </ol>
 */
class ResumeFailureMessageTest {

	private static final String RUN_ID = "run-resume-failed";

	private static final String SESSION_ID = "s-resume-failed";

	private static final String RAW = "HTTP 500 upstream body: secret-token-123";

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
	void uncaughtResumeExceptionIsReportedAsWhitelistedShortMessage() {
		seedSuspendedRun();

		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		doThrow(new RuntimeException(RAW)).when(this.chatService)
			.driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), anyList(), eq(this.emitter));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(this.emitter).error(eq(ErrorCodes.AI_RESUME_FAILED), message.capture());
		assertThat(message.getValue()).isEqualTo(ResumeService.AI_RESUME_FAILED_MESSAGE);
		assertThat(message.getValue()).doesNotContain("secret-token-123").doesNotContain("HTTP 500");
	}

	/**
	 * I-5 / 4B：HTTP 入口把请求 locale 交给续跑服务；驱动线程 set/reset，en 白名单短句来自
	 * {@code messages_en}，原始异常仍只进日志。
	 */
	@Test
	void englishRequestLocaleReachesResumeWorkerAndUsesEnglishWhitelist() {
		seedSuspendedRun();
		MessageSource source = mock(MessageSource.class);
		String enMessage = "The previous response could not be resumed. Please try again.";
		when(source.getMessage("error." + ErrorCodes.AI_RESUME_FAILED, null, Locale.ENGLISH))
			.thenReturn(enMessage);
		ReflectionTestUtils.setField(this.service, "messageSource", source);

		AtomicReference<String> workerLanguage = new AtomicReference<>();
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		doAnswer(invocation -> {
			workerLanguage.set(ErrorBodyFactory.requestLocale().getLanguage());
			throw new RuntimeException(RAW);
		}).when(this.chatService)
			.driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), anyList(), eq(this.emitter));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, Locale.ENGLISH);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(workerLanguage.get()).as("续跑工作线程必须看到请求 locale").isEqualTo("en");
		verify(this.emitter).error(ErrorCodes.AI_RESUME_FAILED, enMessage);
		assertThat(enMessage).isNotEqualTo(ResumeService.AI_RESUME_FAILED_MESSAGE_EN);
		assertThat(ErrorBodyFactory.requestLocale().getLanguage()).as("池线程 locale 必须 reset").isEqualTo("zh");
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
	}
}
