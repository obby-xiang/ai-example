package com.example.configmgr.ai.resultview;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 帧载体（设计稿 §6）：{@code resultView} 是 {@code tool_result} 的 <b>additive 可选键</b>。
 *
 * <p>本类锁三条：
 * <ol>
 * <li><b>缺键 = 旧行为</b>：不带视图时帧的键集合与基线完全相同（不多一个 {@code resultView: null}）；</li>
 * <li><b>带键自洽</b>：带视图时只多这一个键，{@code result}（模型侧/人读通道）逐字不变；</li>
 * <li><b>帧名不变</b>：{@code type} 仍是 {@code tool_result}（帧类型集 16 的冻结不许破）。</li>
 * </ol>
 *
 * <p>kill：把 {@code toolResult} 改成"总是 put resultView（含 null）"、或把视图写进
 * {@code result} 文本、或新增帧名，本类必红。
 */
class ResultViewFrameTest {

	private static final String RUN_ID = "run-resultview";

	private final RunStore store = mock(RunStore.class);

	private final SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());

	@Test
	void toolResultWithoutViewKeepsTheBaselineKeySet() {
		this.emitter.toolResult(executed("call-1"), true, false, "全部 15 个定义");
		Map<String, Object> frame = lastFrame();

		assertThat(frame).containsEntry("type", "tool_result")
				.as("§6.2：帧名不动").doesNotContainKey("resultView");
		assertThat(frame).containsEntry("result", "全部 15 个定义");
		assertThat(frame.keySet()).containsExactlyInAnyOrder("type", "seq", "atMs", "runId", "toolCallId", "name",
				"kind", "ok", "executed", "executedBy", "reused", "status", "outcome", "code", "retryable",
				"target", "messageId", "result");
	}

	@Test
	void toolResultWithViewAddsExactlyOneKey() {
		ResultView view = ResultViews.table("rv-call-2", null,
				List.of(ResultViewColumn.of("defCode", ResultViewColumnType.STRING, null, null)),
				List.of(ResultViewTest.row("CFG_A", "-", "冲突")), "i18n:resultview.test.fallback",
				"validation_issue");

		this.emitter.toolResult(executed("call-2"), true, false, "发布作业已启动（作业 #9）", view);

		Map<String, Object> withView = lastFrame();
		this.emitter.toolResult(executed("call-3"), true, false, "发布作业已启动（作业 #9）");
		Map<String, Object> withoutView = lastFrame();

		assertThat(withView).containsEntry("type", "tool_result")
				.containsEntry("result", "发布作业已启动（作业 #9）")
				.containsKey("resultView");
		assertThat(withView.keySet()).as("只多一个键；result 逐字不变（模型侧那份不受影响）")
				.containsAll(withoutView.keySet())
				.hasSize(withoutView.size() + 1);
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) withView.get("resultView");
		assertThat(payload).containsEntry("version", "1.0").containsEntry("kind", "table");
		assertThat(payload).containsKey("fallback");
		assertThat(withView.get("result")).isEqualTo(withoutView.get("result"));
	}

	@Test
	void nullViewIsIndistinguishableFromLegacyFrame() throws Exception {
		ObjectMapper json = new ObjectMapper();
		this.emitter.toolResult(executed("call-4"), true, false, "结果文本");
		Map<String, Object> legacy = lastFrame();

		// 同一份帧重放一遍（视图 = null 等价于"不传"），字节级一致
		this.emitter.toolResult(executed("call-4"), true, false, "结果文本");
		Map<String, Object> replay = lastFrame();
		// seq / atMs 是本轮帧的单调序号与构造时刻（必然不同），比对其余字段的字节级一致
		Map<String, Object> normalizedLegacy = new java.util.LinkedHashMap<>(legacy);
		Map<String, Object> normalizedReplay = new java.util.LinkedHashMap<>(replay);
		normalizedLegacy.remove("seq");
		normalizedReplay.remove("seq");
		normalizedLegacy.remove("atMs");
		normalizedReplay.remove("atMs");
		assertThat(json.writeValueAsString(normalizedReplay)).isEqualTo(json.writeValueAsString(normalizedLegacy));
		assertThat(replay).doesNotContainKey("resultView");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> lastFrame() {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(this.store, atLeastOnce()).appendEvent(eq(RUN_ID), captor.capture());
		List<Map<String, Object>> all = captor.getAllValues();
		return all.getLast();
	}

	private static PendingToolCall executed(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_publish");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(PendingToolCall.EXECUTED);
		return pending;
	}
}
