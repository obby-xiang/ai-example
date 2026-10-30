package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * <b>F1</b>：驱动在 A、客户端重挂到 B（B 不是该 run 的驱动方）时，B 的 attach 连接必须收到
 * <b>本地、无业务 seq</b> 的保活心跳 —— 否则 A 处于请求前压缩 / 慢工具 / 上游静默时，B 一条帧都
 * 收不到，前端 30s 首帧 / 45s 帧间看门狗把正常轮次判死。
 *
 * <h2>钉住的边界</h2>
 * <ul>
 * <li>心跳<b>不入档、不取业务号、不发唤醒</b>（归档/序号/ordered delivery 与真实驱动路径不变）；</li>
 * <li><b>一个写出器一个定时器</b>（不是"每 attach 一个"）；</li>
 * <li><b>终态 / 退订 / 转为驱动方</b>都停机（不漏定时器）；</li>
 * <li>驱动方自己（{@link SseChatEmitter#beginDrive()} 已调用）<b>不</b>发本地心跳 —— 真实心跳来自
 * {@code TurnKeepalive}，不再叠加。</li>
 * </ul>
 */
class SseChatEmitterAttachKeepaliveTest {

	private static final String RUN_ID = "run-attach-keepalive";

	/** 用例节拍（生产缺省 5s；压到 40ms 让断言确定性收敛）。 */
	private static final long BEAT_MS = 40L;

	private final List<String> journal = new CopyOnWriteArrayList<>();

	private final InMemoryFrameStore frames = new InMemoryFrameStore(this.journal);

	private final FakeWakeBus bus = new FakeWakeBus(this.journal);

	private final ObjectMapper mapper = new ObjectMapper();

	private final ScheduledThreadPoolExecutor scheduler = spy(
			new ScheduledThreadPoolExecutor(1, runnable -> {
				Thread thread = new Thread(runnable, "attach-keepalive-test");
				thread.setDaemon(true);
				return thread;
			}));

	@AfterEach
	void shutdownScheduler() {
		this.scheduler.shutdownNow();
	}

	@Test
	void nonDrivingAttachReceivesLocalHeartbeatsWithoutSeqArchiveOrWake() throws Exception {
		SseChatEmitter out = emitter();
		FrameWire wire = new FrameWire();
		out.replayAndAttach(wire.emitter(), null, false);

		await(() -> countHeartbeats(wire) > 0, "attach 连接应在驱动侧静默期间收到本地保活心跳");

		List<Map<String, Object>> received = wire.frames();
		Map<String, Object> firstHeartbeat = received.stream()
			.filter(frame -> "heartbeat".equals(frame.get("type")))
			.findFirst()
			.orElseThrow();
		assertSoftly(softly -> {
			softly.assertThat(firstHeartbeat).as("心跳不得占业务序号").doesNotContainKey("seq");
			softly.assertThat(firstHeartbeat).containsKey("heartbeatSeq");
			softly.assertThat(this.frames.readFrom(RUN_ID, 0L, 0)).as("保活心跳不得入档").isEmpty();
			softly.assertThat(this.bus.published).as("保活心跳不得发布唤醒").isEmpty();
			softly.assertThat(this.journal).as("心跳既不取号也不落档").isEmpty();
			softly.assertThat(out.lastIssuedSeq()).as("业务水位不变").isZero();
			softly.assertThat(out.attachKeepaliveActive()).as("保活任务应在跑").isTrue();
		});

		// 真实业务帧仍然从 1 起号：心跳不占用业务序号空间。
		out.delta("正文");
		List<Map<String, Object>> archived = this.frames.readFrom(RUN_ID, 0L, 0);
		assertThat(archived).extracting(frame -> frame.get("type")).containsExactly("delta");
		assertThat(archived.get(0)).containsEntry("seq", 1L);
		assertThat(wire.frames()).as("业务帧之后仍然只多这一帧业务帧（心跳条数不定）")
			.anySatisfy(frame -> assertThat(frame).containsEntry("type", "delta").containsEntry("seq", 1L));
	}

	@Test
	void oneKeepaliveTaskPerEmitterRegardlessOfHowManyAttachConnections() throws Exception {
		SseChatEmitter out = emitter();
		FrameWire first = new FrameWire();
		FrameWire second = new FrameWire();
		out.replayAndAttach(first.emitter(), null, false);
		out.replayAndAttach(second.emitter(), null, false);

		await(() -> countHeartbeats(first) > 0 && countHeartbeats(second) > 0,
				"两条 attach 连接都应收到本地保活");
		assertThat(out.subscriberCount()).isEqualTo(2);
		verify(this.scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(),
				any(TimeUnit.class));
	}

	@Test
	void becomingTheDriverStopsTheLocalKeepalive() throws Exception {
		SseChatEmitter out = emitter();
		FrameWire wire = new FrameWire();
		out.replayAndAttach(wire.emitter(), null, false);
		await(() -> countHeartbeats(wire) > 0, "先证明保活在跑");

		out.beginDrive();
		await(() -> !out.attachKeepaliveActive(), "本实例转为驱动方后本地保活必须停机");
		int settled = wire.size();
		Thread.sleep(BEAT_MS * 4);
		assertThat(wire.size()).as("停机后不得再有本地心跳（真实心跳由 TurnKeepalive 发）").isEqualTo(settled);
	}

	@Test
	void terminalCompleteStopsTheLocalKeepalive() throws Exception {
		SseChatEmitter out = emitter();
		FrameWire wire = new FrameWire();
		out.replayAndAttach(wire.emitter(), null, false);
		await(() -> countHeartbeats(wire) > 0, "先证明保活在跑");

		out.complete();
		await(() -> !out.attachKeepaliveActive(), "终态后本地保活必须停机");
		int settled = wire.size();
		Thread.sleep(BEAT_MS * 4);
		assertThat(wire.size()).as("终帧之后不得再入队任何帧（含心跳）").isEqualTo(settled);
	}

	@Test
	void lastUnsubscribeStopsTheLocalKeepalive() throws Exception {
		SseChatEmitter out = emitter();
		CapturingEmitter subscriber = new CapturingEmitter();
		out.replayAndAttach(subscriber.emitter, null, false);
		await(() -> countHeartbeats(subscriber.payloads) > 0, "先证明保活在跑");

		subscriber.completion.run();
		await(() -> !out.attachKeepaliveActive(), "最后一个订阅者离开后本地保活必须停机");
		assertThat(out.subscriberCount()).isZero();
		int settled = subscriber.payloads.size();
		Thread.sleep(BEAT_MS * 4);
		assertThat(subscriber.payloads.size()).as("退订后不得再向该连接写帧").isEqualTo(settled);
	}

	@Test
	void aDrivingEmitterNeverStartsTheLocalKeepalive() throws Exception {
		SseChatEmitter out = emitter();
		// 驱动入口先于 attach（AiController#chat 的顺序：attach 之后立刻 beginDrive；
		// 这里取更严格的一侧：连 attach 都不应起保活）。
		out.beginDrive();
		FrameWire wire = new FrameWire();
		out.attach(wire.emitter());

		Thread.sleep(BEAT_MS * 4);
		assertThat(out.attachKeepaliveActive()).as("驱动方不启用本地保活").isFalse();
		assertThat(wire.frames()).as("驱动方连接不应收到本地心跳").isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter emitter() {
		SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), this.frames, this.mapper,
				Runnable::run, AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), this.bus, null);
		out.enableAttachKeepalive(this.scheduler, BEAT_MS);
		return out;
	}

	private static long countHeartbeats(FrameWire wire) {
		return countHeartbeats(wire.payloads());
	}

	private static long countHeartbeats(List<String> payloads) {
		return payloads.stream().filter(payload -> payload.contains("\"type\":\"heartbeat\"")).count();
	}

	private static void await(BooleanSupplier condition, String description) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(10L);
		}
		throw new AssertionError("等待超时：" + description);
	}

	/** 记录帧原文并捕获 {@code onCompletion} 的订阅者桩（退订路径用）。 */
	private static final class CapturingEmitter {

		private final List<String> payloads = new CopyOnWriteArrayList<>();

		private volatile Runnable completion = () -> {
		};

		private final SseEmitter emitter = mock(SseEmitter.class);

		private CapturingEmitter() {
			try {
				doAnswer(invocation -> {
					this.payloads.add(String.valueOf((Object) invocation.getArgument(0)));
					return null;
				}).when(this.emitter).send(org.mockito.ArgumentMatchers.anyString());
			}
			catch (IOException ex) {
				throw new IllegalStateException("桩装配失败", ex);
			}
			doAnswer(invocation -> {
				this.completion = invocation.getArgument(0);
				return null;
			}).when(this.emitter).onCompletion(any(Runnable.class));
		}

	}

}
