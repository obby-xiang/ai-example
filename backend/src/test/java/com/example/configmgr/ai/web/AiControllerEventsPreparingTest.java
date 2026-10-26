package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FrameStore;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>AR-06："已 start、快照未建"阶段的重挂语义</b>（{@code GET /api/ai/events/{runId}}）。
 *
 * <h2>被钉住的缺陷</h2>
 * 首帧（{@code start}）前移到首轮压缩之前以后，存在一段合法窗口：runId 已有、帧面已开、快照还没建立。
 * 旧读侧只有一条"快照为 null ⇒ 404 {@code UNKNOWN_RUN}"的分支，于是<b>正常</b>的请求前压缩
 * 会被显示成"轮不存在"（用户看到的是失败，而不是"正在准备"）。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>归档里有帧、末帧不是终帧 ⇒ <b>202</b> + {@code preparing=true} + {@code elapsedMs}
 *     + {@code retryAfterMs}（可重试、带已用时间），<b>不</b>返回 404、<b>不</b>说"轮不存在"；</li>
 * <li>归档末帧已是终帧（快照被 TTL 先回收）⇒ 照常回放（200 SSE），比"准备中"诚实；</li>
 * <li>归档一帧都没有 ⇒ 仍 404（那确实是不存在的轮）；</li>
 * <li>归档末号读不出来（Redis 抖动）⇒ 503 + {@code degraded}，不得冒充"没有这一轮"；</li>
 * <li>该窗口里已取消 ⇒ 响应体加 {@code cancelled=true}（取消与"准备中"共存，不写快照）。</li>
 * </ul>
 *
 * <p>证红方式：把 {@code events} 的 {@code snapshot == null} 分支改回直接 404 ⇒ 第一条红；
 * 把"末帧是终帧"放行逻辑删掉 ⇒ 第二条红（202 而不是 200）；
 * 把归档判据换成"有 runRegistry 条目就算准备中" ⇒ 第三条红（未知轮不再 404）。
 */
class AiControllerEventsPreparingTest {

	private static final String RUN_ID = "run-preparing";

	private static final String SESSION_ID = "s-preparing";

	private final ObjectMapper mapper = new ObjectMapper();

	/** 没有快照的 Store：正是"已 start、快照未建"这段。 */
	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final FrameStore frameStore = mock(FrameStore.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private AiController controller;

	@BeforeEach
	void setUp() {
		this.controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), this.store, this.registry, this.cancellations,
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper, this.properties,
				mock(Environment.class), mock(JobWatchdog.class));
		ReflectionTestUtils.setField(this.controller, "frameStore", this.frameStore);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
	}

	@Test
	void reattachDuringPreparationReturnsPreparingWithElapsedInsteadOfUnknownRun() {
		long startedAtMs = System.currentTimeMillis() - 12_000L;
		armArchive(List.of(startFrame(startedAtMs)), 1L);

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value()).as("准备中不是错误：202，不是 404").isEqualTo(202);
		assertThat(response.getBody()).isInstanceOf(Map.class).isNotInstanceOf(SseEmitter.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).containsEntry("preparing", true)
			.containsEntry("runId", RUN_ID)
			.containsEntry("degraded", false)
			.containsEntry("retryAfterMs", 1000L)
			.containsEntry("reattach", "/api/ai/events/" + RUN_ID)
			.as("不是错误码：客户端按 preparing 判定")
			.doesNotContainKey("code");
		assertThat(((Number) body.get("elapsedMs")).longValue()).as("带已用时间（服务端钟）")
			.isGreaterThanOrEqualTo(10_000L);
		assertThat(String.valueOf(body.get("message"))).isNotBlank();
	}

	@Test
	void reattachWhenTheArchiveAlreadyEndsWithATerminalReplaysInsteadOfPreparing() {
		// 快照被 TTL 先回收，但归档里已经有终帧：回放 > "准备中"（客户端还能拿到真正的结局）
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(3L));
		when(this.frameStore.readFrom(eq(RUN_ID), eq(0L), eq(1)))
			.thenReturn(List.of(startFrame(System.currentTimeMillis() - 60_000L)));
		when(this.frameStore.readFrom(eq(RUN_ID), eq(2L), eq(1)))
			.thenReturn(List.of(FrameContract.fixture("done", 3L, "cancelled", false)));
		when(this.frameStore.readFrom(eq(RUN_ID), eq(0L), eq(0))).thenReturn(List.of(
				startFrame(System.currentTimeMillis() - 60_000L),
				FrameContract.fixture("done", 3L, "cancelled", false)));

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value()).as("回放路径仍是 200 SSE").isEqualTo(200);
		assertThat(response.getBody()).isInstanceOf(SseEmitter.class);
	}

	@Test
	void unknownRunWithAnEmptyArchiveIsStill404() {
		armArchive(List.of(), 0L);

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).containsEntry("code", ErrorCodes.UNKNOWN_RUN);
	}

	@Test
	void unreadableArchiveTailIs503Not404() {
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.empty());

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value()).as("读失败 ≠ 没有这一轮").isEqualTo(503);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).containsEntry("code", ErrorCodes.AI_REDIS_UNAVAILABLE).containsEntry("degraded", true);
	}

	@Test
	void cancelledPreparationIsMarkedWithoutWritingAnySnapshot() {
		long startedAtMs = System.currentTimeMillis() - 3_000L;
		armArchive(List.of(startFrame(startedAtMs)), 1L);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value()).isEqualTo(202);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).containsEntry("cancelled", true);
		assertThat(this.store.get(RUN_ID)).as("读侧不得凭空造快照（禁止裸 save）").isNull();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 归档只有 start（没有终帧）⇒ "准备中"窗口。 */
	private void armArchive(List<Map<String, Object>> frames, long lastSeq) {
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(lastSeq));
		when(this.frameStore.readFrom(eq(RUN_ID), anyLong(), eq(1))).thenReturn(frames);
	}

	private static Map<String, Object> startFrame(long atMs) {
		return FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID, "atMs", atMs);
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
