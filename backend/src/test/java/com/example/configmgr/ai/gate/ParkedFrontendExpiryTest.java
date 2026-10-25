package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>I4：park 之后即时前端工具的到期收敛</b>。
 *
 * <h2>缺陷</h2>
 * 即时执行类前端工具（{@code navigate_to} 等）有回执上限（{@code app.ai.hitl.frontend-tool-timeout}）。
 * 旧实现靠<b>等待线程</b>到期后写 {@code TIMEOUT}/{@code FRONTEND_TIMEOUT} 并继续本轮；park 之后
 * 没有等待线程 ⇒ 条目会<b>永远 PENDING</b>，既不收敛也不报错。
 *
 * <h2>本批口径</h2>
 * park 时按 {@code expiresAt} 排一次到期收敛：到点把条目写成 {@code TIMEOUT}（结局未知）、
 * 发结局帧，并<b>派发一次续跑</b>（与决策/回灌同一条有界派发：租约、池饱和重试、跨实例恢复都不变）。
 * 理由是到期本身就是<b>系统裁决</b>——条目已收敛、这一轮不能停在"没有驱动方也没有终帧"：
 * E2E S-09 的契约是"卡收敛为终态、<b>轮次继续到 done</b>"，否则用户只看到一张超时卡、
 * 永远等不到回答。park 仍然成立（不驻留线程，续跑走 Redis 状态 + 派发）。
 */
class ParkedFrontendExpiryTest {

	private static final String RUN_ID = "run-parked-expiry";

	private static final String SESSION_ID = "s-parked-expiry";

	private static final String CALL_ID = "call-nav-1";

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private final AiProperties properties = new AiProperties();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(1));
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("navigate_to")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("navigate_to")).thenReturn(ToolMeta.RiskLevel.READ);
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
				List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);

		assertThatThrownBy(() -> manager.executeToolCalls(prompt(), response()))
			.isInstanceOf(RunParkedException.class);
	}

	@Test
	void parkedFrontendCardConvergesToTimedOutAndDispatchesTheResume() {
		awaitTrue(() -> this.store.archivedTypes(RUN_ID).contains("frontend_tool_result"),
				5000, "park 后的即时前端工具必须在 expiresAt 到期收敛并发结局帧（不能永远 PENDING）");

		PendingToolCall expired = this.store.pending(RUN_ID, CALL_ID);
		assertThat(expired.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
		assertThat(expired.getOutcome()).isEqualTo(ToolOutcome.TIMEOUT.wire());
		assertThat(expired.getCode()).isEqualTo(ToolOutcome.CODE_FRONTEND_TIMEOUT);
		assertThat(expired.getReason()).isEqualTo("FRONTEND_TIMEOUT");
		assertThat(this.store.archivedTypes(RUN_ID)).as("结局帧入档（重挂可见）")
			.contains("frontend_tool_result");
		assertThat(this.store.archivedTypes(RUN_ID)).as("到期收敛本身不是轮终态（终帧由续跑产出）")
			.doesNotContain("done", "error");
		assertThat(this.store.get(RUN_ID).getStatus()).as("派发不改变快照状态（驱动方负责翻 RUNNING）")
			.isEqualTo(RunSnapshot.SUSPENDED);
		// 到期是系统裁决：条目已收敛，这一轮必须继续收尾（E2E S-09 的"轮次继续到 done"）
		verify(this.resumeService, timeout(3000)).resume(RUN_ID);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("跳到编辑器")), options);
	}

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "navigate_to",
					"{\"page\":\"editor\"}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(20L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("等待超时：" + message);
	}

}
