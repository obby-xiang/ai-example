package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.context.ContextBreakdownService;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/ai/context/{sessionId}}（上下文统计与压缩专项批①；设计稿 §3.4(b)）。
 *
 * <p>
 * 断言面四条：① 载荷形状（七项 breakdown + compaction 对象）；② 刷新恢复的能力（本地内存态没了也有值）；
 * ③ 两级降级（Redis 不可用 503 / 计量未装配 503）；④ 从未记账的会话如实报"未测"而不是 0%。
 */
class AiControllerContextEndpointTest {

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final AiProperties properties = new AiProperties();

	private final InMemoryContextStateStore contextStore = new InMemoryContextStateStore();

	private final ContextCompactor compactor = new ContextCompactor(this.properties,
			new ContextLimitResolver(this.properties), this.contextStore);

	private final ContextBreakdownService breakdownService = new ContextBreakdownService(this.compactor,
			this.contextStore);

	private final MockMvc mockMvc = MockMvcBuilders
		.standaloneSetup(controller(true))
		.build();

	/** ① 载荷形状：七项 + compaction（旧客户端只读它认识的键，前向兼容）。 */
	@Test
	void contextPayloadCarriesBreakdownAndCompaction() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(
				new UserMessage("导出配置"),
				AssistantMessage.builder().content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "任务".repeat(100))))
					.build(),
				new UserMessage("继续")));
		this.compactor.recordTurn("s-1", "deepseek-flash", 84213, 900, 7, false, 3120, 5120, 93);

		this.mockMvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value("s-1"))
			.andExpect(jsonPath("$.model").value("deepseek-flash"))
			.andExpect(jsonPath("$.turnNo").value(7))
			.andExpect(jsonPath("$.limitTokens").value(128000))
			.andExpect(jsonPath("$.usedTokens").value(84213))
			.andExpect(jsonPath("$.percent").value(65.8))
			.andExpect(jsonPath("$.source").value("stream"))
			.andExpect(jsonPath("$.estimated").value(false))
			.andExpect(jsonPath("$.breakdown.length()").value(7))
			.andExpect(jsonPath("$.breakdown[0].key").value("history"))
			.andExpect(jsonPath("$.breakdown[6].key").value("free"))
			.andExpect(jsonPath("$.compaction.level").value("none"))
			.andExpect(jsonPath("$.compaction.circuit").value("closed"))
			.andExpect(jsonPath("$.compaction.fuseFails").value(0));
	}

	/** 分项守恒：七项里前六项之和 == usedTokens（账单与拆分不许加总矛盾）。 */
	@Test
	void breakdownPartsSumToUsedTokens() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("问题".repeat(100))));
		this.compactor.recordTurn("s-1", "deepseek-flash", 5000, 100, 1, false, 1000, 2000, 50);

		String json = this.mockMvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		com.fasterxml.jackson.databind.JsonNode body = new ObjectMapper().readTree(json);
		int sum = 0;
		for (com.fasterxml.jackson.databind.JsonNode item : body.get("breakdown")) {
			if (!"free".equals(item.get("key").asText())) {
				sum += item.get("tokens").asInt();
			}
		}
		org.assertj.core.api.Assertions.assertThat(sum).isEqualTo(body.get("usedTokens").asInt());
	}

	/** ② 上限未知（降级态）：limitTokens 为 null（前端据此只显示已用、不显示百分比）。 */
	@Test
	void unknownLimitReportsNullTokens() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("问题")));
		AiProperties degraded = new AiProperties();
		degraded.getContext().setLimitTokens(0);
		ContextCompactor noLimit = new ContextCompactor(degraded, new ContextLimitResolver(degraded), this.contextStore);
		MockMvc mvc = MockMvcBuilders
			.standaloneSetup(controller(true, new ContextBreakdownService(noLimit, this.contextStore), noLimit))
			.build();

		mvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.limitTokens").doesNotExist())
			.andExpect(jsonPath("$.percent").doesNotExist())
			.andExpect(jsonPath("$.breakdown.length()").value(6));
	}

	/** ③a Redis 不可用 ⇒ 503（与 /history 同姿态：读不到就如实失败）。 */
	@Test
	void redisUnavailableReturns503() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		this.mockMvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(RedisAvailability.code()));
	}

	/** ③b 计量未装配 ⇒ 503（"未装配"与"用了 0"必须可区分）。 */
	@Test
	void unassembledBreakdownServiceReturns503() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(false)).build();

		mvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_CONTEXT_UNAVAILABLE"));
	}

	/** ④ 从未记账的会话：source=none / percent=null（不谎报 0%）。 */
	@Test
	void unmeasuredSessionReportsNone() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of());

		this.mockMvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("none"))
			.andExpect(jsonPath("$.usedTokens").value(0))
			.andExpect(jsonPath("$.measuredAtMs").doesNotExist());
	}

	/** {@code ?ledger=1} 附带压缩台账（运维面）；默认不带。 */
	@Test
	void ledgerIsOnlyReturnedOnDemand() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of());
		this.contextStore.appendLedger("s-1", java.util.Map.of("level", "L1", "before", 90000, "after", 60000));

		this.mockMvc.perform(get("/api/ai/context/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ledger").doesNotExist());
		this.mockMvc.perform(get("/api/ai/context/s-1").param("ledger", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ledger[0].level").value("L1"));
	}

	private AiController controller(boolean withContext) {
		return controller(withContext, this.breakdownService, this.compactor);
	}

	@SuppressWarnings("unchecked")
	private AiController controller(boolean withContext, ContextBreakdownService breakdown, ContextCompactor service) {
		AiController controller = new AiController(this.availability, provider(), provider(), provider(),
				this.chatMemory, this.chatMemoryRepository, this.sessionGate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		if (withContext) {
			ReflectionTestUtils.setField(controller, "contextBreakdownService", breakdown);
			ReflectionTestUtils.setField(controller, "contextCompactor", service);
		}
		return controller;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
