package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 不变量 <b>①②④</b> 在<b>真实编排层</b>（{@link ResilientChatService}）上的一致性用例。
 *
 * <p>
 * 与 {@link SseFrameSequenceConformanceTest}（只看写出器）不同，这里把模型侧做成可控的假上游
 * （{@link ChatModel} 桩返回脚本化的分片流），跑的是<b>真正的</b> {@code runAttempts} 循环：
 * 首包、正文三段式、终帧收尾、断流重试、取消收尾全部走生产代码路径，因此"帧序"是端到端形态，
 * 而不是"我手工按实现顺序摆出来的样子"。
 *
 * <p>
 * 三个用例分别锁：① 正常一轮（delta 拼接 = 落定正文、终帧收尾）；
 * ② 断开/缺终帧 → 重试帧 → error 终帧，且 {@code retry} 绝不落在已产出内容之后；
 * ③ 取消 → 也是 {@code done}（{@code cancelled=true}）终帧，且终帧之后无帧。
 * 另有两条修复后回归：④ 纯空白分片也让"delta 拼接 / message_end.chars / 落定正文"三者相等（S5c-4）、
 * ⑤ 已产出半截正文后失败时先关正文段再发 {@code error} 终帧（S5c-5）。
 */
class ChatTurnFrameSequenceConformanceTest {

	private static final String RUN_ID = "run-chat-turn";

	private static final String SESSION_ID = "s-chat-turn";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "conformance-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.chatClient, this.chatModel, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService,
				this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	// ── ①②④ 正常一轮 ───────────────────────────────────────────────────────

	@Test
	void fullTurnFrameSequenceIsConformantAndDeltaConcatMatchesTheSettledText() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你好"), chunk("，世界"),
				terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "delta",
				"message_end", "done");
		assertThat(FrameContract.types(wire.frames())).as("续跑轮不重发 start（首包归属首轮，已在归档里）")
				.containsExactly("message_start", "delta", "delta", "message_end", "done");
		FrameContract.assertConformant(frames);

		String concatenated = concatenatedDeltas(frames);
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("chars",
				(long) concatenated.length());
		// 帧流拼接所得 == 落定正文（前端渲染与落库正文同源）
		assertThat(concatenated).isEqualTo("你好，世界");
		assertThat(this.store.get(RUN_ID).getFinalText()).isEqualTo(concatenated);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("cancelled", false)
				.containsEntry("attempts", 1L)
				.containsEntry("terminalSignal", "finishReason=stop");
		// 重挂回放能把整轮拼回来（回放跳过 delta，但状态帧齐全）
		FrameWire reattached = new FrameWire();
		this.registry.of(RUN_ID).replayTo(reattached.emitter(), null);
		assertThat(FrameContract.types(reattached.frames())).containsExactly("start", "message_start", "message_end",
				"done");
	}

	// ── ④ 断开重试 → error 终帧 ─────────────────────────────────────────────

	/**
	 * 上游"安静地结束"（无 finishReason 终帧）= 断流（硬规范②）：第一次尝试按可重试处理，
	 * 于是先出 {@code retry} 公告，第二次仍失败 → {@code error} 终帧，且 error 是最后一帧。
	 *
	 * <p>
	 * kill：去掉重试三条件里的"未产出内容"（已产出也重试）⇒ {@code retry} 会出现在 delta 之后，
	 * {@link FrameContract#orderingContract} 立刻变红。
	 */
	@Test
	void incompleteUpstreamRetriesThenEndsWithAnErrorTerminal() {
		// 上游一个事件都没有正文、也没有 finishReason：每轮都判"缺终帧"
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(emptyChunk()));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "retry", "error");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "retry")).containsEntry("nextAttempt", 2L)
				.containsEntry("code", StreamViolationException.INCOMPLETE);
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", StreamViolationException.INCOMPLETE);
		assertThat(FrameContract.ofType(wire.frames(), "delta")).as("两次尝试都未产出内容（重试的前提）").isEmpty();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(this.store.get(RUN_ID).getAttempts()).isEqualTo(2);
	}

	// ── ④ 取消也走合法终帧 ──────────────────────────────────────────────────

	/**
	 * 取消不是"异常收场"：它同样以 {@code done}（{@code cancelled=true}）作为唯一终帧，
	 * 前端不必为"取消"另写一套终态处理；且取消终帧之后同样没有任何帧。
	 */
	@Test
	void cancelledTurnEndsWithDoneCancelledAndClosesTheTextSegment() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(chunk("半截")),
				Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "本轮对话已被取消"))));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("cancelled", true)
				.containsEntry("partialChars", 2L);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(FrameContract.ofType(frames, "error")).as("取消不得走 error 终帧").isEmpty();
		assertThat(FrameContract.ofType(wire.frames(), "done")).as("取消终帧是本轮实时流的最后一帧").hasSize(1);
	}

	// ── S5c-4 回归：纯空白分片不再让"delta 拼接"与"落定正文"分家 ──────────────

	/**
	 * <b>【S5c-4 回归】</b>模型单独吐一个纯空白分片（空格/换行 —— 流式分片边界上很常见）时，
	 * 它同样要发 {@code delta}：正文三处口径（delta 拼接 = {@code message_end.chars} = 落定正文）归一。
	 *
	 * <p>
	 * 事实链（修复前）：{@code consume} 只在 {@code StringUtils.hasText(text)} 为真时发 {@code delta}，
	 * 而同一个分片<b>无条件</b>进 {@code Accumulator}。于是 {@code message_end.chars}=5、
	 * 前端按 delta 拼接=4（少一个空格）、{@code finalText}=5 —— 渲染与落定正文分家。
	 *
	 * <p>
	 * kill：把发帧判据改回 {@code StringUtils.hasText}（或让累计器跳过空白分片）⇒
	 * 本用例的 chars / delta 拼接 / finalText 三者相等的断言变红。
	 */
	@Test
	void whitespaceChunkIsEmittedAsDeltaSoConcatEqualsCharsAndSettledText() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你好"), chunk(" "), chunk("世界"),
				terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空白分片也发 delta（三个分片 = 三个 delta）")
				.containsExactly("start", "message_start", "delta", "delta", "delta", "message_end", "done");
		FrameContract.assertConformant(frames);

		String concatenated = concatenatedDeltas(frames);
		assertThat(concatenated).as("空白被保住（不是被吞掉）").isEqualTo("你好 世界");
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("chars",
				(long) concatenated.length());
		assertThat(this.store.get(RUN_ID).getFinalText()).as("帧流拼接 == 落定正文（前端渲染与落库正文同源）")
				.isEqualTo(concatenated);
	}

	// ── S5c-5 回归：失败路径也先关正文段再发终帧 ─────────────────────────────

	/**
	 * <b>【S5c-5 回归】</b>"已产出半截正文后上游报错"这一形态下，运行关闭前必须先把它打开的
	 * 文本段关掉：帧序是 {@code delta → message_end → error}，而不是"以一条仍开着的文本消息收尾"。
	 *
	 * <p>
	 * 判据借自 ag-ui 的 {@code open-message-at-run-finished-fatal}：运行关闭前，
	 * 它打开的一切都必须先关闭。修复前三条终局里只有 {@code finish()} 与 {@code cancelTerminal()}
	 * 会 {@code textEnd}，{@code fail()} 直接发 {@code error}。
	 *
	 * <p>
	 * kill：去掉 {@code ResilientChatService#fail} 里的 {@code out.textEnd(partialChars)}
	 * ⇒ 帧流变成 {@code … delta, error}，{@code FrameContract.textSegmentContract} 报
	 * "轮次已收终帧，但正文段没有 message_end"，本用例变红。
	 */
	@Test
	void partialContentThenUpstreamFailureClosesTheTextSegmentBeforeTheErrorTerminal() {
		// 已产出正文后上游报错：不满足重试三条件（已产出内容）⇒ 直接以 error 收尾
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(chunk("半截")),
				Flux.error(new StreamViolationException(StreamViolationException.STREAM_ERROR, "上游流中断"))));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("chars", 2L);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", StreamViolationException.STREAM_ERROR + "_AFTER_PARTIAL");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(FrameContract.ofType(wire.frames(), "error")).as("错误终帧是本轮实时流的最后一帧").hasSize(1);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 跑一轮续跑驱动（生产里由 ResumeService 调用），返回挂上帧线的写出器所收到的帧。 */
	private FrameWire runTurn() {
		// 首轮的 start 帧早已落档（续跑不重发、序号也从归档续号）：先铺进归档，再跑本轮
		this.store.seedArchive(RUN_ID,
				FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		return wire;
	}

	/**
	 * <b>客户端最终持有的完整帧流</b> = 归档回放的状态帧（首轮的 start 等历史帧 + 本轮状态帧，
	 * 重挂时回放）∪ 实时流上收到的 delta，按 seq 归并。
	 *
	 * <p>
	 * 续跑轮<b>不重发 {@code start}</b>（首包归属首轮，早已在归档里；前端靠重挂回放拿到），
	 * 因此单看"本轮实时帧"必然不是一条完整的流。一致性断言必须打在<b>客户端眼里的那条完整流</b>上 ——
	 * 这恰好也是不变量⑤"回放 + 实时拼得完整一轮"在编排层的形态。
	 *
	 * <p>
	 * <b>T3-2 修订</b>：delta 帧跳过归档后，归档不再是"客户端完整流"的超集 —— delta 只在
	 * 实时流上存在（回放从来不发 delta，前端正文渲染靠实时拼接）。于是本方法从
	 * "纯归档视角"改为"归档状态帧 + 实时 delta"的归并视角，与前端实际持有的帧集一致。
	 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		List<Map<String, Object>> all = new ArrayList<>(FrameWire.normalize(this.store.events(RUN_ID)));
		all.addAll(FrameContract.ofType(wire.frames(), "delta"));
		all.sort(Comparator.comparingLong(frame -> ((Number) frame.get("seq")).longValue()));
		return all;
	}

	/** 一个正文分片（无 finishReason）。 */
	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	/** 一个空正文分片（模拟"只有 role 分片"或纯空白分片）。 */
	private static ChatResponse emptyChunk() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build())));
	}

	/** 带 finishReason 的终帧（完成帧校验的唯一判据）。 */
	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	/** 帧流里 delta 文本的顺序拼接（前端渲染正文的方式）。 */
	private static String concatenatedDeltas(List<Map<String, Object>> frames) {
		StringBuilder text = new StringBuilder();
		for (Map<String, Object> frame : FrameContract.ofType(frames, "delta")) {
			text.append(String.valueOf(frame.get("text")));
		}
		return text.toString();
	}

}
