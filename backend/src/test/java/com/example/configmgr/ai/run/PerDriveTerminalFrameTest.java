package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.memory.MessageJsonCodec;
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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>终帧判据的"每次驱动"口径</b>（2026-10-29 返修，复审 I-2）。
 *
 * <h2>被钉住的缺陷</h2>
 * 归档是 <b>per-runId</b> 的，而同一个 runId 会被驱动<b>多次</b>：`POST /api/ai/runs/{runId}/retry-model-step`
 * 接受 FAILED 轮，把快照改回 RUNNING 再 `driveResumed` 一次（归档<b>不清理</b>、也不追加驱动代次）。
 * 上一版失败出口的判据是"归档里有没有<b>任何</b> done/error"——于是第二次驱动失败时读到的是<b>上一次</b>
 * 的 error：
 * <ol>
 * <li>本次驱动一条新终帧都不发（用户看不到"重试也失败了"，连接只靠收尾关掉）；</li>
 * <li>连 {@code textEnd} 也被一起跳过 ⇒ 本次已落档的 delta 段没有 {@code message_end}
 *     （正是 ag-ui {@code open-message-at-run-finished} 违约）；</li>
 * <li>归档末帧是<b>旧的</b> code/message，与 {@code turn_end} 的"failed + 新 code"自相矛盾。</li>
 * </ol>
 *
 * <h2>本批口径</h2>
 * 驱动入口（{@code chat} / {@code driveResumed}）记一次<b>归档序号下界</b>，失败出口只认
 * <b>seq &gt; 下界</b>的 done/error —— "上一次驱动"的终帧不属于本次驱动；同一驱动内已落的终帧仍然算数
 * （不重复发）。下界读不出来 = 不可知 ⇒ 照发（宁可多一条，也不吞掉本次失败）。
 *
 * <p>本类<b>全程走真实 {@link RunStore} + 真实归档</b>（{@code ai:events:} LIST 兼容口径），
 * 不用内存替身，避免"替身语义掩盖真实写门/取号路径"。
 */
class PerDriveTerminalFrameTest {

	private static final String RUN_ID = "run-per-drive-terminal";

	private static final String SESSION_ID = "s-per-drive-terminal";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	/** 直调真实 RunStore 必须显式带身份（create 缺身份 fail-closed）。 */
	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable tenantScope;

	@BeforeEach
	void setUp() {
		this.tenantScope = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
		ProviderRuntime runtime = new ProviderRuntime(null, "prov", "model", "http://localhost", "/chat/completions",
				"k", null, null, this.chatModel, mock(ChatClient.class));
		when(this.providerRegistry.current()).thenReturn(runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(runtime));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "per-drive-terminal-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		// 真实 create（顺带走一遍本批的首笔快照显式判定路径）
		this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), this.codec);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
		if (this.tenantScope != null) {
			this.tenantScope.close();
		}
	}

	/**
	 * 第一次 error → 重试再 error：本次驱动必须自己落 {@code message_end} + {@code error} 各一条，
	 * 且 error 的 code 是<b>本次</b>的（不是上一次那条）。
	 */
	@Test
	void aRetryDriveEmitsItsOwnTextEndAndErrorWithItsOwnCode() {
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("第一次驱动失败（模拟）")));
		drive();
		List<Map<String, Object>> afterFirst = this.store.events(RUN_ID);
		assertThat(terminalTypes(afterFirst)).as("第一次驱动恰好一条终帧").containsExactly("error");
		String firstCode = terminalCode(afterFirst.get(afterFirst.size() - 1));

		// retry-model-step 的语义：快照改回 RUNNING 再驱动一次（归档保留上一次的 error）
		RunSnapshot running = this.store.get(RUN_ID);
		running.setStatus(RunSnapshot.RUNNING);
		this.store.save(running);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.concat(Flux.just(chunk("半截正文")), Flux.just(terminal("content_filter"))));
		drive();

		List<Map<String, Object>> afterSecond = this.store.events(RUN_ID);
		List<Map<String, Object>> secondDrive = afterSecond.subList(afterFirst.size(), afterSecond.size());
		assertThat(secondDrive).as("第二次驱动必须真的落了帧（取证前提）").isNotEmpty();
		assertThat(terminalTypes(secondDrive))
			.as("本次驱动必须有自己的终帧（上一次的 error 不属于本次）")
			.containsExactly("error");
		assertThat(countType(secondDrive, "message_end"))
			.as("本次驱动落过 delta ⇒ 必须闭合正文段（不得因旧终帧连 textEnd 一起跳过）")
			.isEqualTo(1);
		String secondCode = terminalCode(secondDrive.get(secondDrive.size() - 1));
		assertThat(secondCode).as("新终帧带本次的 code，而不是上一次驱动留下的那条")
			.startsWith("CONTENT_FILTERED")
			.isNotEqualTo(firstCode);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/**
	 * 相邻对照：上一次驱动的 error <b>不得</b>影响本次重跑成功 —— 本次仍要落自己的 {@code done}。
	 *
	 * <p>成功出口（{@code finishSuccess}）本来就不读归档终帧，本用例锁住"返修没有把成功路径一起改坏"。
	 */
	@Test
	void aSuccessfulRetryDriveStillEmitsItsOwnDone() {
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("第一次驱动失败（模拟）")));
		drive();
		List<Map<String, Object>> afterFirst = this.store.events(RUN_ID);
		assertThat(terminalTypes(afterFirst)).containsExactly("error");

		RunSnapshot running = this.store.get(RUN_ID);
		running.setStatus(RunSnapshot.RUNNING);
		this.store.save(running);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("重试成功"), terminal("stop")));
		drive();

		List<Map<String, Object>> afterSecond = this.store.events(RUN_ID);
		List<Map<String, Object>> secondDrive = afterSecond.subList(afterFirst.size(), afterSecond.size());
		assertThat(terminalTypes(secondDrive)).as("重试成功必须落自己的 done").containsExactly("done");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	/**
	 * 同一驱动内已落的终帧仍被认出来（否则"本次驱动"的重复发射会落出两条终帧）。
	 *
	 * <p>用<b>真实归档</b>锁判据本体：{@code beginDrive} → 本驱动落一条 error ⇒ 判据为 TRUE；
	 * 而<b>上一次驱动</b>落的 error 不进入本驱动判据（下一条用例）。
	 */
	@Test
	void aTerminalFromThisDriveIsStillDetected() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		out.beginDrive();
		out.error("UPSTREAM_STREAM_ERROR", "本次驱动的终帧");

		assertThat(out.terminalFrameArchivedSinceDriveStart()).as("本次驱动已落终帧 ⇒ 不得再发第二条").isTrue();
	}

	/** 上一次驱动留下的终帧不属于本次驱动（下界之前的一律不算）。 */
	@Test
	void aTerminalFromThePreviousDriveIsNotCounted() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		out.beginDrive();
		out.error("UPSTREAM_STREAM_ERROR", "上一次驱动的终帧");

		out.beginDrive();
		assertThat(out.terminalFrameArchivedSinceDriveStart())
			.as("新驱动从下界之后算起：上一次的 error 不得让本次失败被吞掉")
			.isFalse();
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private void drive() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), out);
	}

	private static List<String> terminalTypes(List<Map<String, Object>> frames) {
		List<String> types = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				types.add(type);
			}
		}
		return types;
	}

	private static long countType(List<Map<String, Object>> frames, String type) {
		return frames.stream().filter(frame -> type.equals(String.valueOf(frame.get("type")))).count();
	}

	private static String terminalCode(Map<String, Object> frame) {
		Object code = frame.get("code");
		return code == null ? null : String.valueOf(code);
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").build(),
						ChatGenerationMetadata.builder().finishReason(finishReason).build())),
				ChatResponseMetadata.builder().model("model").build());
	}

}
