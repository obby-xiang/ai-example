package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取消收尾交棒的身份恢复（租户任务3）：{@link CancelledRunFinalizer} 跑在 {@code aiResumeTrigger}
 * 或自己的重试调度线程上，那里没有请求身份。
 *
 * <p>被钉住的形态：交棒执行体按本轮快照建立作用域后才调 {@code ResumeService.cancelConfirmed}
 * —— 取消收尾看到的身份必须是这一轮的租户/用户（不是 {@code null}、也不是上一位调用者）。
 */
class CancelledRunFinalizerIdentityTest {

	private static final String RUN_ID = "run-cancel-finalizer-identity";

	private static final String SESSION_ID = "s-cancel-finalizer-identity";

	@Test
	void handOffRunsCancelFinalizeWithTheRunIdentity() {
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
		when(resumeService.cancelConfirmed(eq(RUN_ID))).thenAnswer(invocation -> {
			observed.set(RequestContextUtils.currentOrNull());
			return ResumeService.CancelFinalize.FINALIZED;
		});
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(resumeService);
		CancelledRunFinalizer finalizer = new CancelledRunFinalizer(provider, Runnable::run, new AiProperties(),
				store, null);

		assertThat(RequestContextUtils.currentOrNull()).as("前置：交棒线程模拟触发池，无身份").isNull();

		assertThat(finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT)).isTrue();

		assertThat(observed.get()).as("取消收尾交棒必须带本轮身份").isEqualTo(new TenantUser(9001L, 7L));
		assertThat(RequestContextUtils.currentOrNull()).as("交棒结束不得在调用线程残留身份").isNull();
	}

}
