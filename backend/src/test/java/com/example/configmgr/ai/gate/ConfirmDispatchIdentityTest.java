package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HITL 死卡兜底派发的身份恢复（租户任务3）：续跑触发跑在 {@code aiResumeTrigger} 轻量池上，
 * 那里没有请求身份（决策来自 HTTP 线程、租约重试来自调度线程）。
 *
 * <p>被钉住的形态：{@code ConfirmGate} 按本轮快照建立身份作用域后才调 {@code ResumeService.resume}
 * —— 触发池线程看到的身份必须是<b>这一轮</b>的租户/用户，而不是 {@code null}。
 */
class ConfirmDispatchIdentityTest {

	private static final String RUN_ID = "run-confirm-dispatch-identity";

	private static final String SESSION_ID = "s-confirm-dispatch-identity";

	@Test
	void deadcardDispatchRunsResumeWithTheParkedRunIdentity() {
		InMemoryRunStore store = new InMemoryRunStore();
		RunSnapshot snapshot;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			snapshot = store.seedRun(RUN_ID, SESSION_ID);
			snapshot.setTenantId(9001L);
			snapshot.setUserId(7L);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			store.save(snapshot);
		}
		ResumeService resumeService = mock(ResumeService.class);
		AtomicReference<TenantUser> observed = new AtomicReference<>();
		when(resumeService.resume(eq(RUN_ID))).thenAnswer(invocation -> {
			observed.set(RequestContextUtils.currentOrNull());
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of("resumed", true));
		});
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(resumeService);
		RunRegistry registry = mock(RunRegistry.class);
		when(registry.of(RUN_ID)).thenReturn(mock(SseChatEmitter.class));
		Executor triggerPool = Runnable::run;
		ConfirmGate gate = new ConfirmGate(store, registry, new AiProperties(), mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), List.of(), provider, triggerPool, null, null);

		assertThat(RequestContextUtils.currentOrNull()).as("前置：派发线程模拟 HTTP/定时线程，无身份").isNull();

		ConfirmGate.ResumeTrigger trigger = gate.triggerResumeForParked(RUN_ID);

		assertThat(trigger.triggered()).isTrue();
		assertThat(observed.get()).as("触发池里的续跑入口必须带本轮身份").isEqualTo(new TenantUser(9001L, 7L));
		assertThat(RequestContextUtils.currentOrNull()).as("派发结束不得在调用线程残留身份").isNull();
	}

}
