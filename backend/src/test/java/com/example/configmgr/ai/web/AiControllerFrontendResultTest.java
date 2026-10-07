package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.FrontendToolGuard;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DC-15 用例⑥：{@code POST /api/ai/frontend-tool-result} 的 <b>HTTP 契约</b>。
 *
 * <p>回灌被安全闸拒绝时，前端拿到的是可编程处理的响应：
 * <b>400</b> + 机器可读码（如 {@code FORM_RESULT_REJECTED}）+ 逐条原因 + "挂起仍在等待"的明确状态
 * —— 而不是一个含糊的 500 或静默忽略。取消（{@code cancelled:true}）在同一端点上报，老客户端
 * 不带该字段时行为不变。
 *
 * <p>用 standalone MockMvc + 桩 {@link ConfirmGate}：本用例只锁"结局码 → HTTP 状态/体"的映射，
 * 闸门本身的判定在 {@code GenerativeFormGateTest} 里用真实实现验证（那里不需要 Redis）。
 */
class AiControllerFrontendResultTest {

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void rejectedResubmissionIs400WithCodeAndReasons() throws Exception {
		PendingToolCall pending = pending(PendingToolCall.PENDING);
		FrontendToolGuard.Verdict verdict = FrontendToolGuard.Verdict.reject(GenerativeFormRules.RESULT_REJECTED,
				"回灌的表单结果未通过类型复核，本次回灌被拒（挂起仍在等待，可修正后重试）",
				List.of("字段 keyword 期望 text（字符串），实际是数字"));
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", "{\"keyword\":7}", "frontend-form", false))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.REJECTED, pending, false, verdict));

		mockMvc.perform(post("/api/ai/frontend-tool-result")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", "{\"keyword\":7}", "frontend-form", false)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(GenerativeFormRules.RESULT_REJECTED))
			.andExpect(jsonPath("$.accepted").value(false))
			.andExpect(jsonPath("$.duplicate").value(false))
			.andExpect(jsonPath("$.status").value(PendingToolCall.PENDING))
			.andExpect(jsonPath("$.name").value("generative_form"))
			.andExpect(jsonPath("$.reasons[0]").value(containsString("keyword")))
			.andExpect(jsonPath("$.note").value(containsString("cancelled=true")));
	}

	@Test
	void cancellationIsForwardedAndReportedAsTerminalState() throws Exception {
		PendingToolCall pending = pending(PendingToolCall.FRONTEND_CANCELLED);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", null, "frontend-form", true))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null));

		mockMvc.perform(post("/api/ai/frontend-tool-result")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", null, "frontend-form", true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true))
			.andExpect(jsonPath("$.cancelled").value(true))
			.andExpect(jsonPath("$.status").value(PendingToolCall.FRONTEND_CANCELLED))
			.andExpect(jsonPath("$.wokeInProcessGate").value(true));
	}

	@Test
	void unknownAndDuplicateSubmissionsKeepThe409Semantics() throws Exception {
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", "{\"keyword\":\"CNY\"}", null, false))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.DUPLICATE,
					pending(PendingToolCall.FRONTEND_RESULT), false, null));
		when(this.gate.submitFrontendResult("r-2", "c-2", "{\"keyword\":\"CNY\"}", null, false))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.NOT_FOUND, null, false, null));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", "{\"keyword\":\"CNY\"}", null, false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_TOOL_CALL_ID"));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-2", "c-2", "{\"keyword\":\"CNY\"}", null, false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("UNKNOWN_TOOL_CALL"));
	}

	@Test
	void missingIdsAreStillABadRequest() throws Exception {
		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	private String body(String runId, String toolCallId, String result, String source, boolean cancelled)
			throws Exception {
		return this.objectMapper.writeValueAsString(
				new FrontendToolResultRequest(runId, toolCallId, result, source, cancelled));
	}

	private static PendingToolCall pending(String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("generative_form");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(status);
		return pending;
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求、不读 Redis）。 */
	private AiController controller() {
		return new AiController(mock(AiAvailability.class), provider(), provider(), provider(), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate, this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), this.objectMapper, new AiProperties(), mock(Environment.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
