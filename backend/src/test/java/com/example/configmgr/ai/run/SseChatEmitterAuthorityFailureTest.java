package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>写权限判定抛异常 = 不可知 = UNAVAILABLE</b>（fencing 收口补丁）。
 *
 * <h2>缺陷形态</h2>
 * {@code SseChatEmitter#emit} 的判定原本是：
 * <pre>{@code
 * try { authority = store.runLeaseAuthority(runId); }
 * catch (RuntimeException ex) { authority = null; } // "按原语义发送"
 * if (authority != null && authority.readOnly()) return;
 * }</pre>
 * 于是"判定本身失败"（Redis 抖动、租约视图读取异常）被吞成 {@code null}，业务帧继续
 * <b>取号 → 落档 → 发布 → 扇出</b>。用户批准的契约是"租约不可知时只读恢复"：
 * 不可知既不是"持有"，也不是"从未持租"，不得 plain 放行 —— 否则丢租的旧主人会在
 * 事件日志里继续写业务帧、唤醒其它实例、并给本地订阅者发终帧。
 *
 * <h2>本用例的口径</h2>
 * 让 {@code runLeaseAuthority} 每次抛 {@link IllegalStateException}：
 * <ul>
 * <li>业务帧（{@code done}/{@code delta}）<b>不取号、不落档、不 publish、不扇出</b>；</li>
 * <li>心跳仍然投递（它不写业务状态，只留"进程还活着"的戳）—— 豁免保持。</li>
 * </ul>
 */
class SseChatEmitterAuthorityFailureTest {

	private static final String RUN_ID = "run-authority-failure";

	private final List<String> journal = new CopyOnWriteArrayList<>();

	private final InMemoryFrameStore frames = new InMemoryFrameStore(this.journal);

	private final FakeWakeBus bus = new FakeWakeBus(this.journal);

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void authorityFailureTurnsBusinessFramesReadOnlyButKeepsHeartbeats() {
		RunStore store = mock(RunStore.class);
		when(store.runLeaseAuthority(RUN_ID)).thenThrow(new IllegalStateException("租约视图不可读（模拟）"));
		SseChatEmitter out = emitter(store);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("不该落档的正文");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		assertThat(this.journal).as("不可知 ⇒ 不得取号/落档/发布").isEmpty();
		assertThat(this.frames.readFrom(RUN_ID, 0L, 0)).as("不得写事件日志").isEmpty();
		assertThat(this.bus.published).as("不得 publish 唤醒").isEmpty();
		assertThat(wire.payloads()).as("不得向订阅者扇出业务帧（含终帧）").isEmpty();
		verify(store, times(2)).runLeaseAuthority(RUN_ID);

		// 心跳豁免：不写业务状态，仍须投递（重挂/判活依赖它）
		out.heartbeat(Map.of("phase", "suspended"));

		List<Map<String, Object>> received = FrameWire.normalize(wire.frames());
		assertThat(received).hasSize(1);
		assertThat(received.get(0)).containsEntry("type", "heartbeat").doesNotContainKey("seq");
		assertThat(this.journal).as("心跳不取号/不落档/不发布").isEmpty();
		assertThat(this.bus.published).isEmpty();
		verify(store).touchActivity(RUN_ID);
	}

	private SseChatEmitter emitter(RunStore store) {
		return new SseChatEmitter(RUN_ID, store, this.frames, this.mapper, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), this.bus, null);
	}

}
