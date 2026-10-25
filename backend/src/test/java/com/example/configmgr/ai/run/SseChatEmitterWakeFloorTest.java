package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨实例唤醒补帧的 <b>{@code ?lastSeq} 下界</b>（三实例 run 40173316364 的 streaming-kill-a 回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * 客户端带 {@code ?lastSeq=2} 重挂到实例 C，而归档此刻<b>还没有</b>新帧（原驱动 A 已 kill、
 * 接管方 B 尚未出帧）⇒ 回放暂存 0 帧，emitter 级的 {@link SseChatEmitter} 扇出上界
 * （{@code lastFanoutSeq}）停在 0。等 B 出帧后 C 收到唤醒，{@code onWake} 从 0 读归档，
 * 把 <b>1..N 全部重发</b> —— 客户端在"补帧"里先看到 {@code seq=1}，违反
 * "补帧起始 seq 必须 > 客户端 lastSeq"，并与之已看的帧重复。
 *
 * <h2>本批口径</h2>
 * 每个订阅者记自己的补帧下界（{@link SseChatEmitter.Subscription} 的 {@code wakeFloor}）：
 * 重挂时取客户端的 {@code lastSeq}，回放/唤醒都只投 {@code seq > 该下界}；同一 emitter 上的
 * 全量订阅者（下界 0）不受影响（不得被别人的游标饿死）。
 */
class SseChatEmitterWakeFloorTest {

	private static final String RUN_ID = "run-wake-floor";

	private static final String SESSION_ID = "s-wake-floor";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void reattachAtTailDoesNotReplayAlreadySeenFramesOnTheNextCrossInstanceWake() {
		SseChatEmitter writer = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		writer.start(SESSION_ID);
		writer.delta("a");
		assertThat(seqs(this.store.events(RUN_ID))).containsExactly(1L, 2L);

		// 实例 C：客户端带着 lastSeq=2 重挂；归档此刻没有新帧 ⇒ 回放暂存 0，连接保留等实时/唤醒帧
		SseChatEmitter reader = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		FrameWire wire = new FrameWire();
		int staged = reader.replayAndAttach(wire.emitter(), 2L, false);
		assertThat(staged).isZero();
		assertThat(wire.size()).isZero();

		// 实例 B 接管后写入 3、4（共享归档）；C 只收到跨实例唤醒（没有本地 emit）
		this.store.appendEvent(RUN_ID, frame(3, "b3"));
		this.store.appendEvent(RUN_ID, frame(4, "b4"));
		reader.onWake(RUN_ID, 4L);

		assertThat(seqs(wire.frames()))
			.as("补帧必须严格大于客户端 lastSeq（不得把已见的 1..2 补一遍）")
			.containsExactly(3L, 4L);
	}

	@Test
	void aSecondFullReplaySubscriberIsNotStarvedByAnotherSubscribersCursor() {
		SseChatEmitter writer = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		writer.start(SESSION_ID);
		writer.delta("a");

		SseChatEmitter reader = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		FrameWire incremental = new FrameWire();
		assertThat(reader.replayAndAttach(incremental.emitter(), 2L, false)).isZero();
		FrameWire full = new FrameWire();
		assertThat(reader.replayAndAttach(full.emitter(), null, false)).isEqualTo(2);
		assertThat(seqs(full.frames())).containsExactly(1L, 2L);

		this.store.appendEvent(RUN_ID, frame(3, "b3"));
		this.store.appendEvent(RUN_ID, frame(4, "b4"));
		reader.onWake(RUN_ID, 4L);

		assertThat(seqs(incremental.frames())).as("带 lastSeq 的订阅者只收增量").containsExactly(3L, 4L);
		assertThat(seqs(full.frames())).as("全量订阅者不得被别人的游标饿死").containsExactly(1L, 2L, 3L, 4L);
	}

	/**
	 * <b>F3：丢唤醒窗口里的"别人回放把我饿死"</b>（独立复审确认的真实缺口，本批必修）。
	 *
	 * <p>时序：无游标订阅者 B 已收 1..3（共享上界 = 3）→ 另一实例写 4、唤醒丢失 → 另一订阅者带
	 * {@code lastSeq=3} 重挂，它的回放读到 4 并把<b>共享</b> {@code lastFanoutSeq} 推到 4 →
	 * 丢失的唤醒被重试（上界 4）。旧实现用共享上界当读取下界：{@code 4 <= from(4)} 直接早退，
	 * B 在这条连接上<b>永久缺 4</b>（只能靠客户端再重连）。
	 *
	 * <p>正确口径：唤醒补帧的<b>读取下界</b>取所有订阅者已投递游标的<b>最小值</b>，
	 * 每个订阅者只收自己还没见过的帧 —— 别人的回放推进共享上界不再影响我。
	 */
	@Test
	void aLostWakeIsStillDeliveredToACursorlessSubscriberAfterAnotherReplayAdvancesTheSharedBound() {
		SseChatEmitter writer = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		writer.start(SESSION_ID);
		writer.delta("a");
		writer.delta("b");
		assertThat(seqs(this.store.events(RUN_ID))).containsExactly(1L, 2L, 3L);

		// B：无游标订阅者，已回放 1..3（连接保持）
		SseChatEmitter reader = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		FrameWire b = new FrameWire();
		assertThat(reader.replayAndAttach(b.emitter(), null, false)).isEqualTo(3);
		assertThat(seqs(b.frames())).containsExactly(1L, 2L, 3L);

		// 另一实例写 4；唤醒丢失（本拍不调用 onWake）
		this.store.appendEvent(RUN_ID, frame(4, "b4"));

		// 另一个订阅者带 lastSeq=3 重挂：它的回放读到 4，把共享上界推到 4
		FrameWire other = new FrameWire();
		assertThat(reader.replayAndAttach(other.emitter(), 3L, false)).isEqualTo(1);
		assertThat(seqs(other.frames())).containsExactly(4L);

		// 丢失的唤醒被重试：B 必须补到 4；另一个订阅者不得重复收到 4
		reader.onWake(RUN_ID, 4L);

		assertThat(seqs(b.frames())).as("B 不得因为别人的回放推进共享上界而永久缺 seq=4")
			.containsExactly(1L, 2L, 3L, 4L);
		assertThat(seqs(other.frames())).as("唤醒补帧不得给已见过的订阅者重发").containsExactly(4L);
	}

	private static Map<String, Object> frame(long seq, String text) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("seq", seq);
		frame.put("text", text);
		frame.put("atMs", 1_792_913_800_000L + seq);
		return frame;
	}

	private static List<Long> seqs(List<Map<String, Object>> frames) {
		return frames.stream()
			.map(item -> item.get("seq"))
			.filter(java.util.Objects::nonNull)
			.map(item -> ((Number) item).longValue())
			.toList();
	}

}
