package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /api/ai/health} 的 {@code sheet} 块（AI 表格工具治理方案 2026-10-22 §2.5 / §4.4）。
 *
 * <h2>为什么必须是"有效值"</h2>
 * 前端要靠这一块才能<b>在数据回来之前</b>收紧采样/投影上界（{@code min(键值, 硬顶)}）。
 * 若这里报配置原值（比如运维写了 200），前端会以为还能取 200 行 —— 数据已经进了模型之后
 * 再拒一个回执，什么也挽回不了（D2 已经出域）。
 *
 * <p>两条一致性：① 四键与 {@link AiProperties.Sheet} 的有效值逐值相同（S-05）；
 * ② 未装配时<b>不写</b>该键（"未装配"与"全 0"必须可区分，与 {@code security} 同姿态）。
 */
class AiControllerHealthSheetTest {

	@Test
	void sheetBlockEchoesEffectiveSwitchesAndCounters() {
		AiProperties properties = new AiProperties();
		properties.getSheet().setSampleMaxRows(200);
		SheetSwitches switches = new SheetSwitches(properties);
		SheetAudit audit = new SheetAudit(switches, mock(RunStore.class));
		audit.disabledReject();
		audit.record("s-1", pendingRead());

		Map<String, Object> body = health(switches, audit);

		@SuppressWarnings("unchecked")
		Map<String, Object> sheet = (Map<String, Object>) body.get("sheet");
		assertThat(sheet).containsEntry("readEnabled", true)
			.containsEntry("writeEnabled", false)
			// 配置写 200 ⇒ 报的是生效的 20
			.containsEntry("sampleMaxRows", 20)
			.containsEntry("projectionMaxColumns", 5)
			.containsEntry("effectiveFrom", "config");
		@SuppressWarnings("unchecked")
		Map<String, Object> counters = (Map<String, Object>) sheet.get("counters");
		assertThat(counters).containsEntry("scope", "process")
			.containsEntry("readCalls", 1L)
			.containsEntry("sampleRowsTotal", 5L)
			.containsEntry("disabledRejects", 1L)
			.containsEntry("writeApplied", 0L);
	}

	@Test
	void sheetBlockIsAbsentWhenNotWired() {
		Map<String, Object> body = health(null, null);

		assertThat(body).doesNotContainKey("sheet");
	}

	@Test
	void countersAreAbsentWhenOnlyTheSwitchesAreWired() {
		Map<String, Object> body = health(new SheetSwitches(new AiProperties()), null);

		@SuppressWarnings("unchecked")
		Map<String, Object> sheet = (Map<String, Object>) body.get("sheet");
		assertThat(sheet).containsEntry("readEnabled", true).doesNotContainKey("counters");
	}

	// ── 装配（照 AiControllerHealthParamsTest 的最小面） ──────────────────────

	private static com.example.configmgr.ai.run.PendingToolCall pendingRead() {
		com.example.configmgr.ai.run.PendingToolCall pending = new com.example.configmgr.ai.run.PendingToolCall();
		pending.setName("read_sheet_summary");
		pending.setStatus(com.example.configmgr.ai.run.PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome("ok");
		pending.setSheetMeta(Map.of("dataClass", "D2", "sampleRows", 5, "sampleChars", 412));
		return pending;
	}

	private Map<String, Object> health(SheetSwitches switches, SheetAudit audit) {
		return controller(switches, audit).health().getBody();
	}

	private AiController controller(SheetSwitches switches, SheetAudit audit) {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		ConfirmGate gate = mock(ConfirmGate.class);
		when(gate.frontendToolTimeoutSeconds()).thenReturn(120);
		JobWatchdog jobWatchdog = mock(JobWatchdog.class);
		when(jobWatchdog.metrics()).thenReturn(Map.of("tracked", 0));
		AiController built = new AiController(availability, provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), gate,
				mock(RunStore.class), mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), new ObjectMapper(),
				new AiProperties(), mock(Environment.class), jobWatchdog);
		ReflectionTestUtils.setField(built, "sheetSwitches", switches);
		ReflectionTestUtils.setField(built, "sheetAudit", audit);
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
