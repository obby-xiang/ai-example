package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 续跑交棒的身份恢复（租户任务3）：续跑入口可能跑在 HTTP 线程、触发池、启动扫描或定时扫描上。
 *
 * <p>被钉住的形态：{@code ResumeService.resume} 在<b>无身份</b>的线程上被调用（模拟启动/定时扫描），
 * 快照里带着本轮身份 —— 交棒给挂起池的驱动任务必须在<b>快照身份</b>下运行 {@code driveResumed}。
 * 用例断言驱动回调看到的身份，并确认调用线程在返回后没有残留身份。
 */
class ResumeHandoffIdentityTest {

	private static final String RUN_ID = "run-resume-identity";

	private static final String SESSION_ID = "s-resume-identity";

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

	private final AtomicReference<TenantUser> observed = new AtomicReference<>();

	@Test
	void handoffDrivesResumedTurnWithSnapshotIdentityWhenEntryThreadHasNone() {
		seedSuspendedRun();
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		doAnswer(invocation -> {
			this.observed.set(RequestContextUtils.currentOrNull());
			return null;
		}).when(this.chatService)
			.driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(), eq(this.emitter));

		ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

		assertThat(RequestContextUtils.currentOrNull()).as("前置：调用线程模拟定时扫描，无身份").isNull();

		ResumeService.ResumeResult result = service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.observed.get()).as("驱动任务必须在快照身份下续跑")
			.isEqualTo(new TenantUser(9001L, 7L));
		assertThat(RequestContextUtils.currentOrNull()).as("续跑入口返回后不得在调用线程残留身份").isNull();
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
			snapshot.setTenantId(9001L);
			snapshot.setUserId(7L);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			snapshot.setContext(AiContext.empty().toMap());
			List<Message> history = new ArrayList<>();
			history.add(new SystemMessage("PERSONA"));
			history.add(new UserMessage("继续"));
			snapshot.setMessageJson(this.codec.serializeAll(history));
			this.store.save(snapshot);
		}
	}

}
