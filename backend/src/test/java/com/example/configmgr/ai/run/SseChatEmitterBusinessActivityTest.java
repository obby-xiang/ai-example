package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * R1-09：<b>出帧点</b>必须刷新真实业务活动锚点，且只有心跳不刷新。
 *
 * <p>
 * 修前只有心跳帧调用 {@code touchActivity}，业务帧（start / delta / tool_* / done / error）在出帧点
 * 什么都不写；新落档口（{@code ai:xevents} Stream）也不写 {@code ai:beat}。于是"业务帧在流、活动锚点
 * 不动"，长工具 / 慢吐字超过僵尸窗口就会被别的实例接管。
 *
 * <p>
 * I-5：本组走<b>生产装配</b>（显式注入 {@link FrameStore}，不是兼容的 {@code RunStoreFrameStore}），
 * 并按帧型逐项覆盖 —— "它们都调 emit"是代码事实，不是测试事实。
 */
class SseChatEmitterBusinessActivityTest {

	private static final String RUN_ID = "run-business-anchor";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final RunStore store = mock(RunStore.class);

	private final InMemoryFrameStore frames = new InMemoryFrameStore();

	private final SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.frames, MAPPER);

	static Stream<Arguments> businessFrames() {
		PendingToolCall executed = pending(PendingToolCall.EXECUTED, PendingToolCall.KIND_BACKEND);
		PendingToolCall frontend = pending(PendingToolCall.FRONTEND_RESULT, PendingToolCall.KIND_FRONTEND);
		return Stream.of(
				Arguments.of("start", (Consumer<SseChatEmitter>) emitter -> emitter.start("s-1")),
				Arguments.of("delta", (Consumer<SseChatEmitter>) emitter -> emitter.delta("正文")),
				Arguments.of("tool_start", (Consumer<SseChatEmitter>) emitter -> emitter.toolStart(executed)),
				Arguments.of("tool_result",
						(Consumer<SseChatEmitter>) emitter -> emitter.toolResult(executed, true, false, "已执行")),
				Arguments.of("frontend_tool_result",
						(Consumer<SseChatEmitter>) emitter -> emitter.frontendToolResult(frontend, true, "填好了")),
				Arguments.of("done",
						(Consumer<SseChatEmitter>) emitter -> emitter.done(Map.of(), "model-x", Map.of("cancelled", false))),
				Arguments.of("error", (Consumer<SseChatEmitter>) emitter -> emitter.error("AI_X", "失败")));
	}

	/** 每个非心跳帧都在出帧点推进业务活动锚点（带该帧的 atMs）。 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("businessFrames")
	void businessFramesTouchTheBusinessAnchorAtEmission(String type, Consumer<SseChatEmitter> action) {
		action.accept(this.out);

		verify(this.store, times(1)).touchBusinessActivity(eq(RUN_ID), longThat(atMs -> atMs > 0L));
		verify(this.store, never()).touchActivity(anyString());
	}

	/** 心跳只推进"进程还活着"的戳，绝不推进业务活动锚点。 */
	@Test
	void heartbeatDoesNotTouchTheBusinessAnchor() {
		this.out.heartbeat(Map.of("phase", "streaming"));

		verify(this.store).touchActivity(RUN_ID);
		verify(this.store, never()).touchBusinessActivity(anyString(), anyLong());

		reset(this.store);
		this.out.delta("正文");
		verify(this.store).touchBusinessActivity(eq(RUN_ID), anyLong());
		verify(this.store, never()).touchActivity(anyString());
	}

	/** 生产落档口里只有业务帧；心跳不入档（原有 T7 口径不变）。 */
	@Test
	void archiveContainsBusinessFramesButNotHeartbeats() {
		this.out.start("s-1");
		this.out.heartbeat(Map.of());
		this.out.delta("正文");

		List<Map<String, Object>> archived = this.frames.readFrom(RUN_ID, 0L, 0);
		assertThat(archived).extracting(frame -> frame.get("type")).containsExactly("start", "delta");
		assertThat(archived).allSatisfy(frame -> assertThat(frame).containsKey("atMs"));
	}

	private static PendingToolCall pending(String status, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-1");
		pending.setName("list_tasks");
		pending.setKind(kind);
		pending.setStatus(status);
		return pending;
	}

}
