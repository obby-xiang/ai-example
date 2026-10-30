package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>没有身份上下文时归属复核必须 fail-closed</b>（只带 runId 的端点）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code ownedByCurrentIdentity} 原来在"没有身份上下文"时直接 {@code return true}：理由是"单测 /
 * 非 HTTP 入口由键面兜底"。但无身份线程读到的正是 <b>legacy 命名空间</b>（{@code ai:run:{run:<id>}}），
 * 于是"无上下文"＝"放行一切 legacy 数据"，旧快照（{@code tenantId=0/userId=0}）可以进入新的读/写
 * 路径 —— 归属复核形同虚设。
 *
 * <h2>本组口径</h2>
 * 没有上下文 = 无法证明归属 ⇒ 一律按<b>不存在</b>回答：明细 {@code exists=false}、取消 404 且
 * <b>零副作用</b>（不写取消旗标）。身份只能来自显式作用域（HTTP 头 / {@code RunIdentityScope}），
 * 不从"没上下文"推导出"是我的"。
 */
class AiControllerOwnershipWithoutContextTest {

	private static final String RUN_ID = "run-legacy-no-context";

	private final RunStore store = mock(RunStore.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		when(this.store.instanceId()).thenReturn("test-instance");
		when(this.resumeService.describe(RUN_ID)).thenReturn(Map.of("exists", true, "runId", RUN_ID));
		when(this.cancellations.status(RUN_ID)).thenReturn(Map.of());
		when(this.cancellations.cancel(RUN_ID, "test-instance"))
			.thenReturn(new CancellationRegistry.CancelResult(true, false, RUN_ID));
		AiController controller = new AiController(mock(AiAvailability.class),
				providerOf((ResilientChatService) null), providerOf(this.resumeService),
				providerOf((StartupResumeRunner) null), mock(ChatMemory.class), mock(ChatMemoryRepository.class),
				mock(SessionGate.class), mock(ConfirmGate.class), this.store, mock(RunRegistry.class),
				this.cancellations, mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class),
				new ObjectMapper(), new AiProperties(), mock(Environment.class), mock(JobWatchdog.class));
		this.mvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void runDetailDoesNotAdoptALegacySnapshotWithoutIdentityContext() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(legacySnapshot());

		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			this.mvc.perform(get("/api/ai/runs/{runId}", RUN_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.exists").value(false));
		}

		verify(this.resumeService, never()).describe(anyString());
	}

	@Test
	void cancelDoesNotAdoptALegacySnapshotWithoutIdentityContext() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(legacySnapshot());

		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			this.mvc.perform(post("/api/ai/cancel/{runId}", RUN_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ErrorCodes.UNKNOWN_RUN))
				.andExpect(jsonPath("$.cancelled").value(false));
		}

		verify(this.cancellations, never()).cancel(anyString(), anyString());
	}

	private static RunSnapshot legacySnapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-legacy-no-context");
		snapshot.setTenantId(0L);
		snapshot.setUserId(0L);
		snapshot.setStatus(RunSnapshot.RUNNING);
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
