package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * <b>L-91：思考段（reasoning）帧契约</b>—— {@code SseChatEmitter} 侧的形态锁。
 *
 * <h2>为什么是"喂键"不是"加帧型"</h2>
 * 帧类型集仍是 16（{@code FrameTypeSetFrozenTest} 不动）：思考段复用既有的三段式边界
 * {@code message_start/message_end} 的 {@code kind=reasoning}，增量复用 {@code delta} 的
 * <b>additive 可选键</b> {@code kind}：
 * <ul>
 * <li>{@code kind} 缺省 = 正文 → <b>正文 delta 的键集合逐字不动</b>（老客户端/旧归档零影响）；</li>
 * <li>{@code kind='reasoning'} = 思考段增量。前端据它分流到 reasoning 段（{@code parts.ts}）。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>给正文 {@code delta} 补一个恒为 {@code "text"} 的 kind ⇒ 本类第 1 条
 *     "正文 delta 不带 kind 键"变红（那是键集合扩张，不是零影响的增量）；</li>
 * <li>去掉 {@link SseChatEmitter#reasoningEnd()} 在 {@code textStart/textEnd} 里的收口
 *     ⇒ 第 2/3 条的"思考段先闭合再开正文/收终帧"变红（帧序：思考段永远开着）。</li>
 * </ul>
 */
class SseChatEmitterReasoningFramesTest {

	private static final String RUN_ID = "run-reasoning";

	private final RunStore store = mock(RunStore.class);

	/** 归档面：当前由 FrameStore 拥有（RunStore 不再持有 events）。 */
	private final InMemoryFrameStore frames = new InMemoryFrameStore();

	/** 投递身份作用域：写出器按身份解析后才能归档/取号。 */
	private RequestContextUtils.SafeCloseable identity;

	private SseChatEmitter emitter;

	@BeforeEach
	void setUpEmitter() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
		this.emitter = new SseChatEmitter(RUN_ID, this.store, this.frames, new ObjectMapper(), Runnable::run, 256,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), null, null, null);
	}

	@AfterEach
	void closeIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void reasoningSegmentIsASeparateClosedSegmentAndTextDeltasKeepTheOldKeySet() {
		this.emitter.start("s-1");
		this.emitter.reasoningDelta("先想");
		this.emitter.reasoningDelta("一步");
		this.emitter.textStart();
		this.emitter.delta("答案是 2");
		this.emitter.textEnd(4);
		this.emitter.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> archived = archivedFrames();

		assertThat(types(archived)).containsExactly("start", "message_start", "delta", "delta", "message_end",
				"message_start", "delta", "message_end", "done");
		assertThat(archived).extracting(frame -> frame.get("seq")).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

		// ① 思考段边界：kind=reasoning，消息身份同一份（T5 起 messageId 是协议事实）
		assertThat(archived.get(1)).containsEntry("kind", "reasoning")
				.containsEntry("messageId", "assistant-" + RUN_ID);
		assertThat(archived.get(4)).containsEntry("kind", "reasoning").containsEntry("chars", 4L)
				.containsEntry("messageId", "assistant-" + RUN_ID);

		// ② 思考 delta：kind=reasoning 且文本是思考原文
		assertThat(archived.get(2)).containsEntry("kind", "reasoning").containsEntry("text", "先想");
		assertThat(archived.get(3)).containsEntry("kind", "reasoning").containsEntry("text", "一步");

		// ③ 正文 delta 的键集合逐字不动（additive 口径：老客户端读到的帧与本批之前完全一致）
		assertThat(archived.get(6)).doesNotContainKey("kind").containsEntry("text", "答案是 2");
		assertThat(archived.get(6).keySet()).containsExactlyInAnyOrder("type", "atMs", "messageId", "text", "runId",
				"seq");

		// ④ 正文段 chars 只数正文（思考文本不进正文对账）
		assertThat(((Number) archived.get(7).get("chars")).longValue()).isEqualTo(4L);
		assertThat(archived.get(7)).containsEntry("kind", "text");
	}

	@Test
	void openReasoningSegmentIsClosedBeforeTheBodyStarts() {
		this.emitter.reasoningDelta("想了想");
		this.emitter.textStart();

		List<Map<String, Object>> archived = archivedFrames();

		assertThat(types(archived)).containsExactly("message_start", "delta", "message_end", "message_start");
		assertThat(archived.get(0)).containsEntry("kind", "reasoning");
		assertThat(archived.get(2)).containsEntry("kind", "reasoning").containsEntry("chars", 3L);
		assertThat(archived.get(3)).containsEntry("kind", "text");
	}

	@Test
	void pureReasoningTurnIsClosedByTheTerminalPath() {
		// 只吐思考、正文一个字都没有（思考中即收尾）：段边界也必须闭合，不能"开着段就下班"
		this.emitter.reasoningDelta("只想了没答");
		this.emitter.textEnd(0);
		this.emitter.done(Map.of(), "deepseek-flash", Map.of("cancelled", true));

		List<Map<String, Object>> archived = archivedFrames();

		assertThat(types(archived)).containsExactly("message_start", "delta", "message_end", "done");
		assertThat(archived.get(2)).containsEntry("kind", "reasoning").containsEntry("chars", 5L);
	}

	@Test
	void emptyReasoningChunkEmitsNoFrame() {
		// 上游每帧都带 reasoning_content=""（spring-ai 缺省占位）：不得每帧发一个空思考 delta
		this.emitter.reasoningDelta("");
		this.emitter.reasoningDelta(null);

		assertThat(archivedFrames()).isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static List<String> types(List<Map<String, Object>> frames) {
		return frames.stream().map(frame -> String.valueOf(frame.get("type"))).toList();
	}

	/**
	 * 归档帧（当前由注入的 {@link FrameStore} 拥有）：与实时投递的是同一份出帧逻辑产物。
	 */
	private List<Map<String, Object>> archivedFrames() {
		return this.frames.readFrom(RUN_ID, 0L, 0);
	}

}
