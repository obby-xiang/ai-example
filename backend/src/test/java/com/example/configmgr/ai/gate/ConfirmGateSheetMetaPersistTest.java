package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.run.SseChatEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 表格采样元数据（{@code sheetMeta}）必须在**结算写回的那份条目**上——L-21 主用例的接口级证据。
 *
 * <h2>为什么单测要专门钉这一条</h2>
 * 该元数据是 `tool_call_end` 的 8 个 additive 字段与 {@code ai:safety:} 台账的唯一来源
 * （AI 表格工具治理 §4.2/§4.3）。它在入口过白名单后落在**本地副本**上，而 R4 抢到 guard 时
 * 会重新从外置存储读一份新副本（真实 Redis 语义 = 反序列化出另一个对象）—— 若把副本切换写成
 * "重新赋值"，此前的落值就静默丢了：帧、日志、台账三面一起缺，且**判定语义完全正常**，
 * 只有观测面塌掉。故用 {@link InMemoryRunStore#InMemoryRunStore(boolean) 往返模式}（深拷贝）
 * 让这条实现事实可判。
 *
 * <p>既有 {@code SheetSampleTraceTest} 覆盖的是"元数据到了之后日志与台账怎么写"（它直接给
 * 条目打桩），本类覆盖的是更上游的一跳："元数据有没有活到结算那一刻"。
 */
class ConfirmGateSheetMetaPersistTest {

	private static final String RUN_ID = "run-sheet-meta";

	private static final String SESSION_ID = "s-sheet-meta";

	private static final String TOOL_CALL_ID = "call-sheet-meta";

	/** 往返模式：`pendingRaw` 每读一次都是新副本（与真实 Redis 反序列化同口径）。 */
	private final InMemoryRunStore store = new InMemoryRunStore(true);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);

	@Test
	void sheetMetaLandsOnTheSettledEntryEvenAfterTheClaimReload() {
		seedSuspendedFrontend();

		ConfirmGate.Submission submission = gate().submitFrontendResult(RUN_ID, TOOL_CALL_ID, "共 5 行", "aie2e",
				false, "ok", Map.of("dataClass", "D1", "totalRows", 5, "sheetVer", 1), null);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		PendingToolCall settled = this.store.pending(RUN_ID, TOOL_CALL_ID);
		assertThat(settled.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(settled.getSheetMeta())
			.as("白名单副本必须活到结算写回（否则 tool_call_end 八字段与 ai:safety 台账双双缺席）")
			.containsEntry("dataClass", "D1")
			.containsKey("totalRows")
			.containsKey("sheetVer");
		// 数值键按字符串比对：外置存储往返会换数值宽度（Long ↔ Integer），值是同一个
		assertThat(String.valueOf(settled.getSheetMeta().get("totalRows"))).isEqualTo("5");
		assertThat(String.valueOf(settled.getSheetMeta().get("sheetVer"))).isEqualTo("1");
	}

	@Test
	void nonWhitelistedKeysNeverReachTheSettledEntry() {
		seedSuspendedFrontend();

		gate().submitFrontendResult(RUN_ID, TOOL_CALL_ID, "共 5 行", "aie2e", false, "ok",
				Map.of("dataClass", "D2", "sampleRows", 3, "values", List.of("人民币")), null);

		PendingToolCall settled = this.store.pending(RUN_ID, TOOL_CALL_ID);
		assertThat(settled.getSheetMeta()).containsEntry("dataClass", "D2");
		assertThat(String.valueOf(settled.getSheetMeta().get("sampleRows"))).isEqualTo("3");
		assertThat(settled.getSheetMeta())
			.as("白名单之外的键（业务值通道）在入口就该消失")
			.doesNotContainKey("values");
	}

	// ───────────────────────── helpers ─────────────────────────

	private ConfirmGate gate() {
		return new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations, this.beacon,
				List.of(), this.provider, Runnable::run);
	}

	private void seedSuspendedFrontend() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setName("aggregate_sheet");
		pending.setType("function");
		pending.setArguments("{\"defCode\":\"CURRENCY\"}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		this.store.seedPending(RUN_ID, pending);
		when(this.provider.getIfAvailable()).thenReturn(this.resumeService);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.resumeService.resume(RUN_ID)).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.NOT_FOUND, Map.of("runId", RUN_ID)));
	}

}
