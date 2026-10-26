package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
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
import com.example.configmgr.ai.web.AiChatRequest;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 *
 * <h2>AR-06（批次3）：<b>首轮帧序已变</b>，本类不再声称"旧帧序不变"</h2>
 * 首帧 {@code start} 前移到请求前压缩之前，且压缩阶段的可见性由一条<b>心跳通道</b>的阶段帧承担
 * （{@code heartbeat{phase=compacting}}）。于是精确序列变了两处、类型集不变（仍是 16）：
 * <pre>
 * 旧（首轮）：[压缩…] start → context? → message_start → delta… → message_end → done
 * 新（首轮）：start → heartbeat{phase=compacting} → [压缩…] → context? → 正文三段式 → done
 * </pre>
 * 续跑（{@code driveResumed}）的形状不变：{@code start} 由原轮发出（归档里），续跑不重发；
 * 续跑压缩的阶段帧由 {@code ResumeService} 在进入同步压缩之前发（见 {@code ResumeContextCompactionTest}）。
 * 阶段帧是心跳：不占业务 {@code seq}、不入归档 ⇒ 回放断言里看不到它（"正在压缩"由重挂的
 * 202"准备中"给出，见 {@code AiControllerEventsPreparingTest}）。
 */
class ChatTurnFrameSequenceConformanceTest {

	private static final String RUN_ID = "run-chat-turn";

	private static final String SESSION_ID = "s-chat-turn";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	/** 首轮（{@code chat()}）走的 ChatClient 流式链：逐层返回同一个 spec，末端接 scripted 分片。 */
	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	/** 模型配置专项批①：模型侧从注册表取用（Runtime 包住 api/model/client）。 */
	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel,
			this.chatClient);

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
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "conformance-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
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
		// 重挂回放能把整轮拼回来（B1 起回放也含 delta ⇒ 正文可经差量补回）
		FrameWire reattached = new FrameWire();
		this.registry.of(RUN_ID).replayTo(reattached.emitter(), null);
		assertThat(FrameContract.types(reattached.frames())).containsExactly("start", "message_start", "delta",
				"delta", "message_end", "done");
		assertThat(concatenatedDeltas(reattached.frames())).as("回放拼接的正文与落定正文一致")
				.isEqualTo("你好，世界");
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

		long startedAt = System.nanoTime();
		FrameWire wire = runTurn();
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "retry", "error");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "retry")).containsEntry("nextAttempt", 2L)
				.containsEntry("code", StreamViolationException.INCOMPLETE);
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", StreamViolationException.INCOMPLETE);
		assertThat(FrameContract.ofType(wire.frames(), "delta")).as("两次尝试都未产出内容（重试的前提）").isEmpty();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(this.store.get(RUN_ID).getAttempts()).isEqualTo(2);
		// 超时面治理批① 第 1 项：两次尝试之间真的退避了（基线 500ms，抖动下限 0.75×）
		assertThat(elapsedMs).as("退避生效（≥375ms）").isGreaterThanOrEqualTo(375L);
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

	// ── L-91：思考段（reasoning）在编排层的形态 ─────────────────────────────

	/**
	 * <b>【L-91】</b>上游 {@code reasoning_content} 必须在帧面上成为<b>独立且闭合</b>的思考段：
	 * {@code message_start(kind=reasoning) → delta{kind:reasoning}* → message_end(kind=reasoning)}
	 * 先于正文段，思考文本<b>不进</b>正文（{@code message_end(text).chars} 只数正文、
	 * {@code finalText} 不含思考）。
	 *
	 * <p>
	 * kill：把 {@code ResilientChatService#consume} 里的 reasoning 分支删掉 ⇒ 帧流只剩正文段，
	 * 本用例的 {@code delta{kinds}} 断言变红（这正是 L-91 挂账的缺口本身：上游有 reasoning，
	 * 后端无出口）。
	 */
	@Test
	void reasoningChunksOpenAClosedThinkingSegmentAndNeverLeakIntoTheBody() {
		when(this.chatModel.stream(any(Prompt.class)))
				.thenReturn(Flux.just(reasoningChunk("先想"), reasoningChunk("一步"), chunk("答案是 2"),
						terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "delta",
				"message_end", "message_start", "delta", "message_end", "done");
		FrameContract.assertConformant(frames);

		// ① 两段边界同 messageId、kind 各自正确；思考段 chars = 思考 delta 之和
		List<Map<String, Object>> starts = FrameContract.ofType(frames, "message_start");
		assertThat(starts).hasSize(2);
		assertThat(starts.get(0)).containsEntry("kind", "reasoning");
		assertThat(starts.get(1)).containsEntry("kind", "text");
		assertThat(starts.get(0).get("messageId")).isEqualTo(starts.get(1).get("messageId"));

		// ② 思考 delta 带 kind=reasoning，正文 delta 不带 kind 键（additive 口径）
		List<Map<String, Object>> deltas = FrameContract.ofType(frames, "delta");
		assertThat(deltas).hasSize(3);
		assertThat(deltas.subList(0, 2)).allSatisfy(frame -> assertThat(frame).containsEntry("kind", "reasoning"));
		assertThat(deltas.get(2)).doesNotContainKey("kind").containsEntry("text", "答案是 2");

		// ③ 思考段闭合时 chars = 2（"先想"+"一步"），正文段 chars = 4（"答案是 2"）
		List<Map<String, Object>> ends = FrameContract.ofType(frames, "message_end");
		assertThat(ends).hasSize(2);
		assertThat(ends.get(0)).containsEntry("kind", "reasoning").containsEntry("chars", 4L);
		assertThat(ends.get(1)).containsEntry("kind", "text").containsEntry("chars", 5L);

		// ④ 思考不进落定正文 / 记忆（正文口径与前端正文段同源）
		assertThat(this.store.get(RUN_ID).getFinalText()).isEqualTo("答案是 2");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	/**
	 * <b>【L-91】</b>只吐思考、正文一个字都没有就断流：思考段仍必须先闭合再发 {@code error}
	 * （与 S5c-5 同一判据：运行关闭前，它打开的一切都必须先关闭）。
	 *
	 * <p>
	 * 这条同时锁住"已产出思考 ⇒ 不重试"：{@code emittedAny()} 只数正文时，纯思考轮会被判成
	 * "没产出任何内容"，于是重试会在思考段之后补一个 {@code retry} 帧 ——
	 * {@link FrameContract#orderingContract} 立刻变红。
	 */
	@Test
	void reasoningOnlyThenUpstreamFailureClosesTheThinkingSegmentAndDoesNotRetry() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(reasoningChunk("只想了没答")),
				Flux.error(new StreamViolationException(StreamViolationException.STREAM_ERROR, "上游流中断"))));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("kind", "reasoning")
				.containsEntry("chars", 5L);
		assertThat(FrameContract.ofType(frames, "retry")).as("已产出思考 ⇒ 不得重试（重试三条件：未产出内容）").isEmpty();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
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

	/**
	 * <b>【R-08】</b>上游错误响应体/异常只进日志台账，用户面只看到白名单短句。
	 *
	 * <p>kill：把 {@code fail()} 的 message 改回 {@code describeForLog(violation)}（原始 body）
	 * ⇒ 错误帧与快照里重新出现 {@code sk-...} / {@code 401}，本用例变红。
	 */
	@Test
	void upstreamErrorBodyAndExceptionAreNotExposedToUsers() {
		this.properties.getResilience().setMaxAttempts(1);
		String secret = "sk-upstream-secret";
		byte[] body = ("{\"error\":{\"message\":\"Incorrect API key provided: " + secret + "\"}}")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(WebClientResponseException.create(
				401, "Unauthorized", org.springframework.http.HttpHeaders.EMPTY, body, null)));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		Map<String, Object> error = FrameContract.oneOfType(frames, "error");
		assertThat(error).containsEntry("code", com.example.configmgr.common.ErrorCodes.UPSTREAM_AUTH_FAILED);
		assertThat(String.valueOf(error.get("message")))
				.as("用户面 = 白名单短句（不含原始 body / 异常类名 / key）")
				.isEqualTo("上游鉴权失败：请到「设置 → AI」检查 API Key。")
				.doesNotContain(secret)
				.doesNotContain("\"error\"")
				.doesNotContain("WebClientResponseException");
		assertThat(String.valueOf(this.store.get(RUN_ID).getError()))
				.as("快照台账同样不得写入原始 body")
				.doesNotContain(secret)
				.doesNotContain("Incorrect API key");
	}

	/**
	 * <b>【R-08 / 4B】</b>同一份白名单短句按请求 locale 取 {@code messages_en}，en 用户不再看到中文。
	 */
	@Test
	void upstreamErrorUsesEnglishForEnglishRequestLocale() {
		this.properties.getResilience().setMaxAttempts(1);
		org.springframework.context.support.ResourceBundleMessageSource source =
				new org.springframework.context.support.ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		org.springframework.test.util.ReflectionTestUtils.setField(this.service, "messageSource", source);
		org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
		try {
			when(this.chatModel.stream(any(Prompt.class)))
					.thenReturn(Flux.error(new IllegalStateException("upstream exploded: secret-body")));

			FrameWire wire = runTurn();

			Map<String, Object> error = FrameContract.oneOfType(clientView(wire), "error");
			assertThat(String.valueOf(error.get("message")))
					.as("en locale 取 messages_en；原始异常文本仍只进日志")
					.isEqualTo("The AI service returned an error. Please try again later")
					.doesNotContain("secret-body");
		}
		finally {
			org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
		}
	}

	// ── AR-06（批次3）：首轮精确序列（start 前移 + 压缩阶段帧） ────────────────

	/**
	 * <b>【AR-06】首轮精确序列</b>：{@code start} → {@code heartbeat{phase=compacting}} → 正文三段式 → {@code done}。
	 *
	 * <p>与旧帧序的差别只有两处，本用例就是这两处的可执行规格：
	 * <ol>
	 * <li>{@code start} 由"压缩之后"前移到"压缩<b>之前</b>"（首帧地位不变）；</li>
	 * <li>{@code start} 与正文之间多了一条<b>心跳通道</b>的阶段帧 —— 不占业务 {@code seq}、不入归档，
	 *     因此 16 帧类型集不变、回放里也看不到它。</li>
	 * </ol>
	 *
	 * <p>kill：把 {@code out.start(...)} 挪回 {@code store.create(...)} 之后 ⇒ 第 2 项（heartbeat）变红；
	 * 把阶段帧改成业务帧 ⇒ 归档里出现 heartbeat、业务 seq 出现洞，replay 断言变红。
	 */
	@Test
	void firstTurnPutsStartThenTheCompactionStageFrameBeforeTheBody() {
		stubFluentChain();
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "heartbeat", "message_start", "delta",
				"message_end", "done");
		assertThat(frames.get(1)).as("阶段帧走心跳通道：没有业务 seq")
			.containsEntry("phase", "compacting")
			.doesNotContainKey("seq");
		List<Map<String, Object>> business = frames.stream()
			.filter(frame -> !FrameContract.HEARTBEAT.equals(FrameContract.type(frame)))
			.toList();
		// 客户端视角（业务帧）序号连续、帧序不变量成立：心跳插在中间也不改业务时间线
		FrameContract.assertConformant(business);
		assertThat(business).extracting(frame -> ((Number) frame.get("seq")).longValue())
			.containsExactly(1L, 2L, 3L, 4L, 5L);

		// 回放：阶段帧不入归档（心跳口径），重挂者拿到的是完整业务流；
		// "这一轮正在压缩"由重挂的 202"准备中"给出（阶段帧本身补不回来）
		FrameWire reattached = new FrameWire();
		this.registry.of(RUN_ID).replayTo(reattached.emitter(), null);
		assertThat(FrameContract.types(reattached.frames())).containsExactly("start", "message_start", "delta",
				"message_end", "done");
		assertThat(concatenatedDeltas(reattached.frames())).isEqualTo("你好");
	}

	/** 首轮的 ChatClient 流式链（{@code chat()} 走这条，不走 ChatModel）。 */
	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenReturn(Flux.just(chunk("你好"), terminal("stop")));
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
	 * <b>客户端最终持有的完整帧流</b> = 归档帧 ∪ 实时流帧，按 seq 归并去重（归档优先）。
	 *
	 * <p>
	 * 续跑轮<b>不重发 {@code start}</b>（首包归属首轮，早已在归档里；前端靠重挂回放拿到），
	 * 因此单看"本轮实时帧"必然不是一条完整的流。一致性断言必须打在<b>客户端眼里的那条完整流</b>上 ——
	 * 这恰好也是不变量⑤"回放 + 实时拼得完整一轮"在编排层的形态。
	 *
	 * <p>
	 * <b>B1 修订（2026-10-20）</b>：delta 入档后归档本身就是客户端完整流的超集，于是本方法
	 * 按 seq 去重合并两条通道 —— 顺带把"归档帧与实时投递帧是同一份帧"这条也锁进断言。
	 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		Map<Long, Map<String, Object>> bySeq = new LinkedHashMap<>();
		for (Map<String, Object> frame : FrameWire.normalize(this.store.events(RUN_ID))) {
			bySeq.putIfAbsent(((Number) frame.get("seq")).longValue(), frame);
		}
		for (Map<String, Object> frame : wire.frames()) {
			if (frame.get("seq") instanceof Number number) {
				bySeq.putIfAbsent(number.longValue(), frame);
			}
		}
		List<Map<String, Object>> all = new ArrayList<>(bySeq.values());
		all.sort(Comparator.comparingLong(frame -> ((Number) frame.get("seq")).longValue()));
		return all;
	}

	/** 一个正文分片（无 finishReason）。 */
	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	/**
	 * 一个<b>思考</b>分片（L-91）：正文为空、{@code metadata.reasoningContent} = 思考文本 ——
	 * 形态与 Spring AI 1.1.7 的流式转换一致（见 {@code ReasoningContentUpstreamProbeTest} 的实证）。
	 */
	private static ChatResponse reasoningChunk(String reasoning) {
		return new ChatResponse(List.of(new Generation(
				AssistantMessage.builder().content("").properties(Map.of("reasoningContent", reasoning)).build())));
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

	// ── L-99：输出侧 DLP 已删除后的编排层形态（原 B3-T03/T07/T08/T16 改写） ──

	private static String apiKey() {
		return "s" + "k-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4";
	}

	/**
	 * 密钥跨分片时：正文原样透传（不脱敏、不阻断），{@code done} 不带 {@code safety} 键，且
	 * {@code message_end.chars == Σ delta}、{@code finalText == Σ delta}（三处同源）。
	 *
	 * <p>kill：把 DLP 再装回来 —— 第一条断言（含原文）立刻变红；把 delta 与累计器分家 ——
	 * {@link FrameContract#textSegmentContract} 变红。
	 */
	@Test
	void crossChunkSecretIsStreamedVerbatimAndTheSegmentStillBalances() {
		String key = apiKey();
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("保存这个 " + key.substring(0, 3)),
				chunk(key.substring(3) + " 到变量里"), terminal("stop")));

		FrameWire wire = runTurn();
		List<Map<String, Object>> frames = clientView(wire);
		FrameContract.assertConformant(frames);

		String concatenated = concatenatedDeltas(frames);
		assertThat(concatenated).contains("保存这个 " + key + " 到变量里");
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("chars",
				(long) concatenated.length());
		assertThat(this.store.get(RUN_ID).getFinalText()).isEqualTo(concatenated);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(FrameContract.oneOfType(frames, "done")).doesNotContainKey("safety");
	}

	/**
	 * 私钥块不再触发阻断终态：本轮正常 {@code done}，正文逐字透传（"无 DLP"是当前口径）。
	 *
	 * <p>取消 S-03（{@code SENSITIVE_OUTPUT_BLOCKED}）后本用例的方向反转：它现在锁的是
	 * "这个终态<b>不存在</b>"，防止有人把阻断分支悄悄装回来。
	 */
	@Test
	void privateKeyTextEndsWithDoneAndIsStreamedVerbatim() {
		String head = "-----BEGIN " + "RSA PRIVATE KEY-----";
		String foot = "-----END " + "RSA PRIVATE KEY-----";
		String body = "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun";
		when(this.chatModel.stream(any(Prompt.class)))
				.thenReturn(Flux.just(chunk("这是配置：\n" + head + "\n" + body + "\n" + foot), terminal("stop")));

		FrameWire wire = runTurn();
		List<Map<String, Object>> frames = clientView(wire);
		FrameContract.assertConformant(frames);

		assertThat(FrameContract.types(frames)).contains("message_end", "done").doesNotContain("error");
		assertThat(concatenatedDeltas(frames)).contains(head).contains(body).contains(foot);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	/** {@code done} 永不带 {@code safety} 键（该 additive 键随输出守卫一起移除）。 */
	@Test
	void doneNeverCarriesSafetyKey() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你好"), terminal("stop")));

		FrameWire wire = runTurn();
		List<Map<String, Object>> frames = clientView(wire);

		assertThat(FrameContract.oneOfType(frames, "done")).doesNotContainKey("safety");
		assertThat(concatenatedDeltas(frames)).isEqualTo("你好");
	}

}
