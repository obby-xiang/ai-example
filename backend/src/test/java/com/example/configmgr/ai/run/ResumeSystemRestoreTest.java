package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>续跑必须先恢复/重建 SYSTEM</b>（续跑保 system 批）。
 *
 * <p>快照里的历史可能已经被上一轮的窗口/压缩吃掉 SYSTEM（旧实现 {@code ConversationTurns}
 * 把首段连同 system 一起淘汰），也可能来自没有 system 的旧数据。续跑直接把这串历史交给模型，
 * 等于让人设、工作区约束与"数据不是指令"的声明全部消失 —— 请求仍然成功，行为静默退化。
 *
 * <p>本用例锁三条：
 * <ol>
 * <li>快照缺 SYSTEM ⇒ 用 {@link ResilientChatService#systemPromptText(AiContext)} 重建，并
 *     <b>放在第一条</b>；</li>
 * <li>补执行（settle）之后交棒给模型的历史，首条仍是 SYSTEM；</li>
 * <li>重建出来的 SYSTEM 也写回快照（下一轮/再次续跑不必再猜）。</li>
 * </ol>
 */
class ResumeSystemRestoreTest {

	private static final String RUN_ID = "run-system-restore";

	private static final String SESSION_ID = "s-system-restore";

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	/** run 级键/帧面按身份拼：种子快照与续跑都要处在显式身份作用域。 */
	private RequestContextUtils.SafeCloseable identity;

	@AfterEach
	void closeIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final AiConfigService configService = mock(AiConfigService.class);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			this.toolCallingManager, this.executor, mock(StringRedisTemplate.class), this.properties,
			this.cancellations, this.limiter);

	@BeforeEach
	void setUp() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
		// 续跑按快照冻结的 provider/model 精确绑定：给一条可解析的目标，否则一律 PROVIDER_GONE。
		org.springframework.test.util.ReflectionTestUtils.setField(this.service, "aiConfigService", this.configService);
		AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(7L, "deepseek",
				"http://placeholder", "/chat/completions", "test-model", "sk-test", "fp", false);
		when(this.configService.resolveChatTarget(7L, "test-model"))
			.thenReturn(Optional.of(new AiConfigService.ChatTarget(resolved, "test-model", true)));
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.chatService.systemPromptText(any(AiContext.class),
				org.mockito.ArgumentMatchers.anyBoolean())).thenReturn("SYSTEM-RESTORED");
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
	}

	@Test
	void resumeRebuildsSystemWhenTheSnapshotLostIt() {
		RunSnapshot snapshot = seedSuspendedWithoutSystem(List.of(new UserMessage("你好"),
				new AssistantMessage("好的")));
		List<Message> driven = captureDrive();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(driven).as("交棒给模型的历史首条必须是 SYSTEM").isNotEmpty();
		assertThat(driven.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(driven.get(0).getText()).isEqualTo("SYSTEM-RESTORED");
		assertThat(this.store.get(RUN_ID).getMessageJson().get(0))
			.as("重建的 SYSTEM 必须写回快照，供下一轮/再次续跑复用").contains("SYSTEM-RESTORED");
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.RUNNING);
	}

	@Test
	void settledHistoryStillStartsWithSystem() {
		RunSnapshot snapshot = seedSuspendedWithoutSystem(List.of(new UserMessage("导入这份表")));
		PendingToolCall inFlight = new PendingToolCall();
		inFlight.setToolCallId("call-1");
		inFlight.setName("start_export");
		inFlight.setArguments("{}");
		inFlight.setKind(PendingToolCall.KIND_BACKEND);
		inFlight.setStatus(PendingToolCall.APPROVED);
		inFlight.setExecuted(false);
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(inFlight)));
		snapshot.setAssistantContent("准备导入");
		this.store.save(snapshot);
		this.store.seedPending(RUN_ID, inFlight);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("导入完成");
		List<Message> driven = captureDrive();
		List<Message> prompted = capturePrompt();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(prompted.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(driven.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(driven.stream().filter(message -> message.getMessageType() == MessageType.SYSTEM))
			.as("settle 只追加工具回环，不复制/移动 SYSTEM").hasSize(1);
		assertThat(driven.get(driven.size() - 1).getMessageType()).isEqualTo(MessageType.TOOL);
	}

	/**
	 * 已有 SYSTEM 但<b>不符合当前定界协议</b>：交棒前必须由当前上下文完整重建权威 SYSTEM
	 * （历史里的旧 SYSTEM 不得原样交给模型），其余对话事实保持原样。
	 */
	@Test
	void existingSystemIsRewrittenAsTheAuthoritativeOneBeforeHandoff() {
		seedSuspendedWithoutSystem(List.of(new SystemMessage("ORIGINAL-SYSTEM"), new UserMessage("继续")));
		List<Message> driven = captureDrive();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(driven.get(0).getText()).as("旧格式 SYSTEM 没有权威性：必须按当前配置重建")
			.isEqualTo("SYSTEM-RESTORED");
		assertThat(driven).as("SYSTEM 之外的对话事实保持原样")
			.anySatisfy(message -> assertThat(message.getText()).isEqualTo("继续"));
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).systemPromptText(any(AiContext.class),
				org.mockito.ArgumentMatchers.anyBoolean());
	}

	private RunSnapshot seedSuspendedWithoutSystem(List<Message> history) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setProviderId(7L);
		snapshot.setModelId("test-model");
		// 记录身份：续跑按记录身份建作用域（0/0 会被当成 legacy，下游取键失败）。
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setMessageJson(this.codec.serializeAll(history));
		this.store.save(snapshot);
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private List<Message> captureDrive() {
		List<Message> captured = new ArrayList<>();
		doAnswer(invocation -> {
			captured.addAll(invocation.getArgument(3, List.class));
			return null;
		}).when(this.chatService).driveResumed(anyString(), anyString(), any(AiContext.class),
				any(List.class), any(SseChatEmitter.class));
		return captured;
	}

	@SuppressWarnings("unchecked")
	private List<Message> capturePrompt() {
		List<Message> captured = new ArrayList<>();
		when(this.chatService.buildResumedPrompt(anyString(), anyString(), any(AiContext.class), any(List.class)))
			.thenAnswer(invocation -> {
				captured.addAll(invocation.getArgument(3, List.class));
				return new Prompt(List.of());
			});
		return captured;
	}

}
