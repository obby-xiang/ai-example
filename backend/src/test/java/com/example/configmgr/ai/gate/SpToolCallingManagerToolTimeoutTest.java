package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后端工具独立超时（超时面治理批② 第 3 项；决策看板 {@code :16} = 120s + {@code TOOL_TIMEOUT}）。
 *
 * <h2>锁的是什么</h2>
 * <ol>
 * <li>到点结算成 {@code outcome=timeout / code=TOOL_TIMEOUT / executed=false}（不是 failed ——
 * failed 会触发批次首错即停，把"一个工具卡住"变成"整批作废"）；</li>
 * <li><b>轮继续</b>：同批后续调用照常执行；</li>
 * <li><b>不写台账</b>：超时的结局是"不知道有没有副作用"，不能被续跑当成功重放；</li>
 * <li>与 {@code FRONTEND_TIMEOUT} 不同码（等后端桩体 vs 等前端回执）。</li>
 * </ol>
 *
 * <p>用例把 {@code app.ai.tool.exec-timeout} 调到 250ms（生产 120s）：判据是"到点收口"的行为，
 * 不是时长本身；时长口径由 {@link #defaultTimeoutIsTwoMinutes()} 单列锁住。
 */
class SpToolCallingManagerToolTimeoutTest {

	private static final String RUN_ID = "run-tool-timeout";

	private static final String SESSION_ID = "s-tool-timeout";

	private static final String SLOW_TOOL = "start_import";

	private static final String FAST_TOOL = "list_tasks";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = mock(RunRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final AtomicInteger slowInvocations = new AtomicInteger();

	/** 快工具被真正执行的次数（饱和用例断言"没有退化成同步执行"）。 */
	private final AtomicInteger fastInvocations = new AtomicInteger();

	/** 执行替身：慢工具睡 5s（可被 interrupt 打断），快工具立即返回。 */
	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of(), null, null, null,
			null, this.properties) {

		@Override
		String delegateSingle(Prompt prompt, PendingToolCall pending) {
			if (!SLOW_TOOL.equals(pending.getName())) {
				SpToolCallingManagerToolTimeoutTest.this.fastInvocations.incrementAndGet();
				return "执行成功：" + pending.getName();
			}
			SpToolCallingManagerToolTimeoutTest.this.slowInvocations.incrementAndGet();
			try {
				Thread.sleep(5_000L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("桩体被中断（预期：超时后 cancel(true)）", ex);
			}
			return "慢工具竟然跑完了";
		}
	};

	@BeforeEach
	void setUp() {
		this.store.seedRun(RUN_ID, SESSION_ID);
		this.properties.getTool().setExecTimeout(Duration.ofMillis(250L));
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.categoryOf(anyString())).thenReturn(ToolMeta.Category.J);
	}

	@Test
	void timesOutIntoTimeoutOutcome() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(),
				response(call("c-1", SLOW_TOOL, "{\"taskId\":1}")));

		ToolResultEnvelope envelope = envelope(responses(result).get(0));
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.TIMEOUT.wire());
		assertThat(envelope.code()).isEqualTo(ErrorCodes.TOOL_TIMEOUT);
		assertThat(envelope.message()).contains(SpToolCallingManager.RECONCILIATION_NOTE);

		PendingToolCall entry = this.store.pending(RUN_ID, "c-1");
		assertThat(entry.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
		assertThat(entry.getOutcome()).isEqualTo(ToolOutcome.TIMEOUT.wire());
		assertThat(entry.getCode()).isEqualTo(ErrorCodes.TOOL_TIMEOUT);
		assertThat(entry.isExecuted()).as("到点没回来 = 未判定为执行过").isFalse();
		assertThat(entry.getExecutedBy()).isNull();
		// 不写台账：超时不能被续跑当成功重放
		assertThat(this.store.idemFor(RUN_ID, "c-1")).isNull();
	}

	@Test
	void timeoutKeepsTheRoundGoing() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(),
				response(call("c-1", SLOW_TOOL, "{\"taskId\":1}"), call("c-2", FAST_TOOL, "{}")));

		List<ToolResponseMessage.ToolResponse> all = responses(result);
		assertThat(all).hasSize(2);
		assertThat(envelope(all.get(0)).code()).isEqualTo(ErrorCodes.TOOL_TIMEOUT);
		assertThat(envelope(all.get(1)).outcome())
				.as("超时不触发批次首错即停（haltsBatch 只认 failed）")
				.isEqualTo(ToolOutcome.OK.wire());
		assertThat(this.store.pending(RUN_ID, "c-2").isExecuted()).isTrue();
	}

	@Test
	void codeIsDistinctFromFrontendTimeout() {
		assertThat(ErrorCodes.TOOL_TIMEOUT).isNotEqualTo(ErrorCodes.FRONTEND_TIMEOUT);
		assertThat(ErrorCodes.TOOL_TIMEOUT).isNotEqualTo(ErrorCodes.CONFIRM_TIMEOUT);
		assertThat(ToolOutcome.fromWire(ToolOutcome.TIMEOUT.wire())).isEqualTo(ToolOutcome.TIMEOUT);
	}

	@Test
	void defaultTimeoutIsTwoMinutes() {
		assertThat(new AiProperties().getTool().getExecTimeout()).isEqualTo(Duration.ofSeconds(120));
	}

	/**
	 * <b>N3：执行器饱和时必须明确结算"未执行"</b>，绝不退化成"无超时同步执行"。
	 *
	 * <p>锁两件事：① 工具桩体一次都没跑（没有同步兜底）；② 结局仍是既有 {@code timeout/TOOL_TIMEOUT}
	 * 口径 —— {@code executed=false}、不写台账、轮继续（不会把一次饱和变成 failed 触发首错即停）。
	 */
	@Test
	void saturatedExecutorSettlesAsUnexecutedWithoutRunningSynchronously() throws Exception {
		ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(this.manager,
				"toolTimeoutExecutor");
		assertThat(executor).isNotNull();
		int capacity = this.properties.getSuspend().getPoolSize();
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch occupied = new CountDownLatch(capacity);
		List<Future<?>> blockers = new ArrayList<>();
		for (int i = 0; i < capacity; i++) {
			blockers.add(executor.submit(() -> {
				occupied.countDown();
				hold.await(10, TimeUnit.SECONDS);
				return null;
			}));
		}
		try {
			assertThat(occupied.await(5, TimeUnit.SECONDS))
				.as("占满超时执行器（容量 = app.ai.suspend.pool-size）")
				.isTrue();

			ToolExecutionResult result = this.manager.executeToolCalls(prompt(),
					response(call("c-1", FAST_TOOL, "{}")));

			ToolResultEnvelope envelope = envelope(responses(result).get(0));
			assertThat(envelope.outcome()).as("未执行沿用既有 timeout 口径（不触发首错即停）")
				.isEqualTo(ToolOutcome.TIMEOUT.wire());
			assertThat(envelope.code()).isEqualTo(ErrorCodes.TOOL_TIMEOUT);
			assertThat(envelope.message()).contains("EXECUTOR_SATURATED").contains("未执行");
			assertThat(this.fastInvocations).as("绝不退化成无超时同步执行").hasValue(0);

			PendingToolCall entry = this.store.pending(RUN_ID, "c-1");
			assertThat(entry.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
			assertThat(entry.isExecuted()).as("饱和 = 确定没有执行").isFalse();
			assertThat(this.store.idemFor(RUN_ID, "c-1")).as("未执行不写台账").isNull();
		}
		finally {
			hold.countDown();
			for (Future<?> blocker : blockers) {
				blocker.get(5, TimeUnit.SECONDS);
			}
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("导入吧")), options);
	}

	private static AssistantMessage.ToolCall call(String id, String name, String args) {
		return new AssistantMessage.ToolCall(id, "function", name, args);
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static List<ToolResponseMessage.ToolResponse> responses(ToolExecutionResult result) {
		List<ToolResponseMessage.ToolResponse> out = new ArrayList<>();
		for (Message message : result.conversationHistory()) {
			if (message instanceof ToolResponseMessage toolMessage) {
				out.addAll(toolMessage.getResponses());
			}
		}
		return out;
	}

	private static ToolResultEnvelope envelope(ToolResponseMessage.ToolResponse response) {
		return ToolResultEnvelope.fromJson(response.responseData());
	}

}
