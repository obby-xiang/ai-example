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
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>人工等待 park</b>（用户裁决 2026-10-25）：等人/等前端时<b>不驻留工作线程</b>。
 *
 * <h2>三点硬断言</h2>
 * <ol>
 * <li><b>池容量不再是挂起并发上限</b>：{@code pool=2} 同时挂起 3 个任务，三个任务都<b>立刻结束</b>
 * （{@code RunParkedException}），{@code activeCount == 0}；不发起任何上游调用（本用例直接驱动
 * 工具管理器，根本没有模型），不留 latch、不发心跳、不写终帧；</li>
 * <li><b>Redis 状态是合法恢复态</b>：快照 SUSPENDED、待决条目仍是 PENDING 且 req 位在、
 * {@code confirm_request} 已入档；</li>
 * <li><b>决策由 ResumeService 重建执行</b>：park 之后本进程没有等待方，{@code submitDecision}
 * 必须派发续跑（每个 run 各一次），而不是等一个不存在的线程。</li>
 * </ol>
 *
 * <p>证红：把 {@code ConfirmGate} 的 park 分支删掉（回到阻塞等待）⇒ 第 1 例的任务会卡在
 * {@code await} 上直到超时（future 拿不到 {@code RunParkedException}）；把 park 分支里的
 * "交还租约"去掉（用带租约设施的装配）⇒ 租约残留断言红。
 */
class HumanWaitParkTest {

	private static final String SESSION_ID = "s-park";

	private static final String[] RUNS = { "run-park-1", "run-park-2", "run-park-3" };

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private final List<String> resumed = new ArrayList<>();

	private final AiProperties properties = new AiProperties();

	private ThreadPoolTaskExecutor executor;

	private ThreadPoolTaskExecutor trigger;

	private ConfirmGate gate;

	private SpToolCallingManager manager;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() throws Exception {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.resumeService.resume(anyString())).thenAnswer(invocation -> {
			String runId = invocation.getArgument(0);
			synchronized (this.resumed) {
				this.resumed.add(runId);
			}
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
					Map.of("runId", runId, "resumed", true));
		});
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);

		// 挂起池 = 2（用户给的最坏形态）；触发池同步执行，让"派发"在决策调用线程里可见
		this.executor = pool(2, 3, "ai-suspend-test-");
		java.util.concurrent.Executor syncTrigger = Runnable::run;
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, syncTrigger, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of());
		for (String runId : RUNS) {
			RunSnapshot snapshot = this.store.seedRun(runId, SESSION_ID);
			snapshot.setStatus(RunSnapshot.RUNNING);
			snapshot.setContext(AiContext.empty().toMap());
			this.store.save(snapshot);
		}
	}

	@AfterEach
	void tearDown() {
		if (this.executor != null) {
			this.executor.shutdown();
		}
		if (this.trigger != null) {
			this.trigger.shutdown();
		}
	}

	/**
	 * <b>C1 多卡死锁</b>：一批 2 张卡时，park 发生在<b>第一张</b>卡的 await —— 若第二张卡的请求帧
	 * 与 req 位没在 park 前公布，用户永远看不到第二张卡，ResumeService 又因"仍有 PENDING 条目"
	 * 拒绝驱动 ⇒ 死锁。
	 *
	 * <p>本批口径：park 之前把<b>本批全部</b>待决请求帧都公布并置 req，使每张卡都可决策
	 * （不能靠删除 {@code PENDING_UNRESOLVED} 判据绕过）。
	 */
	@Test
	void multiCardBatchPublishesEveryRequestBeforeParking() throws Exception {
		try {
			this.executor.submit(() -> {
				try {
					this.manager.executeToolCalls(prompt(RUNS[0]), twoCalls());
					return null;
				}
				catch (RunParkedException parked) {
					throw parked;
				}
			}).get(5, TimeUnit.SECONDS);
		}
		catch (ExecutionException parked) {
			assertThat(parked.getCause()).isInstanceOf(RunParkedException.class);
		}

		PendingToolCall first = this.store.pending(RUNS[0], "call-1");
		PendingToolCall second = this.store.pending(RUNS[0], "call-2");
		assertThat(first.getReq()).as("首卡：请求帧发出前已置 req").isTrue();
		assertThat(second.getReq()).as("park 前必须把本批每张卡的 req 都置上（否则第二张永远不可决策）")
			.isTrue();
		assertThat(this.store.archivedTypes(RUNS[0]).stream().filter("confirm_request"::equals).count())
			.as("两张卡的请求帧都必须在 park 前公布").isEqualTo(2L);
		assertThat(this.store.get(RUNS[0]).getInFlightToolCalls()).hasSize(2);

		// 两张卡都批准 ⇒ 每张都走一次决策派发（不再"批准了却卡死"）
		assertThat(this.gate.submitDecision(RUNS[0], "call-1", true, "ok").outcome())
			.isNotEqualTo(ConfirmGate.Outcome.NOT_FOUND);
		assertThat(this.gate.submitDecision(RUNS[0], "call-2", true, "ok").outcome())
			.isNotEqualTo(ConfirmGate.Outcome.NOT_FOUND);
		verify(this.resumeService, times(2)).resume(RUNS[0]);
	}

	/**
	 * pool=2、挂起 3 个任务：三个都<b>立刻结束</b>（park），activeCount 归零，无上游/心跳/终帧，
	 * 快照/待决条目保持可恢复；决策逐个派发续跑。
	 */
	@Test
	void threeWaitsOnATwoThreadPoolAllParkAndLeaveNoThreadBehind() throws Exception {
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < RUNS.length; i++) {
			String runId = RUNS[i];
			String callId = "call-" + (i + 1);
			futures.add(this.executor.submit(() -> {
				try {
					this.manager.executeToolCalls(prompt(runId), response(callId));
					return null;
				}
				catch (RunParkedException parked) {
					throw parked;
				}
			}));
		}

		for (int i = 0; i < futures.size(); i++) {
			try {
				futures.get(i).get(5, TimeUnit.SECONDS);
				throw new AssertionError("第 " + (i + 1) + " 个等待必须 park（抛 RunParkedException）而不是阻塞");
			}
			catch (ExecutionException ex) {
				assertThat(ex.getCause()).as("park 是控制流信号，不是失败")
					.isInstanceOf(RunParkedException.class);
			}
		}
		// Future 完成 ≠ worker 已从任务体返回：{@code activeCount} 有"刚跑完还没落回 idle"的窗口，
		// 因此按<b>有界等待</b>判定（真漏线程时永远到不了 0，1s 足够区分）。
		long drainDeadline = System.currentTimeMillis() + 1000L;
		while (this.executor.getActiveCount() > 0 && System.currentTimeMillis() < drainDeadline) {
			Thread.sleep(10L);
		}
		assertThat(this.executor.getActiveCount()).as("pool=2 挂起 3 个任务后不得残留工作线程").isZero();

		for (int i = 0; i < RUNS.length; i++) {
			String runId = RUNS[i];
			String callId = "call-" + (i + 1);
			RunSnapshot snapshot = this.store.get(runId);
			assertThat(snapshot.getStatus()).as("快照保持 SUSPENDED（合法恢复态）")
				.isEqualTo(RunSnapshot.SUSPENDED);
			PendingToolCall pending = this.store.pending(runId, callId);
			assertThat(pending).isNotNull();
			assertThat(pending.getStatus()).as("待决条目仍是 PENDING（人不决定就不收敛）")
				.isEqualTo(PendingToolCall.PENDING);
			assertThat(this.gate.hasActiveLatch(runId)).as("不得留下 latch").isFalse();
			assertThat(this.store.archivedTypes(runId)).as("请求帧已入档（重挂可回放）")
				.contains("suspended", "confirm_request");
			assertThat(this.store.archivedTypes(runId))
				.as("park 期不得有心跳/工具结局/终帧（没有等待线程，也没有上游继续跑）")
				.doesNotContain("heartbeat", "tool_result", "done", "error");
		}
		assertThat(this.gate.pendingGates()).isZero();

		// 决策到达：本进程没有等待方 ⇒ 必须逐个派发续跑（不能批准了却无人执行）
		for (int i = 0; i < RUNS.length; i++) {
			ConfirmGate.Submission submission = this.gate.submitDecision(RUNS[i], "call-" + (i + 1), true, "ok");
			assertThat(submission.outcome()).isNotEqualTo(ConfirmGate.Outcome.NOT_FOUND);
		}
		verify(this.resumeService, times(RUNS.length)).resume(anyString());
		assertThat(this.resumed).containsExactlyInAnyOrder(RUNS);
	}

	/** 主动 stop 优先于 park：park 之后没有等待线程，stop 必须自己派发续跑推动收敛。 */
	@Test
	void stopAfterParkTriggersResumeSoCancellationConverges() throws Exception {
		try {
			this.executor.submit(() -> {
				try {
					this.manager.executeToolCalls(prompt(RUNS[0]), response("call-1"));
					return null;
				}
				catch (RunParkedException parked) {
					throw parked;
				}
			}).get(5, TimeUnit.SECONDS);
		}
		catch (ExecutionException parked) {
			assertThat(parked.getCause()).isInstanceOf(RunParkedException.class);
		}
		assertThat(this.store.get(RUNS[0]).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);

		ConfirmGate.ResumeTrigger trigger = this.gate.triggerResumeForParked(RUNS[0]);

		assertThat(trigger.triggered()).as("快照仍 SUSPENDED ⇒ 停/催办必须能派发续跑").isTrue();
		verify(this.resumeService).resume(RUNS[0]);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static ThreadPoolTaskExecutor pool(int size, int queue, String prefix) {
		ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
		pool.setCorePoolSize(size);
		pool.setMaxPoolSize(size);
		pool.setQueueCapacity(queue);
		pool.setThreadNamePrefix(prefix);
		pool.initialize();
		return pool;
	}

	private static Prompt prompt(String runId) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", runId))
			.build();
		return new Prompt(List.of(new UserMessage("hi")), options);
	}

	private static ChatResponse twoCalls() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(
					new AssistantMessage.ToolCall("call-1", "function", "start_import", "{\"taskId\":7}"),
					new AssistantMessage.ToolCall("call-2", "function", "start_import", "{\"taskId\":9}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static ChatResponse response(String callId) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "start_import",
					"{\"taskId\":7}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
