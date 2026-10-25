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
import com.example.configmgr.common.ErrorBodyFactory;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结构化回灌超限拒绝（{@code FRONTEND_RESULT_TOO_LARGE}）：{@code POST /api/ai/frontend-tool-result}。
 *
 * <h2>为什么要这条判据</h2>
 * 回灌端点的旧口径是<b>一律</b> substring 截断 + 标记（防护批③ B2-7）。对普通文本这是对的
 * （拒绝会让悬起永久挂着），但对<b>结构化结果</b>是错的：字符级截断会切坏 JSON 结构、
 * 切掉表格结果的「共 N 格」尾行 —— 落库的是半截数据，比重试更危险。
 *
 * <h2>本批口径</h2>
 * <ul>
 * <li>结构化（JSON 对象/数组、表格写工具的结果）超限 → <b>400</b>
 * {@code FRONTEND_RESULT_TOO_LARGE}，在进入 {@link ConfirmGate} <b>之前</b>拒绝：
 * 不调用 {@code submitFrontendResult}、不写条目、pending 原样留着，客户端改小后重试；</li>
 * <li>普通文本超限 → 既有截断 + 标记行为<b>一字不变</b>；</li>
 * <li>未知 / 已决 / 非前端 kind 的条目 → 既有 409 语义，不被新判据吞掉。</li>
 * </ul>
 */
class FrontendResultTooLargeTest {

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	/** 上限的唯一来源：{@code app.ai.result-budget.callback.max-chars}（本批不新增第二把尺子）。 */
	private final int limit = new AiProperties().getResultBudget().getCallback().getMaxChars();

	@Test
	void overlongFormJsonIsRejectedBeforeTheGate() throws Exception {
		PendingToolCall pending = pending("generative_form", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		String formJson = "{\"keyword\":\"" + "A".repeat(9000) + "\",\"scope\":\"XN\"}";

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", formJson)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FRONTEND_RESULT_TOO_LARGE"))
			.andExpect(jsonPath("$.accepted").value(false))
			.andExpect(jsonPath("$.duplicate").value(false))
			.andExpect(jsonPath("$.retryable").value(true))
			.andExpect(jsonPath("$.truncated").value(false))
			.andExpect(jsonPath("$.originalChars").value(formJson.length()))
			.andExpect(jsonPath("$.limit").value(this.limit))
			.andExpect(jsonPath("$.name").value("generative_form"))
			.andExpect(jsonPath("$.kind").value(PendingToolCall.KIND_FRONTEND))
			.andExpect(jsonPath("$.status").value(PendingToolCall.PENDING))
			.andExpect(jsonPath("$.note").value(containsString("PENDING")));

		// 闸门从未被调用：不消费挂起、不落任何条目（没有"部分落库"）
		verify(this.gate, never()).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(),
				any());
		verify(this.runStore, never()).putPending(anyString(), any());
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(pending.getResultText()).isNull();
	}

	@Test
	void overlongSheetWriteResultIsRejectedInsteadOfLosingItsTailLine() throws Exception {
		PendingToolCall pending = pending("update_rows", PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		StringBuilder summary = new StringBuilder("已应用（用户已确认）：update_rows\n");
		while (summary.length() < this.limit + 500) {
			summary.append("行内改动明细：A 列 → B 列\n");
		}
		summary.append("共 1200 格");
		String sheetResult = summary.toString();
		assertThat(sheetResult).hasSizeGreaterThan(this.limit);

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", sheetResult)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FRONTEND_RESULT_TOO_LARGE"))
			.andExpect(jsonPath("$.originalChars").value(sheetResult.length()));

		verify(this.gate, never()).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(),
				any());
		// 尾行没有被"切一半"落库：条目从未被写过
		verify(this.runStore, never()).putPending(anyString(), any());
	}

	@Test
	void shortenedStructuredResultPassesThroughVerbatim() throws Exception {
		PendingToolCall pending = pending("generative_form", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", "{\"keyword\":\"CNY\"}", null, false, null))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED,
					settled(PendingToolCall.FRONTEND_RESULT), true, null));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", "{\"keyword\":\"CNY\"}")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true))
			.andExpect(jsonPath("$.truncated").value(false));

		verify(this.gate).submitFrontendResult("r-1", "c-1", "{\"keyword\":\"CNY\"}", null, false, null);
	}

	@Test
	void overlongPlainTextIsStillTruncatedAndMarked() throws Exception {
		PendingToolCall pending = pending("ui_snapshot", PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED,
					settled(PendingToolCall.FRONTEND_RESULT), true, null));
		String huge = "行内容".repeat(3000);

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", huge)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.truncated").value(true))
			.andExpect(jsonPath("$.strategy").value("head"));

		org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(this.gate).submitFrontendResult(anyString(), anyString(), captor.capture(), any(), anyBoolean(),
				any());
		assertThat(captor.getValue()).hasSizeLessThanOrEqualTo(this.limit).contains("结果过长已截断");
	}

	/** JSON 形状的判据必须看"能否解析"，不能只看首字符 —— 否则一段以 '{' 开头的普通文本会被误伤。 */
	@Test
	void bracePrefixedPlainTextIsNotMistakenForJson() throws Exception {
		PendingToolCall pending = pending("ui_snapshot", PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED,
					settled(PendingToolCall.FRONTEND_RESULT), true, null));
		String text = "{" + "页面结构说明".repeat(2000);

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", text)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.truncated").value(true));
	}

	/** 数组形态（带前导空白）同样是"明确 JSON 形状"：截断一样会切坏它。 */
	@Test
	void whitespacePrefixedJsonArrayCountsAsStructured() throws Exception {
		PendingToolCall pending = pending("ui_snapshot", PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		String arrayJson = "\n  [\"" + "row\",\"".repeat(2000) + "tail\"]";

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", arrayJson)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FRONTEND_RESULT_TOO_LARGE"));

		verify(this.gate, never()).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(),
				any());
	}

	@Test
	void alreadyDecidedEntryKeepsDuplicateSemantics() throws Exception {
		PendingToolCall pending = pending("generative_form", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.DUPLICATE, pending, false, null));
		String formJson = "{\"keyword\":\"" + "A".repeat(9000) + "\"}";

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", formJson)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_TOOL_CALL_ID"));

		verify(this.gate).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(), any());
	}

	@Test
	void unknownEntryKeepsUnknownToolCallSemantics() throws Exception {
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(null);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.NOT_FOUND, null, false, null));
		String formJson = "{\"keyword\":\"" + "A".repeat(9000) + "\"}";

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", formJson)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("UNKNOWN_TOOL_CALL"));

		verify(this.gate).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(), any());
	}

	/**
	 * kind 不是 FRONTEND（确认门条目）时：<b>不得</b>被尺寸判据吞成"结果太大"，也不得走进闸门 ——
	 * 由入口 kind 校验报 {@code TOOL_KIND_MISMATCH}（kind 校验批之后这是更准确的口径）。
	 */
	@Test
	void nonFrontendKindIsNotTakenOverByTheSizeGate() throws Exception {
		PendingToolCall pending = pending("delete_task", PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING);
		when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("test-instance");
		String formJson = "{\"keyword\":\"" + "A".repeat(9000) + "\"}";

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", formJson)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOOL_KIND_MISMATCH"));

		verify(this.gate, never()).submitFrontendResult(anyString(), anyString(), any(), any(), anyBoolean(),
				any());
	}

	/**
	 * 成功的短文本回灌：只做一次入口 kind 校验的读，不触发结构化/超限判定。
	 *
	 * <p>（kind 校验批之后每次回灌都会读一次条目 —— 判据必须是服务端 kind；这里锁"只读一次、
	 * 且不因此拒绝"。）
	 */
	@Test
	void shortResultReadsTheEntryOnceForTheKindCheckOnly() throws Exception {
		when(this.runStore.pendingLenient("r-1", "c-1"))
			.thenReturn(pending("generative_form", PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING));
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult("r-1", "c-1", "ok", null, false, null))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED,
					settled(PendingToolCall.FRONTEND_RESULT), true, null));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(body("r-1", "c-1", "ok")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.truncated").value(false));

		verify(this.runStore, org.mockito.Mockito.times(1)).pendingLenient("r-1", "c-1");
	}

	/**
	 * 用户放弃（{@code cancelled=true}）优先级最高：结果本来就会被丢弃，
	 * 不该因为"太大"被挡在门外 —— 否则用户关不掉一张填错的表单。
	 */
	@Test
	void cancelledSubmissionBypassesTheSizeGate() throws Exception {
		when(this.runStore.instanceId()).thenReturn("test-instance");
		when(this.gate.submitFrontendResult(anyString(), anyString(), any(), any(), org.mockito.ArgumentMatchers.eq(true),
				any())).thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED,
						settled(PendingToolCall.FRONTEND_CANCELLED), true, null));
		String formJson = "{\"keyword\":\"" + "A".repeat(9000) + "\"}";

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(cancelledBody("r-1", "c-1", formJson)))
			.andExpect(status().isOk());

		verify(this.gate).submitFrontendResult(anyString(), anyString(), any(), any(),
				org.mockito.ArgumentMatchers.eq(true), any());
	}

	/** 非中文 locale 取 {@code messages_en} 的码级文案（双语补齐的可执行证据）。 */
	@Test
	void englishLocaleUsesTheRegisteredMessage() throws Exception {
		LocaleContextHolder.setLocale(Locale.US);
		try {
			AiController localized = controller();
			org.springframework.test.util.ReflectionTestUtils.setField(localized, "errorBodyFactory", factory());
			MockMvc localizedMvc = MockMvcBuilders.standaloneSetup(localized).build();
			PendingToolCall pending = pending("generative_form", PendingToolCall.KIND_FRONTEND,
					PendingToolCall.PENDING);
			when(this.runStore.pendingLenient("r-1", "c-1")).thenReturn(pending);
			when(this.runStore.instanceId()).thenReturn("test-instance");
			String formJson = "{\"keyword\":\"" + "A".repeat(9000) + "\"}";

			localizedMvc
				.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
					.content(body("r-1", "c-1", formJson)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FRONTEND_RESULT_TOO_LARGE"))
				.andExpect(jsonPath("$.message").value(
						"The structured result exceeds the callback size limit. Reduce it and submit again"));
		}
		finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

	private static ErrorBodyFactory factory() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
		return new ErrorBodyFactory(beans.getBeanProvider(org.springframework.context.MessageSource.class));
	}

	private String body(String runId, String toolCallId, String result) throws Exception {
		return this.objectMapper.writeValueAsString(
				new FrontendToolResultRequest(runId, toolCallId, result, null, false, null, null, null));
	}

	private String cancelledBody(String runId, String toolCallId, String result) throws Exception {
		return this.objectMapper.writeValueAsString(
				new FrontendToolResultRequest(runId, toolCallId, result, null, true, null, null, null));
	}

	private static PendingToolCall pending(String name, String kind, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName(name);
		pending.setKind(kind);
		pending.setStatus(status);
		return pending;
	}

	/** 闸门接受后回给控制器的条目（本批只关心 HTTP 契约，不关心它的内容）。 */
	private static PendingToolCall settled(String status) {
		return pending("generative_form", PendingToolCall.KIND_FRONTEND, status);
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求、不读 Redis）。 */
	private AiController controller() {
		return new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate,
				this.runStore, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.objectMapper,
				new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
