package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>续跑派发预算耗尽后的中性降级结局</b>（"决策已落库 + 无驱动方"的收尾形态）。
 *
 * <h2>被钉住的缺陷</h2>
 * 用户批准后决策已落库，但续跑每拍都被 {@code CANCEL_STATE_UNKNOWN} 拒绝
 * （取消状态读不出来 ⇒ fail-closed）。重挂的 SSE 会一直挂着：服务端 emitter 没有传输超时，
 * 有界重派发（2s→4s→…→30s ×15 ≈ 360s）走完也<b>什么都不发</b> —— 客户端既收不到终帧，
 * 也收不到任何"这一轮为什么停在这里"的结局，只能等自己的超时。
 *
 * <h2>本批口径（已审方案）</h2>
 * <ul>
 * <li><b>预算耗尽发一次可区分的降级结局并收流</b>：复用既有"中性未确认"帧语义
 *     （{@code error} + {@code unconfirmed=true}，AR-05）—— 它不是成功、不是失败、不是取消；</li>
 * <li><b>只发给当下这条连接，不入事件日志</b>：归档里因此<b>没有</b>终帧，本轮后续的成功
 *     {@code done} 仍是它唯一的归档终帧（N-2 的降级回放同一手法）；</li>
 * <li><b>状态仍可恢复</b>：快照保持 {@code SUSPENDED}、条目原样（{@code APPROVED} /
 *     {@code executed=false} / {@code outcome=null}），下一次显式触发（决策/重挂自愈对账）
 *     重新拥有完整预算并派发；</li>
 * <li><b>不伪造</b>：不发 {@code done}（含 {@code cancelled:true}）、不发任何工具结局帧，
 *     工具的 outcome 保持 unknown。</li>
 * </ul>
 *
 * <p>证红：去掉耗尽分支里的收尾 ⇒ 第一个用例的"连接已收流 / 收到降级结局"两条断言红
 * （订阅仍在、帧一条没有）；把降级帧改成 {@code done{cancelled:false}} 或归档写入 ⇒
 * "不伪造终帧"与"归档无终帧"两条红。
 */
class ResumeDispatchExhaustionEndingTest {

	private static final String RUN_ID = "run-dispatch-ending";

	private static final String SESSION_ID = "s-dispatch-ending";

	private static final String CALL_ID = "call-dispatch-ending";

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final List<Duration> scheduled = new ArrayList<>();

	private final AtomicInteger resumeCalls = new AtomicInteger();

	private RecordingEmitter connection;

	private ResumeService resumeService;

	private ConfirmGate gate;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.resumeService = mock(ResumeService.class);
		this.gate = gateWith((task, delay) -> {
			this.scheduled.add(delay);
			task.run();
			return () -> {
			};
		});
		seedSuspendedRun();
	}

	/**
	 * 预算耗尽 ⇒ 恰好一帧可区分的降级结局 + 收流，且本轮仍可显式恢复。
	 *
	 * <p>复现路径与生产一致：决策已落库（归档里有 {@code confirm_request} /
	 * {@code confirm_decision}），重挂的 SSE 已挂着，续跑的每一拍都是 {@code CANCEL_STATE_UNKNOWN}。
	 */
	@Test
	void exhaustedDispatchCycleEndsTheHungStreamWithOneNeutralRecoverableEnding() throws IOException {
		alwaysUnknown();
		seedDecidedToolCall();
		attachReattachedStream();

		this.gate.triggerResumeForParked(RUN_ID);

		// ① 连接收流：不悬挂（服务端 emitter 无传输超时，悬挂 = 客户端只能等自己的超时）
		assertThat(this.registry.of(RUN_ID).subscriberCount())
			.as("预算耗尽必须收流（否则重挂 = 无界 emitter）")
			.isZero();

		// ② 恰好一帧可区分的降级结局（不是 done / 不是取消 / 不是工具结局）
		List<Map<String, Object>> received = this.connection.frames();
		List<Map<String, Object>> endings = received.stream()
			.filter(frame -> Boolean.TRUE.equals(frame.get("unconfirmed")))
			.toList();
		assertThat(endings).as("预算耗尽只发一帧可区分的降级结局（帧流=%s）", received).hasSize(1);
		Map<String, Object> ending = endings.get(0);
		assertThat(ending.get("reason")).as("复用既有取消状态不可确认识别").isEqualTo("CANCEL_STATE_UNKNOWN");
		assertThat(ending.get("code")).as("复用既有故障码，不新增错误体系").isEqualTo(ErrorCodes.AI_REDIS_UNAVAILABLE);
		assertThat(ending.get("recoverable")).as("中性且可恢复").isEqualTo(true);
		assertThat(ending.get("retryable")).isEqualTo(true);
		assertThat(ending.get("degraded")).isEqualTo(true);
		assertThat(ending.get("status")).as("状态仍可恢复").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(ending.get("message")).as("中性文案：不声称成功/失败/取消")
			.isInstanceOf(String.class)
			.asString()
			.contains("仍可恢复");
		assertThat(ending.get("cancelled")).as("不得伪造取消终态").isNull();

		// ③ 不伪造终帧 / 工具结局
		assertThat(received).as("连接上不得出现 done（含 cancelled:true）")
			.noneMatch(frame -> "done".equals(frame.get("type")));
		assertThat(received).as("不得伪造工具结局帧（工具是否执行仍未知）")
			.noneMatch(frame -> "tool_result".equals(frame.get("type"))
					|| "frontend_tool_result".equals(frame.get("type")));
		assertThat(this.store.events(RUN_ID))
			.as("归档里不得出现伪造终帧（本轮仍可恢复，后续成功 done 才是唯一终帧）")
			.noneMatch(frame -> "done".equals(frame.get("type")) || "error".equals(frame.get("type")));

		// ④ 状态仍可恢复：快照 SUSPENDED + 条目原样（outcome 保持 unknown）
		assertThat(this.store.get(RUN_ID).getStatus()).as("预算耗尽不是终态").isEqualTo(RunSnapshot.SUSPENDED);
		PendingToolCall entry = this.store.pendingLenient(RUN_ID, CALL_ID);
		assertThat(entry.getStatus()).as("决策已落库的状态不得被这条路径改写").isEqualTo(PendingToolCall.APPROVED);
		assertThat(entry.isExecuted()).as("不得声称工具已执行").isFalse();
		assertThat(entry.getOutcome()).as("工具 outcome 保持 unknown").isNull();

		// ⑤ 以后仍可显式恢复：下一次外部触发重新拥有完整预算并派发成功
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
					Map.of("runId", RUN_ID, "resumed", true));
		});
		// 计数在打桩之后再取：Mockito 的 when(...) 自身会触发上一个 thenAnswer（与既有用例同一口径）
		int before = this.resumeCalls.get();
		ConfirmGate.ResumeTrigger recovered = this.gate.triggerResumeForParked(RUN_ID);
		assertThat(recovered.triggered()).as("耗尽不封死这一轮：显式恢复仍可派发").isTrue();
		assertThat(this.resumeCalls.get()).as("恢复只派发一次（ACCEPTED 不再重试）").isEqualTo(before + 1);
		assertThat(this.scheduled).as("有界重试次数不变（1 次首发 + max 次重试）")
			.hasSize(this.properties.getPark().getMaxDispatchRetries());
	}

	/**
	 * 同一周期内多条链一起耗尽 ⇒ 降级结局仍只发一次（不刷帧、不重复收尾）。
	 *
	 * <p>用"只排队、不执行"的调度器把并发链的后续拍都排进同一个周期，再一次性排空：
	 * 耗尽判定（{@code attempt == max+1}）只命中一次，因此帧只有一条。
	 */
	@Test
	void overlappingChainsDeliverTheEndingOnlyOncePerCycle() throws IOException {
		List<Runnable> queue = new ArrayList<>();
		ConfirmGate queueing = gateWith((task, delay) -> {
			this.scheduled.add(delay);
			queue.add(task);
			return () -> {
			};
		});
		alwaysUnknown();
		seedDecidedToolCall();
		attachReattachedStream();

		queueing.triggerResumeForParked(RUN_ID);
		queueing.triggerResumeForParked(RUN_ID);
		drain(queue);

		List<Map<String, Object>> endings = this.connection.frames()
			.stream()
			.filter(frame -> Boolean.TRUE.equals(frame.get("unconfirmed")))
			.toList();
		assertThat(endings).as("一个耗尽周期只给一次可区分结局").hasSize(1);
		assertThat(queue).as("耗尽即停（排空后没有积压的后续拍）").isEmpty();
		assertThat(this.registry.of(RUN_ID).subscriberCount()).isZero();
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private void alwaysUnknown() {
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			this.resumeCalls.incrementAndGet();
			return new ResumeService.ResumeResult(ResumeService.Outcome.CANCEL_STATE_UNKNOWN,
					Map.of("runId", RUN_ID, "resumed", false, "reason", "CANCEL_STATE_UNKNOWN"));
		});
	}

	@SuppressWarnings("unchecked")
	private ConfirmGate gateWith(ConfirmGate.RetryScheduler scheduler) {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		return new ConfirmGate(this.store, this.registry, this.properties, mock(CancellationRegistry.class),
				new ToolActivityBeacon(), List.of(), provider, Runnable::run, null, scheduler);
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	/** 决策已落库：条目是 {@code APPROVED} + 未执行 + 无 outcome，归档里有请求/回执两帧。 */
	private void seedDecidedToolCall() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_publish");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.APPROVED);
		pending.setExecuted(false);
		this.store.putPending(RUN_ID, pending);
		SseChatEmitter out = this.registry.of(RUN_ID);
		out.confirmRequest(pending);
		out.confirmDecision(pending, "approve", null, 0L);
	}

	/** 重挂一条真连接（带 {@code lastSeq}，与"决策后重连"同口径：只补差量，不重放已见帧）。 */
	private void attachReattachedStream() {
		long lastSeq = 0L;
		for (Map<String, Object> frame : this.store.events(RUN_ID)) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				lastSeq = Math.max(lastSeq, number.longValue());
			}
		}
		this.connection = new RecordingEmitter();
		this.registry.of(RUN_ID).replayAndAttach(this.connection, lastSeq, false);
		assertThat(this.registry.of(RUN_ID).subscriberCount()).as("前置：确有一条重挂连接").isEqualTo(1);
		assertThat(this.connection.frames()).as("前置：差量回放没有可补的帧").isEmpty();
	}

	private void drain(List<Runnable> queue) {
		int guard = 0;
		while (!queue.isEmpty() && guard++ < 1_000) {
			queue.remove(0).run();
		}
	}

	/** 记录订阅者收到的帧原文（与 {@code SseChatEmitterParkCloseRaceTest} 同一手法）。 */
	private final class RecordingEmitter extends SseEmitter {

		private final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();

		private RecordingEmitter() {
			super(-1L);
		}

		@Override
		public void send(Object object) {
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> frame = ResumeDispatchExhaustionEndingTest.this.mapper
					.readValue(String.valueOf(object), Map.class);
				this.frames.add(new LinkedHashMap<>(frame));
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		List<Map<String, Object>> frames() {
			return List.copyOf(this.frames);
		}

	}

}
