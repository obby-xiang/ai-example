package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 表格采样事实的可见性（AI 表格工具治理方案 2026-10-22 §4.2 / §4.3 / §4.4）。
 *
 * <h2>为什么这组用例是治理的"眼"</h2>
 * §1.5 的反向要求说得直白：<b>开关不是权限</b> —— 关闸只能止住"下一次"，止不住"已经发出去的"。
 * 所以"采样行真的进了模型"这件事必须看得见：日志行有 8 个 additive 字段、台账有
 * {@code kind=sheet_sample} 条目、{@code /health.sheet} 有计数。缺了可见性，撤销就成了盲撤。
 *
 * <p>同时钉死<b>禁止字段不出现</b>：{@code sheetMeta} 里塞进来的单元格值（这里用可识别的 marker
 * 字符串）必须既不在事件行里、也不在台账里 —— 白名单在入口（{@code SheetAudit#sanitize}）就丢弃它。
 *
 * <p>装配说明：确认门用 mock，`awaitFrontendResult` 的应答按真实语义把"结算后的条目"写回
 * {@link InMemoryRunStore}（含前端上送的 {@code sheetMeta}）—— 于是本类测的是
 * "结算后的事件行/台账/计数"，而不是对 mock 的假设。
 */
class SheetSampleTraceTest {

	private static final String RUN_ID = "run-sheet-trace";

	private static final String SESSION_ID = "s-sheet-trace";

	private static final String CALL_ID = "call-sheet-trace-1";

	/** 业务值 marker：它绝不能出现在日志行与台账里。 */
	private static final String BUSINESS_VALUE = "华东-业务值-marker-9911";

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private InMemoryRunStore store;

	private SheetAudit audit;

	private SpToolCallingManager manager;

	/** 本次"前端回灌"要带上的元数据（含故意的禁止字段）。 */
	private Map<String, Object> meta;

	private ToolOutcome declaredOutcome = ToolOutcome.OK;

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.sheetDisabled(anyString())).thenReturn(false);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		this.store = new InMemoryRunStore();
		RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);
		this.audit = new SheetAudit(new SheetSwitches(this.properties), this.store);
		this.manager = new SpToolCallingManager(this.store, registry, this.gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of(), null, null, null,
				null, this.properties);
		this.manager.setSheetAudit(this.audit);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()).toMap());
		this.store.save(snapshot);
		// 前端回灌（真实语义）：结算条目 + 落 sheetMeta（白名单过滤在 ConfirmGate 内，见其重载）
		when(this.gate.awaitFrontendResult(anyString(), any(PendingToolCall.class))).thenAnswer(invocation -> {
			PendingToolCall pending = invocation.getArgument(1);
			pending.setStatus(PendingToolCall.FRONTEND_RESULT);
			pending.setOutcome(this.declaredOutcome.wire());
			pending.setExecuted(true);
			pending.setResolvedAtMs(System.currentTimeMillis());
			pending.setResultText("工具结果正文（不进日志）");
			if (this.meta != null && !this.meta.isEmpty()) {
				pending.setSheetMeta(SheetAudit.sanitize(this.meta));
			}
			this.store.putPending(RUN_ID, pending);
			return pending;
		});
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	/** {@code tool_call_end} 追加恰好 8 个表格字段，且不含业务值。 */
	@Test
	void toolCallEndCarriesTheEightSheetFieldsWithoutBusinessValues() {
		this.meta = fullMeta();

		execute("read_sheet_summary");

		String end = line("tool_call_end");
		assertThat(end).contains("tool=read_sheet_summary")
			.contains("dataClass=D2")
			.contains("sampleRows=5")
			.contains("sampleChars=412")
			.contains("projCols=3")
			.contains("totalRows=3120")
			.contains("sheetVer=7")
			.contains("planId=plan-trace-1")
			.contains("previewLevel=sample-diff");
		// 台账定位标识只进台账，不进日志面（日志严格 = 设计稿 §4.2 的 8 字段）
		assertThat(end).doesNotContain("defCode=").doesNotContain("page=task:");
		assertThat(allLines()).as("业务值绝不允许出现在事件行").doesNotContain(BUSINESS_VALUE);
	}

	/** 非表格工具一个 sheet 键都不写（"逐键可缺席"，不为空值补键）。 */
	@Test
	void nonSheetToolLeavesNoSheetFields() {
		this.meta = null;

		execute("list_tasks");

		String end = line("tool_call_end");
		assertThat(end).doesNotContain("dataClass=").doesNotContain("sampleRows=").doesNotContain("planId=");
	}

	/** 台账复用 {@code ai:safety:<sessionId>}，条目 {@code kind=sheet_sample}（且非 dlp）。 */
	@Test
	void samplingFactLandsInTheSafetyLedgerAsSheetSample() {
		this.meta = fullMeta();

		execute("read_sheet_summary");

		List<Map<String, Object>> ledger = this.store.safetyLedger(SESSION_ID);
		assertThat(ledger).hasSize(1);
		Map<String, Object> entry = ledger.get(0);
		assertThat(entry).containsEntry("kind", "sheet_sample")
			.containsEntry("tool", "read_sheet_summary")
			.containsEntry("defCode", "CURRENCY")
			.containsEntry("page", "task:EXPORT/EXPORT")
			.containsEntry("sampleRows", 5L)
			.containsEntry("totalRows", 3120L)
			.containsEntry("sheetVer", 7L);
		assertThat(entry).doesNotContainKey("cellValue");
		assertThat(entry.toString()).doesNotContain(BUSINESS_VALUE);
	}

	/** 纯元数据（D1）不进台账：台账只记"有业务值出域"的那些次（防台账被结构查询刷满）。 */
	@Test
	void d1OnlyCallsDoNotWriteTheLedger() {
		this.meta = new LinkedHashMap<>();
		this.meta.put("dataClass", "D1");
		this.meta.put("totalRows", 3120);

		execute("aggregate_sheet");

		assertThat(this.store.safetyLedger(SESSION_ID)).isEmpty();
	}

	/** {@code /health.sheet} 的计数随结算增长（进程内口径）。 */
	@Test
	void countersFollowTheCall() {
		this.meta = fullMeta();

		execute("read_sheet_summary");

		assertThat(this.audit.metrics()).containsEntry("readCalls", 1L)
			.containsEntry("sampleRowsTotal", 5L)
			.containsEntry("sampleCharsMax", 412L);
	}

	/** 写工具结算：{@code writePlans / writeApplied} 各 +1（planId 是它们的分母判据）。 */
	@Test
	void writeToolCountsPlansAndApplied() {
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.WRITE);
		this.meta = fullMeta();
		this.meta.put("planId", "plan-write-1");
		this.meta.put("previewLevel", "count");

		execute("replace_values");

		assertThat(this.audit.metrics()).containsEntry("writePlans", 1L).containsEntry("writeApplied", 1L);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static Map<String, Object> fullMeta() {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("dataClass", "D2");
		meta.put("sampleRows", 5);
		meta.put("sampleChars", 412);
		meta.put("projCols", 3);
		meta.put("totalRows", 3120);
		meta.put("sheetVer", 7);
		meta.put("planId", "plan-trace-1");
		meta.put("previewLevel", "sample-diff");
		meta.put("defCode", "CURRENCY");
		meta.put("page", "task:EXPORT/EXPORT");
		// 明令禁止的字段（白名单必须丢掉它们）
		meta.put("cellValue", BUSINESS_VALUE);
		meta.put("predicate", Map.of("value", BUSINESS_VALUE));
		meta.put("scopeSummary", "CURRENCY 条件「region EQ " + BUSINESS_VALUE + "」");
		return meta;
	}

	private void execute(String tool) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		Prompt prompt = new Prompt(List.of(new UserMessage("hi")), options);
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, "{}")))
			.build();
		ToolExecutionResult result = this.manager.executeToolCalls(prompt,
				new ChatResponse(List.of(new Generation(assistant))));
		assertThat(result).isNotNull();
	}

	private String line(String event) {
		return this.appender.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.filter(message -> message.contains("event=" + event))
			.findFirst()
			.orElseThrow(() -> new AssertionError("未捕获 " + event + " 事件行"));
	}

	private List<String> allLines() {
		List<String> lines = new ArrayList<>();
		this.appender.list.forEach(event -> lines.add(event.getFormattedMessage()));
		return lines;
	}

}
