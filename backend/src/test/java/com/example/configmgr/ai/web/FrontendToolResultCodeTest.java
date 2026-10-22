package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批 A · A-14：{@code POST /api/ai/frontend-tool-result} 的可选 {@code code}（additive）。
 *
 * <p>
 * 口径：前端可以上报 {@code UI_*} 机器码，但<b>入口只认白名单形态</b>
 * （{@code ^UI_[A-Z_]{2,40}$}）。非法值（小写 / 含空格 / 超 40 字符 / 非 UI 前缀）
 * <b>丢弃不报 400</b> —— 回灌的成功判定不能被一个诊断字段拖垮，结局仍按状态推导。
 *
 * <p><b>证红</b>：把校验改成"非法即 400" ⇒ 非法值用例红；把合法码丢掉不上传 ⇒ 合法码用例红。
 */
class FrontendToolResultCodeTest {

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void illegalCodesAreDroppedWithHttp200() throws Exception {
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", "ok", "http-post", false, null))
			.thenReturn(accepted());

		for (String illegal : new String[] { "ui_lower", "UI HAS SPACE", "UI_" + "A".repeat(41), "ERROR_CODE" }) {
			mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
				.content(body("r-1", "c-1", "ok", illegal)))
				.andExpect(status().isOk());
		}

		// 全部按"没有 code"处理：非法值不进确认门、不进台账
		verify(this.gate, org.mockito.Mockito.times(4))
			.submitFrontendResult("r-1", "c-1", "ok", "http-post", false, null);
	}

	@Test
	void legalCodeIsForwardedAdditively() throws Exception {
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", "ok", "http-post", false, null, java.util.Map.of(),
				"UI_PAGE_NOT_READY")).thenReturn(accepted());

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", "ok", "UI_PAGE_NOT_READY")))
			.andExpect(status().isOk());

		verify(this.gate).submitFrontendResult("r-1", "c-1", "ok", "http-post", false, null, java.util.Map.of(),
				"UI_PAGE_NOT_READY");
	}

	@Test
	void sanitizerIsTheSingleJudge() {
		assertThat(FrontendToolResultRequest.normalizeCode("UI_PAGE_NOT_READY")).isEqualTo("UI_PAGE_NOT_READY");
		assertThat(FrontendToolResultRequest.normalizeCode("UI_REF_NOT_SNAPSHOTTED"))
			.isEqualTo("UI_REF_NOT_SNAPSHOTTED");
		assertThat(FrontendToolResultRequest.normalizeCode("ui_lower")).isNull();
		assertThat(FrontendToolResultRequest.normalizeCode("UI HAS SPACE")).isNull();
		assertThat(FrontendToolResultRequest.normalizeCode("UI_" + "A".repeat(41))).isNull();
		assertThat(FrontendToolResultRequest.normalizeCode("")).isNull();
		assertThat(FrontendToolResultRequest.normalizeCode(null)).isNull();
	}

	private String body(String runId, String toolCallId, String result, String code) throws Exception {
		return this.objectMapper.writeValueAsString(
				new FrontendToolResultRequest(runId, toolCallId, result, "http-post", false, null, null, code));
	}

	private static ConfirmGate.Submission accepted() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("navigate_to");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		return new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null);
	}

	private AiController controller() {
		return new AiController(mock(AiAvailability.class), provider(), provider(), provider(), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate, this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), this.objectMapper, new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}
}
