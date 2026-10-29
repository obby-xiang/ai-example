package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SecurityEvents;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>环外异常出口</b>（{@code POST /api/ai/chat} 的异步任务里抛错）—— 返修后按<b>每次驱动</b>判终帧。
 *
 * <h2>被钉住的两件事</h2>
 * <ol>
 * <li><b>用户面形状</b>（复审 M-4①）：{@code chat} 是<b>异步提交</b>，HTTP 早已 200 返回 emitter；
 *     环外异常发的是 <b>SSE {@code error} 帧 + {@code AI_RUN_FAILED}</b> —— <b>不是</b>同步 503，
 *     也不是 {@code AI_REDIS_UNAVAILABLE}（那是 {@code AiStateUnavailableException} 走
 *     {@code @ExceptionHandler} 的同步路径，本出口根本不经过它）；</li>
 * <li><b>终帧判据</b>（返修 I-2）：只看"<b>本次驱动</b>是否已落终帧"
 *     （{@link SseChatEmitter#terminalFrameArchivedSinceDriveStart()}），不看"整轮归档有没有终帧"。</li>
 * </ol>
 */
class AiControllerRingOutExitTest {

	private static final String SESSION = "s-ring-out";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final RunStore runStore = mock(RunStore.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private final AiController controller = controller();

	@Test
	void ringOutFailureEmitsSseErrorWithAiRunFailed() {
		doThrow(new IllegalStateException("环外异常（模拟），例如压缩/记忆写/取模型抛错"))
			.when(this.service)
			.chat(anyString(), any(AiChatRequest.class), eq(this.out));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);

		var response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).as("异步提交：HTTP 仍是 200，失败由 SSE 表达")
			.isEqualTo(HttpStatus.OK);
		verify(this.out, timeout(5000)).error(eq(ErrorCodes.AI_RUN_FAILED), anyString());
		verify(this.out, timeout(5000)).terminalFrameArchivedSinceDriveStart();
	}

	@Test
	void ringOutFailureIsSkippedWhenThisDriveAlreadyArchivedATerminal() {
		doThrow(new IllegalStateException("环外异常（模拟）")).when(this.service)
			.chat(anyString(), any(AiChatRequest.class), eq(this.out));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.out.terminalFrameArchivedSinceDriveStart()).thenReturn(Boolean.TRUE);

		this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		// 先等到"判据被问过"，再断言没有补第二条终帧
		verify(this.out, timeout(5000)).terminalFrameArchivedSinceDriveStart();
		verify(this.out, never()).error(anyString(), anyString());
	}

	// ───────────────────────── 装配（照 AiControllerChatGateTest 的最小形态） ─────────────────────────

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

	private AiController controller() {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);
		SessionGate sessionGate = mock(SessionGate.class);
		when(sessionGate.acquire(any(), any())).thenReturn(null);
		when(this.runRegistry.of(any())).thenReturn(this.out);

		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(32);
		executor.initialize();

		AiController built = new AiController(availability, providerOf(this.service),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), sessionGate, mock(ConfirmGate.class), this.runStore,
				this.runRegistry, this.cancellations, redisAvailability, executor, new ObjectMapper(), this.properties,
				mock(Environment.class), mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "contextFieldGate", new ContextFieldGate());
		ReflectionTestUtils.setField(built, "securityEvents", new SecurityEvents());
		return built;
	}

}
