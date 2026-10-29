package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 读侧续期链（2026-10-29 第二轮返修 NEW-1）。
 *
 * <p>park 开启时挂起线程已经交还，心跳不再跑；待决条目靠<b>读侧</b>续命
 * （{@code RunStore#touchActiveRun}）。复核器持有的一次性资源（生成式表单的附件登记）必须挂在
 * <b>同一个位置</b>续期，否则会出现"卡还活着、附件先过期"（用户刷新回来提交，得到
 * {@code ATTACHMENT_NOT_FOUND}，且失效文件没有回收入口）。
 *
 * <p>本类只锁"调用点存在"：四个读侧续期点（{@code /confirm}、{@code /frontend-tool-result}、
 * {@code GET /events/{runId}}、{@code /runs/current}）在与 {@code touchActiveRun} 同一位置调
 * {@link ConfirmGate#touchSuspendedResources}。续期语义本身（只续 PENDING、失败不抛、25 小时仍可提交）
 * 在 {@code GenerativeFormAttachmentConsumeOrderTest} 用真实确认门验证。
 */
class AiControllerSuspensionRenewalTest {

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void runsCurrentRenewsTheSuspendedResourcesAlongWithThePendingEntry() throws Exception {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId("r-1");
		snapshot.setSessionId("s-1");
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		when(this.runStore.sessionCurrentRunId("s-1")).thenReturn("r-1");
		when(this.runStore.get("r-1")).thenReturn(snapshot);
		when(this.runStore.runKeyTtlRemainingMs("r-1")).thenReturn(1_000L);
		when(this.runStore.pendingsLenient("r-1")).thenReturn(List.of());
		when(this.runStore.lastEventSeq("r-1")).thenReturn(0L);

		this.mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1")).andExpect(status().isOk());

		verify(this.runStore).touchActiveRun(snapshot);
		// 与 touchActiveRun 同一位置：刷新是 park 期最强的"还有人看这张卡"信号，附件登记必须同拍续期
		verify(this.gate).touchSuspendedResources("r-1");
	}

	@Test
	void frontendToolResultRenewsTheSuspendedResourcesAlongWithThePendingEntry() throws Exception {
		PendingToolCall pending = pending();
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.gate.submitFrontendResult("r-1", "c-1", "{\"ok\":true}", "frontend", false, null))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null));

		this.mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content("{\"runId\":\"r-1\",\"toolCallId\":\"c-1\",\"result\":\"{\\\"ok\\\":true}\","
					+ "\"source\":\"frontend\",\"cancelled\":false}"))
			.andExpect(status().isOk());

		verify(this.runStore).touchActiveRun("r-1");
		verify(this.gate).touchSuspendedResources("r-1");
	}

	@Test
	void confirmRenewsTheSuspendedResourcesAlongWithThePendingEntry() throws Exception {
		PendingToolCall pending = pending();
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.gate.submitDecision(anyString(), anyString(), anyBoolean(), nullable(String.class)))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null));

		this.mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content("{\"runId\":\"r-1\",\"toolCallId\":\"c-1\",\"approved\":true}")).andExpect(status().isOk());

		verify(this.runStore).touchActiveRun("r-1");
		verify(this.gate).touchSuspendedResources("r-1");
	}

	private static PendingToolCall pending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("generative_form");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求、不读 Redis）。 */
	private AiController controller() {
		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate,
				this.runStore, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.objectMapper,
				new AiProperties(), mock(Environment.class), mock(com.example.configmgr.job.service.JobWatchdog.class));
		// @Autowired 字段注入：standalone 装配里手工补上（/confirm 的 reason 归一化要用）
		org.springframework.test.util.ReflectionTestUtils.setField(controller, "inputSanitizer",
				new com.example.configmgr.ai.gate.InputSanitizer());
		return controller;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
