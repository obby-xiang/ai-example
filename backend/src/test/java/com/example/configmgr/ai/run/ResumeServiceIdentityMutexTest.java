package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>续跑互斥（{@code resuming}）不得按裸 runId 把两个身份混在一桶</b>。
 *
 * <h2>缺陷形态</h2>
 * 旧表 {@code runId → 是否在续跑}：A 正在续跑 X 时，B 的<b>同名</b>轮次（另一个租户，合法且必然
 * 会出现的同形 runId）来续跑，会被判成 {@code IN_PROGRESS} —— B 的一轮既没有被谁驱动，也永远
 * 拿不到续跑权，直到 A 那一轮结束（最长可以是一个人工等待窗口）。
 *
 * <p>本类钉住：互斥的粒度 = <b>身份 + runId</b>。同身份重复续跑仍只有一个能进；
 * 换一个身份就不该被挡。
 */
class ResumeServiceIdentityMutexTest {

	private static final String RUN_ID = "run-shared-resume";

	private static final String SESSION_ID = "s-shared-resume";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

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
	void resumingMutexIsScopedToIdentityPlusRunId() {
		seedSuspendedRun();
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.registry.of(RUN_ID)).thenReturn(mock(SseChatEmitter.class));
		// 交棒任务不执行（mock 的 execute 默认什么都不做）：A 的 resuming 因此一直持有
		// （模拟"驱动线程还在跑"）

		ResumeService.ResumeResult firstInA = as(A, () -> this.service.resume(RUN_ID));
		assertThat(firstInA.outcome()).as("A 的第一拍受理").isEqualTo(ResumeService.Outcome.ACCEPTED);

		ResumeService.ResumeResult secondInA = as(A, () -> this.service.resume(RUN_ID));
		assertThat(secondInA.outcome()).as("同身份的重复续跑仍被进程内互斥挡住（互斥没有被削弱）")
			.isEqualTo(ResumeService.Outcome.IN_PROGRESS);

		ResumeService.ResumeResult inB = as(B, () -> this.service.resume(RUN_ID));
		assertThat(inB.outcome()).as("另一身份的同名轮次不得被 A 的续跑互斥挡住").isNotEqualTo(ResumeService.Outcome.IN_PROGRESS);
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
