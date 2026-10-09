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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2b-D#2：{@code GET /api/ai/runs/current?sessionId=} 的 HTTP 契约。
 *
 * <p>三分支语义（红队 D1）：已超时 → 条目 {@code entryStatus=TIMEOUT}；他端已提交 →
 * {@code entryStatus=FRONTEND_RESULT}；进程已死 → 索引键 TTL 过期缺失 → 明确空态。
 * {@code awaitingExternal} 按 {@code ResumeService} 的 unresolvedExternal 口径
 * （PENDING 且非 BACKEND）计算 —— 快照 SUSPENDED 但条目全决的中间态必须为 false。
 */
class AiControllerRunsCurrentTest {

	private final RunStore runStore = mock(RunStore.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void missingSessionIdIsBadRequest() throws Exception {
		mockMvc.perform(get("/api/ai/runs/current"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "  "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void missingIndexReturnsExplicitEmptyState() throws Exception {
		when(this.runStore.sessionCurrentRunId("s-1")).thenReturn(null);

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.found").value(false))
			.andExpect(jsonPath("$.data.runId").doesNotExist())
			.andExpect(jsonPath("$.data.snapshotStatus").doesNotExist())
			.andExpect(jsonPath("$.data.pendingEntries").isEmpty())
			.andExpect(jsonPath("$.data.unresolvedExternal").isEmpty())
			.andExpect(jsonPath("$.data.awaitingExternal").value(false))
			.andExpect(jsonPath("$.data.archiveMaxSeq").value(0));
	}

	@Test
	void staleIndexWithMissingSnapshotReturnsEmptyState() throws Exception {
		// 索引残留边界：索引在但快照已消失，同样落明确空态
		when(this.runStore.sessionCurrentRunId("s-1")).thenReturn("r-1");
		when(this.runStore.get("r-1")).thenReturn(null);

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.found").value(false));
	}

	@Test
	void suspendedWithPendingFrontendEntryIsAwaitingExternal() throws Exception {
		seedIndex("s-1", "r-1", RunSnapshot.SUSPENDED, List.of(pending("c-1", PendingToolCall.PENDING,
				PendingToolCall.KIND_FRONTEND)));
		when(this.runStore.lastEventSeq("r-1")).thenReturn(42L);

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.found").value(true))
			.andExpect(jsonPath("$.data.runId").value("r-1"))
			.andExpect(jsonPath("$.data.snapshotStatus").value(RunSnapshot.SUSPENDED))
			.andExpect(jsonPath("$.data.pendingEntries[0].toolCallId").value("c-1"))
			.andExpect(jsonPath("$.data.pendingEntries[0].entryStatus").value(PendingToolCall.PENDING))
			.andExpect(jsonPath("$.data.pendingEntries[0].kind").value(PendingToolCall.KIND_FRONTEND))
			.andExpect(jsonPath("$.data.pendingEntries[0].name").value("generative_form"))
			.andExpect(jsonPath("$.data.pendingEntries[0].arguments").value("{}"))
			.andExpect(jsonPath("$.data.unresolvedExternal[0]").value("c-1"))
			.andExpect(jsonPath("$.data.awaitingExternal").value(true))
			.andExpect(jsonPath("$.data.archiveMaxSeq").value(42));
	}

	@Test
	void timedOutEntryIsReportedAsTimeout() throws Exception {
		// 分支一：已超时 —— 等待循环把条目置 TIMEOUT， awaitingExternal 必须翻成 false
		seedIndex("s-1", "r-1", RunSnapshot.SUSPENDED, List.of(pending("c-1", PendingToolCall.TIMEOUT,
				PendingToolCall.KIND_CONFIRM)));

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pendingEntries[0].entryStatus").value(PendingToolCall.TIMEOUT))
			.andExpect(jsonPath("$.data.unresolvedExternal").isEmpty())
			.andExpect(jsonPath("$.data.awaitingExternal").value(false));
	}

	@Test
	void decidedEntryDisambiguatesSuspendedSnapshot() throws Exception {
		// 分支二 + 红队 D1：快照仍 SUSPENDED 但条目已被他端提交（FRONTEND_RESULT）——
		// 中间态不得误判为"仍在等待外部输入"
		seedIndex("s-1", "r-1", RunSnapshot.SUSPENDED, List.of(pending("c-1", PendingToolCall.FRONTEND_RESULT,
				PendingToolCall.KIND_FRONTEND)));

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pendingEntries[0].entryStatus").value(PendingToolCall.FRONTEND_RESULT))
			.andExpect(jsonPath("$.data.awaitingExternal").value(false));
	}

	@Test
	void backendPendingEntryDoesNotCountAsUnresolvedExternal() throws Exception {
		// unresolvedExternal 口径（对齐 ResumeService）：BACKEND 条目即便 PENDING 也不计入
		seedIndex("s-1", "r-1", RunSnapshot.SUSPENDED, List.of(pending("c-1", PendingToolCall.PENDING,
				PendingToolCall.KIND_BACKEND)));

		mockMvc.perform(get("/api/ai/runs/current").param("sessionId", "s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pendingEntries[0].entryStatus").value(PendingToolCall.PENDING))
			.andExpect(jsonPath("$.data.unresolvedExternal").isEmpty())
			.andExpect(jsonPath("$.data.awaitingExternal").value(false));
	}

	private void seedIndex(String sessionId, String runId, String status, List<PendingToolCall> pendings) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(status);
		when(this.runStore.sessionCurrentRunId(sessionId)).thenReturn(runId);
		when(this.runStore.get(runId)).thenReturn(snapshot);
		when(this.runStore.pendings(runId)).thenReturn(pendings);
		when(this.runStore.lastEventSeq(runId)).thenReturn(0L);
	}

	private static PendingToolCall pending(String toolCallId, String status, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("generative_form");
		pending.setKind(kind);
		pending.setArguments("{}");
		pending.setStatus(status);
		return pending;
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求、不读 Redis）。 */
	private AiController controller() {
		return new AiController(mock(AiAvailability.class), provider(), provider(), provider(), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), mock(SessionGate.class), mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
