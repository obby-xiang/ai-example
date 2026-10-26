package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>独立保活定时器</b>（AR-06 / Task 5 Step 3）的帧面契约。
 *
 * <p>三条被钉住的事实：
 * <ol>
 * <li><b>保活不依赖任何事件</b>：这里没有任何上游事件、没有任何压缩调用 —— 节拍照样按墙钟出帧
 *     （旧口径把心跳挂在上游事件回调里，因此"上游不出事件 = 没有保活"）；</li>
 * <li><b>心跳口径不变</b>：{@code heartbeat} 不占业务 {@code seq}、不进事件归档，只带独立
 *     {@code heartbeatSeq}；{@code phase}/{@code elapsedMs}/{@code attempt} 是 additive 键；</li>
 * <li><b>停表即静默</b>：{@link TurnKeepalive#stop()} 之后不再有任何帧（终态/取消/异常/park 四条
 *     出口都靠这一条保证"终帧之后无帧"）。</li>
 * </ol>
 *
 * <p>证红方式：把节拍改回"由上游事件驱动" ⇒ 第 1 条红（等两拍仍无帧）；把阶段帧改成业务帧 ⇒
 * 第 2 条红（归档出现 heartbeat / 业务 seq 出现洞）；去掉 {@code stop()} 的 cancel ⇒ 第 3 条红。
 */
class TurnKeepaliveTest {

	private static final String RUN_ID = "run-keepalive";

	private static final String SESSION_ID = "s-keepalive";

	/** 节拍（毫秒）：比生产 5s 小两个数量级，让"等两拍"在用例里是几十毫秒的事。 */
	private static final long BEAT_MS = 30L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private SseChatEmitter out;

	private FrameWire wire;

	private ScheduledExecutorService scheduler;

	@BeforeEach
	void setUp() {
		this.out = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		this.wire = FrameWire.attachTo(this.out);
		this.out.start(SESSION_ID);
		this.scheduler = Executors.newScheduledThreadPool(1, runnable -> {
			Thread thread = new Thread(runnable, "keepalive-test");
			thread.setDaemon(true);
			return thread;
		});
	}

	@AfterEach
	void tearDown() {
		this.scheduler.shutdownNow();
	}

	/** 上游全程无事件也照样保活：节拍只取决于墙钟。 */
	@Test
	void beatsWithoutAnyUpstreamOrCompactionActivity() {
		TurnKeepalive keepalive = TurnKeepalive.start(RUN_ID, this.out, this.scheduler, BEAT_MS,
				TurnKeepalive.PHASE_STREAMING);
		try {
			assertThat(awaitBeats(2)).as("两拍之内至少两条心跳").isGreaterThanOrEqualTo(2);
		}
		finally {
			keepalive.close();
		}
	}

	/** 心跳口径：不占业务 seq、不入归档、独立 heartbeatSeq、带 phase/elapsedMs/attempt。 */
	@Test
	void heartbeatKeepsItsOwnSequenceSpaceAndNeverEntersTheArchive() {
		TurnKeepalive keepalive = TurnKeepalive.start(RUN_ID, this.out, this.scheduler, BEAT_MS,
				TurnKeepalive.PHASE_COMPACTING);
		try {
			keepalive.attempt(2);
			assertThat(awaitBeats(2)).isGreaterThanOrEqualTo(2);
		}
		finally {
			keepalive.close();
		}
		this.out.done(Map.of(), "test-model", Map.of("cancelled", false),
				Map.of("usedTokens", 1, "limitTokens", 2, "percent", 50.0, "level", "none", "est", true,
						"circuit", "closed"));

		List<Map<String, Object>> frames = this.wire.frames();
		List<Map<String, Object>> beats = FrameContract.ofType(frames, FrameContract.HEARTBEAT);
		assertThat(beats).isNotEmpty();
		assertThat(beats).allSatisfy(beat -> assertThat(beat).doesNotContainKey("seq")
			.containsEntry("phase", TurnKeepalive.PHASE_COMPACTING)
			.containsKeys("elapsedMs", "attempt", "heartbeatSeq"));
		assertThat(beats).extracting(beat -> ((Number) beat.get("heartbeatSeq")).longValue())
			.as("独立号空间自 1 起、逐拍递增")
			.startsWith(1L);
		assertThat(this.store.events(RUN_ID)).extracting(frame -> frame.get("type"))
			.as("心跳不入业务归档")
			.doesNotContain(FrameContract.HEARTBEAT);
		// 业务帧仍是 1、2（start、done），心跳没有插进业务号
		assertThat(frames.stream()
			.filter(frame -> !FrameContract.HEARTBEAT.equals(FrameContract.type(frame)))
			.map(frame -> ((Number) frame.get("seq")).longValue()))
			.containsExactly(1L, 2L);
	}

	/** 停表之后不再有任何帧：终态/取消/异常/park 四条出口共用这一条保证。 */
	@Test
	void stopSilencesTheTimerForGood() {
		TurnKeepalive keepalive = TurnKeepalive.start(RUN_ID, this.out, this.scheduler, BEAT_MS,
				TurnKeepalive.PHASE_STREAMING);
		assertThat(awaitBeats(1)).isGreaterThanOrEqualTo(1);

		keepalive.stop();
		int settled = FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT).size();
		sleep(BEAT_MS * 4);
		assertThat(FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT))
			.as("停表后一拍都不许再出（终帧之后不得有心跳）")
			.hasSize(settled);

		keepalive.rearm();
		assertThat(awaitBeats(settled + 1)).as("继续同一轮时重新武装").isGreaterThan(settled);
		keepalive.close();

		int afterClose = FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT).size();
		sleep(BEAT_MS * 4);
		assertThat(FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT)).hasSize(afterClose);
	}

	/** 阶段开始帧：同步发出（调用返回时已在帧线上）、{@code elapsedMs=0}、相位如实。 */
	@Test
	void stageFrameIsEmittedSynchronouslyBeforeAnySyncWork() {
		TurnKeepalive keepalive = TurnKeepalive.startWithStageFrame(RUN_ID, this.out, this.scheduler, BEAT_MS,
				TurnKeepalive.PHASE_COMPACTING);
		try {
			List<Map<String, Object>> beats = FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT);
			assertThat(beats).as("阶段开始帧由调用线程同步发出（不排在下一拍）").hasSize(1);
			assertThat(beats.get(0)).containsEntry("phase", TurnKeepalive.PHASE_COMPACTING)
				.containsEntry("elapsedMs", 0L)
				.containsEntry("attempt", 0L);
		}
		finally {
			keepalive.close();
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 等到心跳条数 >= target（最多 ~2s）；返回实际条数。 */
	private int awaitBeats(int target) {
		long deadline = System.currentTimeMillis() + 2_000L;
		while (System.currentTimeMillis() < deadline) {
			int beats = FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT).size();
			if (beats >= target) {
				return beats;
			}
			sleep(10L);
		}
		return FrameContract.ofType(this.wire.frames(), FrameContract.HEARTBEAT).size();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
