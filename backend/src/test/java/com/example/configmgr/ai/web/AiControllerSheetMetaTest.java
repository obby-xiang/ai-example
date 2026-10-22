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
import com.example.configmgr.ai.tool.SheetAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/ai/frontend-tool-result} 的 {@code sheetMeta} 透传（AI 表格工具治理批 §4）。
 *
 * <p>两件事必须一起成立：
 * <ol>
 * <li><b>带 sheetMeta 时</b>走七参重载；不带时仍走六参（旧调用方与既有桩的实参形状逐字不变）；</li>
 * <li>进闸门的 meta 已经过白名单（业务值在<b>入口</b>就被丢掉，不是靠下游自觉）。</li>
 * </ol>
 */
class AiControllerSheetMetaTest {

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void sheetMetaIsSanitizedAndForwardedToTheGate() throws Exception {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("read_sheet_summary");
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(eq("r-1"), eq("c-1"), anyString(), any(), eq(false), any(),
				any())).thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null));

		String body = this.objectMapper.writeValueAsString(Map.of(
				"runId", "r-1",
				"toolCallId", "c-1",
				"result", "共 5 行，本次回 5 行",
				"sheetMeta", Map.of(
						"dataClass", "D2",
						"sampleRows", 5,
						"totalRows", 3120,
						"defCode", "CURRENCY",
						"cellValue", "华东-业务值")));

		this.mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
		verify(this.gate).submitFrontendResult(eq("r-1"), eq("c-1"), anyString(), any(), eq(false), any(),
				meta.capture());
		assertThat(meta.getValue()).containsEntry("dataClass", "D2")
			.containsEntry("sampleRows", 5L)
			.containsEntry("totalRows", 3120L)
			.containsEntry("defCode", "CURRENCY")
			// 明令禁止的字段在入口就被丢掉（白名单而非黑名单）
			.doesNotContainKey("cellValue");
		assertThat(meta.getValue()).isEqualTo(SheetAudit.sanitize(meta.getValue()));
		assertThat(meta.getValue().toString()).doesNotContain("华东");
	}

	@Test
	void requestWithoutSheetMetaKeepsTheSixArgPath() throws Exception {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("read_sheet_summary");
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(eq("r-1"), eq("c-1"), anyString(), any(), eq(false), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null));

		String body = this.objectMapper.writeValueAsString(Map.of(
				"runId", "r-1", "toolCallId", "c-1", "result", "共 1 行，本次回 1 行"));

		this.mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());

		verify(this.gate).submitFrontendResult(eq("r-1"), eq("c-1"), anyString(), any(), eq(false), any());
	}

	@SuppressWarnings("unchecked")
	private AiController controller() {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		AiController built = new AiController(availability, provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate,
				this.runStore, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.objectMapper,
				new AiProperties(), mock(Environment.class), mock(com.example.configmgr.job.service.JobWatchdog.class));
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
