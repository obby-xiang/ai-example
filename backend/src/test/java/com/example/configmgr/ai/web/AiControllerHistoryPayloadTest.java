package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-144 B-1：{@code GET /api/ai/history/{sessionId}} 的载荷断言（此前零覆盖）。
 *
 * <p>载荷面一律用 {@link ChatMemory} 桩（T5 裁决：history 读的是 ChatMemory，不是 RunStore），
 * 构造模板照抄 {@link AiControllerHistoryDeleteTest}（固定参数表 {@code new AiController(...)}，
 * 不动构造器签名）。断言面四条：
 * <ol>
 * <li>ASSISTANT 条目带 {@code toolCallStates}，逐 {@code toolCallId} 各读其态（一轮多工具、状态各异）；</li>
 * <li>{@code kind} 随同通道透出；</li>
 * <li>旧载荷（metadata 无该键）⇒ 后端不产 {@code toolCallStates} 字段；</li>
 * <li>既有五字段（type/text/toolCalls/toolResponses/timestamp）逐字未变。</li>
 * </ol>
 */
class AiControllerHistoryPayloadTest {

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(this.availability)).build();

	@Test
	void assistantToolCallStatesAreExposedPerToolCallId() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(
					new AssistantMessage.ToolCall("tc-1", "function", "start_publish", "{}"),
					new AssistantMessage.ToolCall("tc-2", "function", "download_export_files", "{}")))
			.properties(Map.of("toolCallStates", Map.of(
					"tc-1", Map.of("status", "EXECUTED", "kind", "CONFIRM"),
					"tc-2", Map.of("status", "BLOCKED", "kind", "FRONTEND"))))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-1.status").value("EXECUTED"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-1.kind").value("CONFIRM"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-2.status").value("BLOCKED"))
			.andExpect(jsonPath("$.data.messages[0].toolCallStates.tc-2.kind").value("FRONTEND"));
	}

	@Test
	void oldPayloadWithoutToolCallStatesProducesNoField() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "start_publish", "{}")))
			.properties(Map.of("spikeTimestamp", "2026-10-12T00:00:00Z"))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].toolCallStates").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[0].text").value(""))
			.andExpect(jsonPath("$.data.messages[0].toolCalls[0].id").value("tc-1"));
	}

	/**
	 * C2：一轮完成后记忆里 {@code role:tool} 的 {@code responseData} 是统一信封 JSON（模型侧形态），
	 * 但 {@code /history} 是回显通道 —— 必须解包成 {@code message} 原文，不能把整段 JSON 甩给前端。
	 */
	@Test
	void envelopeResponseDataIsUnwrappedToPlainMessageInHistory() throws Exception {
		String envelopeJson = com.example.configmgr.ai.tool.ToolResultEnvelope
			.ok("导入作业已启动（作业 #4242）", "{\"taskId\":7}").toJson();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "start_import", envelopeJson)))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("TOOL"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].id").value("tc-1"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].name").value("start_import"))
			.andExpect(jsonPath("$.data.messages[0].toolResponses[0].responseData")
				.value("导入作业已启动（作业 #4242）"));
	}

	@Test
	void existingFiveFieldsAreUnchanged() throws Exception {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "start_publish", "{}")))
			.properties(Map.of("spikeTimestamp", "ts-1"))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("tc-1", "start_publish", "OK")))
			.metadata(Map.of("spikeTimestamp", "ts-2"))
			.build();
		when(this.chatMemory.get("s-1")).thenReturn(List.of(assistant, toolResponse));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
			.andExpect(jsonPath("$.data.messages[0].text").value(""))
			.andExpect(jsonPath("$.data.messages[0].toolCalls[0].id").value("tc-1"))
			.andExpect(jsonPath("$.data.messages[0].timestamp").value("ts-1"))
			.andExpect(jsonPath("$.data.messages[1].type").value("TOOL"))
			.andExpect(jsonPath("$.data.messages[1].toolResponses[0].id").value("tc-1"))
			.andExpect(jsonPath("$.data.messages[1].toolResponses[0].name").value("start_publish"))
			.andExpect(jsonPath("$.data.messages[1].toolResponses[0].responseData").value("OK"))
			.andExpect(jsonPath("$.data.messages[1].timestamp").value("ts-2"));
	}

	// ── R-154 #16 分页参数 ──

	@Test
	void paginationDefaultsToFullSemantics() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1"),
				new UserMessage("m2")));

		this.mockMvc.perform(get("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(3))
			.andExpect(jsonPath("$.data.total").value(3))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(3))
			.andExpect(jsonPath("$.data.messages[0].text").value("m0"))
			.andExpect(jsonPath("$.data.messages[2].text").value("m2"));
	}

	@Test
	void paginationOffsetLimitSlicesWindowInOrder() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1"),
				new UserMessage("m2"), new UserMessage("m3"), new UserMessage("m4")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "1").param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(2))
			.andExpect(jsonPath("$.data.total").value(5))
			.andExpect(jsonPath("$.data.hasMore").value(true))
			.andExpect(jsonPath("$.data.messages.length()").value(2))
			.andExpect(jsonPath("$.data.messages[0].text").value("m1"))
			.andExpect(jsonPath("$.data.messages[1].text").value("m2"));
	}

	@Test
	void paginationHasMoreReflectsOlderMessagesBeforeSlice() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1"),
				new UserMessage("m2"), new UserMessage("m3"), new UserMessage("m4")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(2))
			.andExpect(jsonPath("$.data.total").value(5))
			.andExpect(jsonPath("$.data.hasMore").value(true))
			.andExpect(jsonPath("$.data.messages[0].text").value("m3"))
			.andExpect(jsonPath("$.data.messages[1].text").value("m4"));
	}

	@Test
	void paginationLimitZeroReturnsEmptySliceWithTotal() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("limit", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(0));
	}

	@Test
	void paginationNegativeOffsetAndLimitClampToZero() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "-1").param("limit", "-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(0));
	}

	@Test
	void paginationOffsetBeyondTotalReturnsEmptySliceWithTotal() throws Exception {
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("m0"), new UserMessage("m1")));

		this.mockMvc.perform(get("/api/ai/history/s-1").param("offset", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0))
			.andExpect(jsonPath("$.data.total").value(2))
			.andExpect(jsonPath("$.data.hasMore").value(false))
			.andExpect(jsonPath("$.data.messages.length()").value(0));
	}

	@SuppressWarnings("unchecked")
	private AiController controller(AiAvailability availability) {
		return new AiController(availability, provider(), provider(), provider(), this.chatMemory,
				this.chatMemoryRepository, this.sessionGate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
