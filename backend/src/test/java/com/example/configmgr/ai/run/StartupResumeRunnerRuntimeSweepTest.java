package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次8B / Task 12 Step 3：<b>运行期低频死轮扫描</b>。
 *
 * <p>
 * 扫描不是第二套判据：候选清单仍取 {@link ResumeService#listRuns()}（启动扫描同一份"发现态"），
 * "有没有人管"仍取 {@link RunLiveness#isDead}（会话门放行 / 重挂自愈同一份活度判据），
 * 判死窗口仍取 {@code app.ai.resume.zombie-age}（180s，<b>不放宽</b>）。
 *
 * <p>
 * 三面必须同时成立，缺一条都会出事：
 * <ul>
 * <li><b>活跃不接管</b>：有租约、本进程有活订阅、业务活动仍在窗口内 ⇒ 一跳都不动（否则同一轮双执行，
 * 第二遍是新的模型请求 + 新的工具副作用）；</li>
 * <li><b>挂起已决、无驱动才接管</b>：SUSPENDED 且待决条目全部结算、业务活动已超龄 ⇒ 交给同一条
 * 续跑路径（决策不能停在"批准了但没人执行"）；仍 {@code PENDING} 的外部项一律不碰（不绕开人审）；</li>
 * <li><b>终态不复活</b>：DONE / FAILED / CANCELLED 不因"超龄"被重新驱动（失败现场与完成事实不得改写）。</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class StartupResumeRunnerRuntimeSweepTest {

	private static final String RUN_ACTIVE = "run-sweep-active";

	private static final String RUN_PARKED = "run-sweep-parked";

	private static final String RUN_FAILED = "run-sweep-failed";

	private static final String RUN_ZOMBIE = "run-sweep-zombie";

	private static final String RUN_CANCELLED = "run-sweep-cancelled";

	private static final String RUN_PENDING = "run-sweep-pending";

	private static final Duration STALE = Duration.ofMinutes(10);

	/** 僵尸窗口内（150s < 180s）：最长合法静默 + 余量都在里面的活轮。 */
	private static final Duration INSIDE_WINDOW = Duration.ofSeconds(150);

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final ResumeService service = new ResumeService(this.store, mock(RunRegistry.class), this.codec,
			this.chatService, mock(SpToolCallingManager.class), this.executor, mock(StringRedisTemplate.class),
			this.properties, this.cancellations, new ToolResultLimiter(this.properties));

	private final AiAvailability availability = mock(AiAvailability.class);

	private final StartupResumeRunner runner = new StartupResumeRunner(this.service, this.store, this.properties,
			this.availability);

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.sessionGate.acquire(anyString(), anyString(), anyString())).thenReturn(null);
		when(this.chatService.systemPromptText(any(AiContext.class))).thenReturn("SYSTEM");
		ReflectionTestUtils.setField(this.service, "sessionGate", this.sessionGate);
	}

	/**
	 * 聚焦用例：同一拍里放三种现场，只有"挂起已决 + 无驱动 + 超龄"那一轮被接管。
	 */
	@Test
	void sweepTakesOverOnlyDeadParkedRunAndLeavesActiveAndTerminalAlone() {
		RunSnapshot active = seed(RUN_ACTIVE, RunSnapshot.RUNNING, Duration.ZERO);
		RunSnapshot parked = seed(RUN_PARKED, RunSnapshot.SUSPENDED, STALE);
		RunSnapshot failed = seed(RUN_FAILED, RunSnapshot.FAILED, STALE);
		long activeUpdatedAt = active.getUpdatedAtMs();
		long failedUpdatedAt = failed.getUpdatedAtMs();

		Map<String, Object> report = this.runner.sweep();

		assertThat(report).containsEntry("swept", true);
		assertThat(resumedRunIds(report)).containsExactly(RUN_PARKED);
		assertThat(resumedOutcomes(report)).containsEntry(RUN_PARKED, "ACCEPTED");
		assertThat(resumed(report).get(0)).containsEntry("zombieTakeover", false);
		assertThat(skippedReasons(report)).containsEntry(RUN_ACTIVE, "ACTIVE")
			.containsEntry(RUN_FAILED, "TERMINAL");
		assertThat(active.getStatus()).as("活跃轮不得被接管/改写").isEqualTo(RunSnapshot.RUNNING);
		assertThat(active.getUpdatedAtMs()).as("活跃轮不得被写").isEqualTo(activeUpdatedAt);
		assertThat(failed.getStatus()).as("失败终态不复活").isEqualTo(RunSnapshot.FAILED);
		assertThat(failed.getUpdatedAtMs()).as("失败终态不得被改写").isEqualTo(failedUpdatedAt);
		assertThat(parked.getStatus()).as("接管后转 RUNNING（交给续跑路径）").isEqualTo(RunSnapshot.RUNNING);
	}

	/** 僵尸 RUNNING（超龄、无租约、无活订阅）→ 带 {@code forceTakeover} 走既有接管分支。 */
	@Test
	void sweepTakesOverZombieRunningRoundWithTakeoverFlag() {
		seed(RUN_ZOMBIE, RunSnapshot.RUNNING, STALE);

		Map<String, Object> report = this.runner.sweep();

		assertThat(resumedRunIds(report)).containsExactly(RUN_ZOMBIE);
		assertThat(resumedOutcomes(report)).containsEntry(RUN_ZOMBIE, "ACCEPTED");
		assertThat(((List<Map<String, Object>>) report.get("resumed")).get(0))
			.containsEntry("zombieTakeover", true);
	}

	/** 窗口内（150s）的 RUNNING 仍是活轮：扫码不得借"低频"之名缩短 180s。 */
	@Test
	void sweepDoesNotShortenTheZombieWindow() {
		seed(RUN_ACTIVE, RunSnapshot.RUNNING, INSIDE_WINDOW);

		Map<String, Object> report = this.runner.sweep();

		assertThat(resumedRunIds(report)).isEmpty();
		assertThat(skippedReasons(report)).containsEntry(RUN_ACTIVE, "ACTIVE");
		assertThat(((Number) report.get("zombieAgeMs")).longValue())
			.as("判死窗口必须仍是配置值（180s）")
			.isEqualTo(Duration.ofSeconds(180).toMillis());
	}

	/** 租约在别人手里 = "有人在管"：即使业务活动超龄也不接管。 */
	@Test
	void sweepDoesNotTakeOverWhenTheLeaseIsHeldElsewhere() {
		seed(RUN_ZOMBIE, RunSnapshot.RUNNING, STALE);
		LeaseService leases = mock(LeaseService.class);
		when(leases.isHeld(anyString())).thenReturn(true);
		this.store.useLeaseFacility(leases);

		Map<String, Object> report = this.runner.sweep();

		assertThat(resumedRunIds(report)).isEmpty();
		assertThat(skippedReasons(report)).containsEntry(RUN_ZOMBIE, "ACTIVE");
	}

	/** 取消旗标与未决外部输入都优先于"超龄"：一个不复活，一个不绕开人审。 */
	@Test
	void sweepSkipsCancelledAndPendingUnresolvedRuns() {
		seed(RUN_CANCELLED, RunSnapshot.SUSPENDED, STALE);
		seed(RUN_PENDING, RunSnapshot.SUSPENDED, STALE);
		when(this.cancellations.cancelState(RUN_CANCELLED))
			.thenReturn(CancellationRegistry.CancelState.CANCELLED);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("tc-sweep-1");
		pending.setName("confirm_publish");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PENDING);
		this.store.seedPending(RUN_PENDING, pending);

		Map<String, Object> report = this.runner.sweep();

		assertThat(resumedRunIds(report)).isEmpty();
		assertThat(skippedReasons(report)).containsEntry(RUN_CANCELLED, "CANCELLED")
			.containsEntry(RUN_PENDING, "PENDING_UNRESOLVED");
	}

	/** 定时器：开关关掉不注册、启动后可停（{@code @PreDestroy} 走同一入口）。 */
	@Test
	void sweepTimerHonoursTheSwitchAndStopsOnShutdown() {
		this.properties.getResume().setRuntimeSweepEnabled(false);
		this.runner.startRuntimeSweep();
		assertThat(this.runner.isRuntimeSweepRunning()).as("开关关闭时不注册定时器").isFalse();

		this.properties.getResume().setRuntimeSweepEnabled(true);
		this.properties.getResume().setRuntimeSweepInterval(Duration.ofMinutes(5));
		this.runner.startRuntimeSweep();
		assertThat(this.runner.isRuntimeSweepRunning()).isTrue();

		this.runner.stopRuntimeSweep();
		assertThat(this.runner.isRuntimeSweepRunning()).as("应用停机/显式停止后不得留线程").isFalse();
	}

	/** 铺一轮：状态 + 消息历史 + 业务活动锚点（活动年龄决定"活/死"）。 */
	private RunSnapshot seed(String runId, String status, Duration businessAge) {
		RunSnapshot snapshot = this.store.create(runId, "s-" + runId, AiContext.empty(), List.of(), this.codec);
		snapshot.setStatus(status);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		// save() 会把 updatedAtMs 刷成当下 —— 超龄现场必须在保存<b>之后</b>铺
		this.store.save(snapshot);
		long atMs = System.currentTimeMillis() - businessAge.toMillis();
		snapshot.setUpdatedAtMs(atMs);
		this.store.appendEvent(runId, Map.of("type", "tool_start", "seq", 1L, "atMs", atMs));
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> resumed(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.getOrDefault("resumed", List.of());
	}

	private static List<String> resumedRunIds(Map<String, Object> report) {
		return resumed(report).stream().map(item -> String.valueOf(item.get("runId"))).toList();
	}

	private static Map<String, String> resumedOutcomes(Map<String, Object> report) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map<String, Object> item : resumed(report)) {
			out.put(String.valueOf(item.get("runId")), String.valueOf(item.get("outcome")));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> skippedReasons(Map<String, Object> report) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map<String, Object> item : (List<Map<String, Object>>) report.getOrDefault("skipped", List.of())) {
			out.put(String.valueOf(item.get("runId")), String.valueOf(item.get("reason")));
		}
		return out;
	}

}
