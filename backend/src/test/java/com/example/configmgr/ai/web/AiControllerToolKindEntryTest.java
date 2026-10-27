package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口 kind 校验（第 6 项）：{@code /confirm} 只收 {@code CONFIRM}，
 * {@code /frontend-tool-result} 只收 {@code FRONTEND}。
 *
 * <h2>为什么必须有这一层</h2>
 * 两条入口写的是<b>同一张</b> {@code ai:pending} 表：前端若把确认卡的 toolCallId 发到回灌端点
 * （或反过来），旧代码会照单全收 —— 确认门条目被写成 {@code FRONTEND_RESULT}（人根本没点过），
 * 或前端工具条目被写成 {@code APPROVED}（续跑时被当成"已批准"去执行后端桩）。
 * 判据只能取<b>服务端条目</b>上的 {@code kind}（客户端传什么都不信）。
 *
 * <h2>本类锁四格矩阵 + 三条兼容口径</h2>
 * <ol>
 * <li>CONFIRM × 确认门 / FRONTEND × 回灌 = 正常（闸门被调）；</li>
 * <li>FRONTEND × 确认门 / CONFIRM × 回灌 = <b>409 TOOL_KIND_MISMATCH</b>，闸门<b>从未被调</b>；</li>
 * <li>旧数据 {@code kind=null} = 放行（不在入口拒绝，交给闸门的兼容口径）；</li>
 * <li>正确 kind 的重复提交 = 仍 {@code 409 DUPLICATE_TOOL_CALL_ID}；</li>
 * <li>入口预读降级（拿不到条目）时，闸门返回的 mismatch 结局同样映射成 409（双保险）。</li>
 * </ol>
 */
class AiControllerToolKindEntryTest {

	private static final String RUN = "r-kind";

	private static final String CALL = "c-kind";

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final AiController controller = controller();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

	// ── 四格矩阵：两格正常 ────────────────────────────────────────────────

	@Test
	void confirmEntryIsAcceptedByTheConfirmEndpoint() throws Exception {
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		verify(this.gate).submitDecision(RUN, CALL, true, null);
	}

	@Test
	void frontendEntryIsAcceptedByTheFrontendEndpoint() throws Exception {
		PendingToolCall pending = pending(PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any()))
			.thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(frontendBody("ok", false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		verify(this.gate).submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any());
	}

	// ── 四格矩阵：两格错入口 ──────────────────────────────────────────────

	@Test
	void frontendEntryOnTheConfirmEndpointIsKindMismatch() throws Exception {
		when(this.runStore.pendingLenient(RUN, CALL))
			.thenReturn(pending(PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING));
		when(this.runStore.instanceId()).thenReturn("inst-a");

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOOL_KIND_MISMATCH"))
			.andExpect(jsonPath("$.kind").value(PendingToolCall.KIND_FRONTEND))
			.andExpect(jsonPath("$.expectedKind").value(PendingToolCall.KIND_CONFIRM))
			.andExpect(jsonPath("$.status").value(PendingToolCall.PENDING))
			.andExpect(jsonPath("$.accepted").value(false))
			.andExpect(jsonPath("$.name").value("generative_form"));

		// 错入口不碰闸门：不落决策、不发帧、不唤醒
		verify(this.gate, never()).submitDecision(anyString(), anyString(), anyBoolean(), any());
		verify(this.runStore, never()).putPending(anyString(), any());
	}

	@Test
	void confirmEntryOnTheFrontendEndpointIsKindMismatch() throws Exception {
		when(this.runStore.pendingLenient(RUN, CALL))
			.thenReturn(pending(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING));
		when(this.runStore.instanceId()).thenReturn("inst-a");

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(frontendBody("ok", false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOOL_KIND_MISMATCH"))
			.andExpect(jsonPath("$.kind").value(PendingToolCall.KIND_CONFIRM))
			.andExpect(jsonPath("$.expectedKind").value(PendingToolCall.KIND_FRONTEND));

		verify(this.gate, never()).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(),
				any());
		verify(this.runStore, never()).putPending(anyString(), any());
	}

	/** 放弃（cancelled=true）也要过 kind 闸：它写的是同一条 FRONTEND_CANCELLED 终态。 */
	@Test
	void cancelledFrontendSubmissionOnAConfirmEntryIsStillKindMismatch() throws Exception {
		when(this.runStore.pendingLenient(RUN, CALL))
			.thenReturn(pending(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING));
		when(this.runStore.instanceId()).thenReturn("inst-a");

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(frontendBody(null, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOOL_KIND_MISMATCH"));

		verify(this.gate, never()).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(),
				any());
	}

	// ── 兼容口径 ──────────────────────────────────────────────────────────

	/** 旧数据（kind 字段引入前写入）：不在入口拒绝，交给闸门的兼容口径（放行 + WARN）。 */
	@Test
	void legacyEntryWithoutKindPassesBothEntrypoints() throws Exception {
		PendingToolCall legacy = pending(null, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(legacy);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(eq(RUN), eq(CALL), eq(true), any())).thenReturn(accepted(legacy));
		when(this.gate.submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any()))
			.thenReturn(accepted(legacy));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(frontendBody("ok", false)))
			.andExpect(status().isOk());

		verify(this.gate).submitDecision(eq(RUN), eq(CALL), eq(true), any());
		verify(this.gate).submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any());
	}

	/** 正确 kind 的重复提交：kind 闸先放行，幂等口径照旧（409 DUPLICATE_TOOL_CALL_ID）。 */
	@Test
	void correctKindDuplicatesKeepDuplicateSemantics() throws Exception {
		PendingToolCall decided = pending(PendingToolCall.KIND_CONFIRM, PendingToolCall.APPROVED);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(decided);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(eq(RUN), eq(CALL), eq(true), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.DUPLICATE, decided, false, null));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_TOOL_CALL_ID"));

		PendingToolCall frontendDecided = pending(PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(frontendDecided);
		when(this.gate.submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.DUPLICATE, frontendDecided, false, null));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(frontendBody("ok", false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_TOOL_CALL_ID"));
	}

	/**
	 * 双保险：入口预读降级（拿不到条目）时，闸门自己的 mismatch 结局也必须映射成 409，
	 * 而不是落进"已受理"的 200 分支。
	 */
	@Test
	void gateKindMismatchOutcomeIsMappedEvenWhenThePreReadIsUnavailable() throws Exception {
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(null);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(eq(RUN), eq(CALL), eq(true), any()))
			.thenReturn(new ConfirmGate.Submission(
					ConfirmGate.Outcome.valueOf("TOOL_KIND_MISMATCH"),
					pending(PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING), false, null));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOOL_KIND_MISMATCH"))
			.andExpect(jsonPath("$.kind").value(PendingToolCall.KIND_FRONTEND))
			.andExpect(jsonPath("$.expectedKind").value(PendingToolCall.KIND_CONFIRM));
	}

	/** 未知 toolCallId 仍是既有 UNKNOWN_TOOL_CALL（kind 闸不得把它吞成 mismatch）。 */
	@Test
	void unknownEntryStillReportsUnknownToolCall() throws Exception {
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(null);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(eq(RUN), eq(CALL), eq(true), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.NOT_FOUND, null, false, null));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("UNKNOWN_TOOL_CALL"));
	}

	/**
	 * N8（2026-10-26 批次 6C，只写不跑）：未知/过期条目的用户可见文案不打印内部 ID；
	 * {@code code}/{@code runId}/{@code toolCallId} 仍是结构字段（前端按码把卡收敛成失效态，
	 * 排障按 id 对账）。
	 */
	@Test
	void unknownToolCallMessageHidesInternalIdsButKeepsStructuredFields() throws Exception {
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(null);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(eq(RUN), eq(CALL), eq(true), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.NOT_FOUND, null, false, null));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("UNKNOWN_TOOL_CALL"))
			.andExpect(jsonPath("$.runId").value(RUN))
			.andExpect(jsonPath("$.toolCallId").value(CALL))
			.andExpect(jsonPath("$.message").value("这轮对话已失效，无法再处理这个操作；请重新提问。"));
	}

	/** 非中文 locale 取 {@code messages_en} 的码级文案（双语补齐的可执行证据）。 */
	@Test
	void englishLocaleRendersTheRegisteredMessage() throws Exception {
		LocaleContextHolder.setLocale(Locale.US);
		try {
			AiController localized = controller();
			ReflectionTestUtils.setField(localized, "errorBodyFactory", factory());
			MockMvc localizedMvc = MockMvcBuilders.standaloneSetup(localized).build();
			when(this.runStore.pendingLenient(RUN, CALL))
				.thenReturn(pending(PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING));
			when(this.runStore.instanceId()).thenReturn("inst-a");

			localizedMvc
				.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
					.content(confirmBody(true)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TOOL_KIND_MISMATCH"))
				.andExpect(jsonPath("$.message").value(
						"This tool call belongs to the other entry point; use the matching endpoint"));
		}
		finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

	// ── reason 归一化 + 长度闸（N5） ────────────────────────────────────────

	/**
	 * N5：{@code reason} 必须先归一化，再按<b>归一后</b>的文本过 1024 长度闸；落进 pending 并回灌
	 * 模型的也是归一后的那份。
	 *
	 * <p>前半段：原文 1028 个字符（1024 个全角 {@code Ａ} —— NFKC 后是等长的半角 {@code A} —— 外加
	 * 零宽/控制填充）。量原文 ⇒ 400；量归一结果（恰好 1024）⇒ 必须受理，且交给确认门的只有
	 * {@code A×1024}。后半段：归一后 1025 ⇒ 仍按归一后长度 400，且不再进闸门。
	 */
	@Test
	void confirmReasonIsNormalizedBeforeTheLengthGate() throws Exception {
		String normalized = "A".repeat(AiController.REASON_MAX_CHARS);
		String raw = "Ａ".repeat(AiController.REASON_MAX_CHARS) + "\u200B\u200C\uFEFF\u0007";
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, normalized)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true, raw)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		verify(this.gate).submitDecision(RUN, CALL, true, normalized);

		String tooLongRaw = "Ａ".repeat(AiController.REASON_MAX_CHARS + 1) + "\u200B";
		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(true, tooLongRaw)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_TOO_LONG"))
			.andExpect(jsonPath("$.length").value(AiController.REASON_MAX_CHARS + 1))
			.andExpect(jsonPath("$.limit").value(AiController.REASON_MAX_CHARS));

		verify(this.gate, times(1)).submitDecision(anyString(), anyString(), anyBoolean(), any());
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private String confirmBody(boolean approved) throws Exception {
		return confirmBody(approved, null);
	}

	private String confirmBody(boolean approved, String reason) throws Exception {
		return this.objectMapper.writeValueAsString(new ConfirmRequest(RUN, CALL, approved, reason));
	}

	private String frontendBody(String result, boolean cancelled) throws Exception {
		return this.objectMapper.writeValueAsString(
				new FrontendToolResultRequest(RUN, CALL, result, null, cancelled, null, null, null));
	}

	private static PendingToolCall pending(String kind, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL);
		pending.setName("generative_form");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(kind);
		pending.setStatus(status);
		return pending;
	}

	private static ConfirmGate.Submission accepted(PendingToolCall pending) {
		return new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null);
	}

	private static com.example.configmgr.common.ErrorBodyFactory factory() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
		return new com.example.configmgr.common.ErrorBodyFactory(
				beans.getBeanProvider(org.springframework.context.MessageSource.class));
	}

	private AiController controller() {
		AiController built = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate,
				this.runStore, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.objectMapper,
				new AiProperties(), mock(Environment.class),
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
