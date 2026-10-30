package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B23L1 批②b：<b>读侧触发的挂起 TTL 续期</b>在 HTTP 入口上的接线（方案第 5 条）。
 *
 * <h2>为什么续期搬到读侧</h2>
 * 挂起线程不再被假设一定活着（挂起在 A、读取/决策落在 B）：若只靠 A 的心跳续期，
 * A 一死，卡的后台数据（快照/待决/归档/序号/req/decided）就开始倒计时，
 * 而用户可能正在另一个实例上操作。于是"任何读/写入口都 touch 一次"成为新的保活口径
 * （{@code RunStore#touchActiveRun} 自带终态判据：终态/缺失不续期）。
 *
 * <h2>三条触发点 + 一条负例</h2>
 * <ol>
 * <li>T1 {@code GET /runs/current}：取到快照后、组装 body 前 touch 一次（快照重载，省一次读）；</li>
 * <li>T2 决策写入 {@code POST /confirm} 与 {@code POST /frontend-tool-result}：<b>先续命再落决策</b>；</li>
 * <li>T3 {@code GET /events/{runId}}（reattach）：{@code get} 成功后 touch 一次；</li>
 * <li>T4 快照缺失（索引残留/已删除）⇒ <b>不</b> touch（读侧不得养出孤儿键）。</li>
 * </ol>
 */
class AiControllerSuspendedTouchTest {

	private static final String RUN_ID = "r-touch";

	private static final String SESSION_ID = "s-touch";

	private static final String TOOL_CALL_ID = "c-touch";

	private final RunStore runStore = mock(RunStore.class);

	private final ConfirmGate confirmGate = mock(ConfirmGate.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	private RequestContextUtils.SafeCloseable identityScope;

	@BeforeEach
	void openIdentityScope() {
		// 这些端点都是 HTTP 数据面入口：生产里必然带身份，测试同样先建立身份作用域
		this.identityScope = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void closeIdentityScope() {
		if (this.identityScope != null) {
			this.identityScope.close();
		}
	}

	// ── T1 /runs/current ───────────────────────────────────────────────────

	@Test
	void runsCurrentTouchesTheSuspendedRunExactlyOnce() throws Exception {
		RunSnapshot snapshot = snapshot(RunSnapshot.SUSPENDED);
		when(this.runStore.sessionCurrentRunId(SESSION_ID)).thenReturn(RUN_ID);
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
		when(this.runStore.pendings(RUN_ID)).thenReturn(List.of());
        when(this.runStore.pendingsLenient(RUN_ID)).thenReturn(List.of());
		when(this.runStore.lastEventSeq(RUN_ID)).thenReturn(0L);

		this.mockMvc.perform(get("/api/ai/runs/current").param("sessionId", SESSION_ID))
			.andExpect(status().isOk());

		verify(this.runStore, times(1)).touchActiveRun(snapshot);
	}

	@Test
	void runsCurrentDoesNotTouchWhenTheSnapshotIsGone() throws Exception {
		when(this.runStore.sessionCurrentRunId(SESSION_ID)).thenReturn(RUN_ID);
		when(this.runStore.get(RUN_ID)).thenReturn(null);

		this.mockMvc.perform(get("/api/ai/runs/current").param("sessionId", SESSION_ID))
			.andExpect(status().isOk());

		verify(this.runStore, never()).touchActiveRun(any(RunSnapshot.class));
		verify(this.runStore, never()).touchActiveRun(any(String.class));
	}

	// ── T2 决策写入 ────────────────────────────────────────────────────────

	@Test
	void confirmTouchesBeforePersistingTheDecision() throws Exception {
		stubAcceptedDecision();

		this.mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content("{\"runId\":\"" + RUN_ID + "\",\"toolCallId\":\"" + TOOL_CALL_ID + "\",\"approved\":true}"))
			.andExpect(status().isOk());

		verify(this.runStore, times(1)).touchActiveRun(RUN_ID);
	}

	@Test
	void frontendToolResultTouchesBeforePersistingTheResult() throws Exception {
		stubAcceptedDecision();

		this.mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content("{\"runId\":\"" + RUN_ID + "\",\"toolCallId\":\"" + TOOL_CALL_ID
					+ "\",\"result\":\"{\\\"ok\\\":true}\"}"))
			.andExpect(status().isOk());

		verify(this.runStore, times(1)).touchActiveRun(RUN_ID);
	}

	// ── T3 reattach ────────────────────────────────────────────────────────

	@Test
	void reattachTouchesTheSuspendedRunOnce() throws Exception {
		RunSnapshot snapshot = snapshot(RunSnapshot.SUSPENDED);
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
		when(this.runRegistry.of(RUN_ID)).thenReturn(this.emitter);

		this.mockMvc.perform(get("/api/ai/events/{runId}", RUN_ID)).andExpect(status().isOk());

		verify(this.runStore, times(1)).touchActiveRun(snapshot);
	}

	@Test
	void reattachDoesNotTouchAnUnknownRun() throws Exception {
		when(this.runStore.get("nope")).thenReturn(null);

		this.mockMvc.perform(get("/api/ai/events/{runId}", "nope")).andExpect(status().isNotFound());

		verify(this.runStore, never()).touchActiveRun(any(RunSnapshot.class));
		verify(this.runStore, never()).touchActiveRun(any(String.class));
	}

	// ───────────────────────── helpers ─────────────────────────

	private void stubAcceptedDecision() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setStatus(PendingToolCall.APPROVED);
		pending.setReason("同意");
		ConfirmGate.Submission accepted = new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, false,
				null, ConfirmGate.ResumeTrigger.notApplicable());
		when(this.confirmGate.submitDecision(any(), any(), any(Boolean.class), any())).thenReturn(accepted);
		when(this.confirmGate.submitFrontendResult(any(), any(), any(), any(), any(Boolean.class), any()))
			.thenReturn(accepted);
		when(this.runStore.instanceId()).thenReturn("inst-test");
	}

	private static RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setStatus(status);
		return snapshot;
	}

	private AiController controller() {
		AiController built = new AiController(mock(AiAvailability.class), provider(), provider(), provider(), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), mock(SessionGate.class), this.confirmGate, this.runStore,
				this.runRegistry, mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		// N5：/confirm 的 reason 归一化复用与 /chat 同一个 InputSanitizer（字段注入，standalone 需手填）
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
