package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返修（Important 3）：{@code /confirm} 与 {@code /frontend-tool-result} 携带的<b>最新工作区上下文</b>
 * 必须在续跑前写进快照，且走与 {@code /chat} 同一套 {@code ContextFieldGate} 消毒。
 *
 * <p>为什么需要：挂起期间用户可以在向导里手动改步骤/勾选，续跑却只读挂起那一刻的快照 ——
 * 模型按旧步骤/旧勾选继续（"提示词承诺了不存在的收敛"）。这条链上没有任何别的写口能拿到页面事实。
 */
class AiControllerWorkspaceContextTest {

	private static final String RUN = "r-ws-ctx";

	private static final String CALL = "c-ws-ctx";

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiController controller = controller();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller).build();

	@Test
	void confirmPersistsSanitizedWorkspaceContextBeforeDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		AiContext stored = AiContext.fromMap(snapshot.getContext());
		assertThat(stored.getStep()).as("挂起期手动推进到 QUERY_COND").isEqualTo("QUERY_COND");
		assertThat(stored.getPage()).isEqualTo("export");
		assertThat(stored.getTaskId()).isEqualTo(42L);
		assertThat(stored.getExtra().get("selectedDefs")).isEqualTo(List.of());
		assertThat(stored.getExtra().get("selectionSynced")).as("保持布尔原值").isEqualTo(true);
		assertThat(stored.getExtra()).as("未知键（旧 taskSlots）被闸门丢弃，不得覆盖页面事实")
			.doesNotContainKey("taskSlots");
		verify(this.runStore).save(snapshot);
	}

	@Test
	void frontendToolResultPersistsWorkspaceContextBeforeDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_FRONTEND);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any()))
			.thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new FrontendToolResultRequest(RUN, CALL, "ok", null,
					false, null, null, null, context()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		AiContext stored = AiContext.fromMap(snapshot.getContext());
		assertThat(stored.getStep()).isEqualTo("QUERY_COND");
		assertThat(stored.getExtra().get("selectedDefs")).isEqualTo(List.of());
		verify(this.runStore).save(snapshot);
	}

	/** 旧客户端不带 context：快照一个字都不动（兼容方向 = 可选字段，缺省不改行为）。 */
	@Test
	void legacyRequestWithoutContextDoesNotTouchTheSnapshot() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null))))
			.andExpect(status().isOk());

		assertThat(AiContext.fromMap(snapshot.getContext()).getStep()).isEqualTo("SELECT_DEFS");
		verify(this.runStore, never()).save(any(RunSnapshot.class));
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private static AiChatRequest.Context context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("pageId", "export");
		extra.put("selectedDefs", List.of());
		extra.put("selectionSynced", true);
		// 客户端不可能伪造服务端槽位：闸门按白名单丢弃，页面事实不得被它压掉
		extra.put("taskSlots", Map.of("defCodes", List.of("STALE")));
		return new AiChatRequest.Context("export", "EXPORT", "QUERY_COND", 42L, "ACTIVE", extra);
	}

	private static RunSnapshot suspendedSnapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN);
		snapshot.setSessionId("s-ws-ctx");
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE",
				Map.of("pageId", "export", "selectedDefs", List.of("CURRENCY"), "selectionSynced", true)).toMap());
		snapshot.setMessageJson(List.of());
		return snapshot;
	}

	private static PendingToolCall pending(String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL);
		pending.setName("generative_form");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(kind);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	private static ConfirmGate.Submission accepted(PendingToolCall pending) {
		return new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null);
	}

	private AiController controller() {
		AiController built = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate,
				this.runStore, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper,
				new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "contextFieldGate", new ContextFieldGate());
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
		ReflectionTestUtils.setField(built, "errorBodyFactory",
				new com.example.configmgr.common.ErrorBodyFactory(
						beans.getBeanProvider(org.springframework.context.MessageSource.class)));
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
