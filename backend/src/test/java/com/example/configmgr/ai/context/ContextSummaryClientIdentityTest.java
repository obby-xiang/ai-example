package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要旁路请求的身份恢复（租户任务3）：摘要在独立的有界线程池上执行，池线程没有请求身份。
 *
 * <p>被钉住的形态：摘要的模型调用发生在本轮上下文中（驱动/续跑线程建立的作用域），
 * 池线程必须按提交时的身份重建作用域 —— 否则旁路请求在"无身份"或"上一位调用者"身份下执行。
 */
class ContextSummaryClientIdentityTest {

	@Test
	void summaryModelCallRunsWithTheSubmittingTurnIdentity() throws Exception {
		AtomicReference<TenantUser> observed = new AtomicReference<>();
		ContextSummaryClient client = new ContextSummaryClient(new AiProperties(),
				(systemPrompt, userPrompt, runtime) -> {
					observed.set(RequestContextUtils.currentOrNull());
					return ContextSummaryClient.SUMMARY_OPEN + "摘要" + ContextSummaryClient.SUMMARY_CLOSE;
				});

		String summary;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			summary = client.summarize("sys", "user", null);
		}
		finally {
			client.close();
		}

		assertThat(summary).isEqualTo("摘要");
		assertThat(observed.get()).as("摘要池线程必须带提交时的本轮身份").isEqualTo(new TenantUser(9001L, 7L));
		assertThat(RequestContextUtils.currentOrNull()).as("调用线程作用域关闭后不得残留身份").isNull();
	}

}
