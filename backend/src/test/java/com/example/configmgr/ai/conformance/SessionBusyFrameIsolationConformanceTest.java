package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.ai.web.AiController;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 不变量 <b>⑧ 409 SESSION_BUSY 时正在运行的会话帧流不受干扰</b>。
 *
 * <p>
 * 这一层跑<b>真实</b>的 {@link AiController}（唯一的 HTTP 入口）+ 真实的 {@link SessionGate}
 * （ADR-5 会话串行化）+ 真实的 {@link RunRegistry}，只把模型侧服务（{@link ResilientChatService}）
 * 换成"按脚本出帧并停在挂起点"的替身 —— 于是可以精确观测"同一 sessionId 的第二轮被 409 拒掉"
 * 对第一轮帧流造成的<b>全部</b>影响。
 *
 * <p>
 * 要证明的是一条否定命题（"不受干扰"）：被拒的第二轮<b>不产生任何帧、不产生任何轮次、
 * 不碰第一轮的写出器</b>，且第一轮拒绝前后的帧流完全连续（seq 1..N 无缺口、终帧收尾）。
 * 否定命题最容易"看起来通过其实什么也没测"，因此每条断言都落在可观测的帧线/归档/轮次索引上，
 * 而不是"没抛异常"。
 */
class SessionBusyFrameIsolationConformanceTest {

	private static final String SESSION_ID = "s-busy-isolation";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper);

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor aiRunExecutor = newExecutor();

	/** runId → 该轮的帧线（由替身服务在自己被调起时挂上）。 */
	private final Map<String, FrameWire> wires = new ConcurrentHashMap<>();

	private final SessionGate sessionGate;

	private final AiController controller;

	private volatile String lastStartedRunId;

	SessionBusyFrameIsolationConformanceTest() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		this.sessionGate = new SessionGate(redis, this.properties);

		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		when(availability.code()).thenReturn("AI_UNAVAILABLE");
		when(availability.reason()).thenReturn("（测试替身）AI 可用");
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);

		this.controller = new AiController(availability, providerOf(this.chatService),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), this.store, this.registry,
				mock(CancellationRegistry.class), redisAvailability, this.aiRunExecutor, this.mapper, this.properties,
				mock(Environment.class));
	}

	@AfterEach
	void tearDown() {
		this.aiRunExecutor.shutdown();
	}

	// ── ⑧ 409 不得干扰正在运行的那一轮 ───────────────────────────────────────

	@Test
	void sessionBusy409LeavesTheRunningTurnsFrameStreamUntouched() throws Exception {
		CountDownLatch firstTurnStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstTurn = new CountDownLatch(1);
		scriptTurns(firstTurnStarted, releaseFirstTurn);

		ResponseEntity<?> first = this.controller.chat(new AiChatRequest(SESSION_ID, "第一轮", null));
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(firstTurnStarted.await(5, TimeUnit.SECONDS)).as("第一轮已开跑（start 帧已出）").isTrue();
		FrameWire running = awaitWire(1);
		String runningRunId = this.lastStartedRunId;
		assertThat(running.types()).containsExactly("start");

		// 同一 sessionId 的第二轮：必须被拒，且回体要能让前端改走重挂（ADR-5）
		ResponseEntity<?> second = this.controller.chat(new AiChatRequest(SESSION_ID, "第二轮", null));
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody()).isInstanceOf(Map.class);
		Map<String, Object> busy = bodyOf(second);
		assertThat(busy).containsEntry("code", "SESSION_BUSY")
				.containsEntry("sessionId", SESSION_ID)
				.containsEntry("runId", runningRunId)
				.containsEntry("reattach", "/api/ai/events/" + runningRunId);

		// 干扰为零：第一轮帧线未多一帧、归档未多一帧、轮次索引未多一条、会话门仍在第一轮手里
		assertThat(running.types()).as("被拒的请求不得往正在运行的帧流里写任何帧（含第二个 start）")
				.containsExactly("start");
		assertThat(this.store.archivedTypes(runningRunId)).containsExactly("start");
		assertThat(this.store.allRunIds()).containsExactly(runningRunId);
		assertThat(this.sessionGate.currentRunId(SESSION_ID)).isEqualTo(runningRunId);

		// 放行第一轮：帧流照旧走到终帧，seq 连续（被拒的请求没有偷走任何序号）
		releaseFirstTurn.countDown();
		running.awaitType("done", 5000L);
		List<Map<String, Object>> frames = running.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		FrameContract.assertConformant(frames);
		assertThat(this.store.allRunIds()).as("被拒的请求不产生轮次").containsExactly(runningRunId);
	}

	// ── ⑧ 拒绝之后会话仍可用、且两轮帧流互不串线 ─────────────────────────────

	@Test
	void afterTheTurnEndsTheSameSessionRunsAgainWithoutCrossTalk() throws Exception {
		CountDownLatch noWait = new CountDownLatch(0);
		scriptTurns(noWait, noWait);

		assertThat(this.controller.chat(new AiChatRequest(SESSION_ID, "第一轮", null)).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		FrameWire firstWire = awaitWire(1);
		firstWire.awaitType("done", 5000L);
		String firstRunId = this.lastStartedRunId;
		awaitSessionRelease();

		assertThat(this.controller.chat(new AiChatRequest(SESSION_ID, "第二轮", null)).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		FrameWire secondWire = theWireOfANewRun(firstRunId);
		secondWire.awaitType("done", 5000L);

		// 两轮各自的帧流都完整、都从 seq 1 起号；互相之间零交叠
		FrameContract.assertConformant(firstWire.frames());
		FrameContract.assertConformant(secondWire.frames());
		assertThat(secondWire.types()).containsExactly("start", "message_start", "delta", "message_end", "done");
		assertThat(firstWire.types()).containsExactly("start", "message_start", "delta", "message_end", "done");
		assertThat(this.store.allRunIds()).hasSize(2).contains(firstRunId);
	}

	// ── ⑤ 重挂端点的参数管线（?lastSeq 只走到回放，不在帧线上可观测） ───────

	/**
	 * {@code GET /api/ai/events/{runId}?lastSeq=N} 的参数管线与未知轮次分支。
	 *
	 * <p>
	 * <b>诚实说明覆盖面</b>：controller 内部自己 {@code new SseEmitter(...)}，JUnit 层截不到那条帧线，
	 * 因此这里只能断言端点语义（200 / 404 UNKNOWN_RUN），补发帧的内容由
	 * {@link ReattachLastSeqConformanceTest} 在写出器层逐帧断言 —— 两层合起来才等于"参数挂上了 +
	 * 回放逐帧正确"；真实 HTTP 上的 SSE 原文证据见 {@code docs/evidence/DC14-微调实施验证.md} §3.6。
	 */
	@Test
	void reattachEndpointAcceptsLastSeqAndRejectsUnknownRuns() {
		this.store.seedRun("run-reattach-endpoint", SESSION_ID);
		this.store.seedArchive("run-reattach-endpoint",
				FrameContract.fixture("start", 1L, "runId", "run-reattach-endpoint", "sessionId", SESSION_ID),
				FrameContract.fixture("suspended", 2L));
		this.store.get("run-reattach-endpoint").setStatus(RunSnapshot.SUSPENDED);

		ResponseEntity<?> reattached = this.controller.events("run-reattach-endpoint", 1L);
		assertThat(reattached.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reattached.getBody()).as("重挂成功时回体是 SSE 流（回放帧写在 controller 自建的 emitter 上）")
				.isInstanceOf(SseEmitter.class);

		ResponseEntity<?> unknown = this.controller.events("run-does-not-exist", null);
		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(bodyOf(unknown)).containsEntry("code", "UNKNOWN_RUN");
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	/** 替身服务：登记轮次快照 → 挂帧线 → start → 等放行 → 正文三段式 → done（与生产 chat() 同序）。 */
	private void scriptTurns(CountDownLatch started, CountDownLatch release) {
		doAnswer(invocation -> {
			String runId = invocation.getArgument(0);
			AiChatRequest request = invocation.getArgument(1);
			SseChatEmitter out = invocation.getArgument(2);
			// 与真实 ResilientChatService#chat 的第一步同效（轮次进索引，"被拒的请求不产生轮次"才有判据）
			this.store.create(runId, request.sessionId(), null, List.of(), null);
			FrameWire wire = FrameWire.attachTo(out);
			this.wires.put(runId, wire);
			this.lastStartedRunId = runId;
			out.start(request.sessionId());
			started.countDown();
			release.await(10, TimeUnit.SECONDS);
			out.textStart();
			out.delta("你好");
			out.textEnd(2);
			out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
			return null;
		}).when(this.chatService).chat(anyString(), any(AiChatRequest.class), any(SseChatEmitter.class));
	}

	/** 等第 {@code expectedCount} 轮的帧线登记上来（chat 在专用线程池里异步开跑）。 */
	private FrameWire awaitWire(int expectedCount) {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			if (this.wires.size() >= expectedCount) {
				break;
			}
			sleepBriefly();
		}
		assertThat(this.wires).as("应当有 %d 轮在跑", expectedCount).hasSize(expectedCount);
		FrameWire last = null;
		for (FrameWire wire : this.wires.values()) {
			last = wire;
		}
		return last;
	}

	private FrameWire theWireOfANewRun(String previousRunId) {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			AtomicReference<String> newRunId = new AtomicReference<>();
			this.wires.keySet().stream().filter(id -> !id.equals(previousRunId)).forEach(newRunId::set);
			if (newRunId.get() != null) {
				return this.wires.get(newRunId.get());
			}
			sleepBriefly();
		}
		throw new IllegalStateException("第二轮未在 5s 内登记：" + this.wires.keySet());
	}

	/** 等会话门释放（轮终态由 controller 的 finally 主动释放，属异步）。 */
	private void awaitSessionRelease() {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			if (this.sessionGate.currentRunId(SESSION_ID) == null) {
				return;
			}
			sleepBriefly();
		}
		throw new IllegalStateException("会话门未在 5s 内释放：" + this.sessionGate.currentRunId(SESSION_ID));
	}

	private static void sleepBriefly() {
		try {
			TimeUnit.MILLISECONDS.sleep(20L);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static ThreadPoolTaskExecutor newExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setThreadNamePrefix("ai-run-conformance-");
		executor.initialize();
		return executor;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

	/** 错误体读取（{@link AiController} 的错误分支返回 JSON Map）。 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
		return (Map<String, Object>) response.getBody();
	}

}
