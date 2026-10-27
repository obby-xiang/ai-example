package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E4 用例：写类/作业类动作必须走<b>确认卡</b>，不得用正文文字询问替代。
 *
 * <p>
 * 现状缺口（用户评审 2026-10-22 问题 5）：提示词第 9 条只说明"启动导出/检查/导入/发布会渲染确认卡、
 * 发起后如实告诉用户"，却没有禁止"不发起工具、改用一句话问用户要不要执行"。模型于是可以停在
 * 「需要我帮你启动导出吗？」—— 用户答"好"，它再发起；这一问一答把确认门从<b>卡片</b>挪到了
 * <b>正文</b>，HITL 的可审计/可回放（确认卡带 runId/toolCallId/预检摘要）随之丢失。
 *
 * <p>
 * 本用例从真实装配点取证（{@code chat()} 传给 {@code ChatClient.system(..)} 的那一串），锁两件事：
 * <ol>
 * <li>硬规则存在且顺延编号（17/18），点名作业类/写类动作与"确认卡"；</li>
 * <li>措辞明确禁止"正文文字询问替代确认"，同时保留"开放式信息可以文字询问"的唯一出口
 * （否则规则会把正常追问也禁掉，模型只能硬发起工具）。</li>
 * </ol>
 *
 * <p><b>证红方式</b>：把第 17 条的"禁止在正文里用文字询问"删掉 ⇒ 第 1 条红；把第 18 条
 * "开放式信息"删掉 ⇒ 第 2 条红。
 */
class SystemPromptHitlConfirmTest {

	private static final String RUN_ID = "run-hitl-confirm";

	private static final String SESSION_ID = "s-hitl-confirm";

	private static final long TASK_ID = 11L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final TaskService taskService = mock(TaskService.class);

	/** 真实 ContextBuilder：系统提示的装配路径必须端到端成立（不是桩出来的）。 */
	private final ContextBuilder contextBuilder = new ContextBuilder(this.taskService, this.mapper);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setTitle("导出配置");
		task.setCurrentStep("SELECT_DEFS");
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setItems(List.of());
		when(this.taskService.findById(TASK_ID)).thenReturn(task);

		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "hitl-confirm-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(runtime("deepseek-flash"));
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(any(), any())).thenReturn(() -> {
		});
		when(this.chatMemory.get(SESSION_ID)).thenReturn(List.of());
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		stubFluentChain();

		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void systemPromptRequiresToolDrivenConfirmCardForWriteActions() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("E4：硬规则必须顺延既有编号（第 16 条之后是 17/18）")
			.contains("17.")
			.contains("18.");
		assertThat(systemText).as("E4：点名作业类/写类动作的四种形态")
			.contains("导出")
			.contains("预检查")
			.contains("导入")
			.contains("发布");
		assertThat(systemText).as("E4：必须调用对应工具，确认由确认卡承接")
			.contains("必须调用对应工具")
			.contains("确认卡");
		assertThat(systemText).as("E4：禁止用正文文字询问替代确认卡（逐字）")
			.contains("禁止在正文里用文字询问");
	}

	@Test
	void systemPromptPinsExportContinuationFlatSelectionAndDownloadIntent() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("无任务时沿既有导出链自动接续")
			.contains("create_task → navigate_to → select_definitions → confirm_step → start_export")
			.contains("不要求用户重复导出意图")
			.contains("不得绕过 DANGER 确认");
		assertThat(systemText).as("导出配置项表单直接平铺多选")
			.contains("直接平铺多选配置项")
			.contains("不得要求用户先选择 GLOBAL/REGION/PROJECT");
		assertThat(systemText).as("启动导出与下载旧结果严格分离")
			.contains("start_export 成功后严禁自动调用 download_export_files")
			.contains("只有用户明确提出下载/获取文件意图")
			.contains("不得把“启动导出”")
			.contains("改写成“下载旧结果”");
		assertThat(systemText).as("仅高危确认卡声明后果说明与复选框权威值")
			.contains("高危确认卡上的后果说明与复选框勾选状态是**宿主权威值**")
			.contains("勾选复选框后才可批准")
			.doesNotContain("确认卡上的影响面、条件摘要、发布模式");
		assertThat(systemText).as("AR-02/AR-03：两种未执行语义必须分开")
			.contains("未批准·未执行").contains("已批准·未执行");
	}

	/**
	 * 用户 2026-10-27 裁决（多步工作流）：新建任务获批准后，导航<b>不是</b>本轮终点 ——
	 * 页面级动作可在后续批次按 {@code navigate_to → select_definitions → confirm_step → set_condition}
	 * 串行接续，进入新页面/步骤后使用最新披露的工具列表；HITL 不变
	 * （{@code create_task} 与 {@code start_*} 仍走各自确认卡，披露不等于授权）。
	 *
	 * <p>本轮同时锁住创建侧不变的两条：拿到真实 taskId 的同一轮立即导航且不与 create_task 并批；
	 * 已创建成功即"任务已存在"，不得再要批准、不得要求用户回复继续。
	 */
	@Test
	void systemPromptPinsCreateThenNavigateInSameTurnWithoutReapproval() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("只有确认卡待决时才说批准；已创建成功即任务已存在")
			.contains("只有确认卡确实待决时才提示")
			.contains("已批准且创建成功即\"任务已存在\"")
			.contains("不得再次索要批准")
			.contains("不得要求用户回复继续");
		assertThat(systemText).as("同一轮拿到真实 taskId 后立即导航，不并批、不提前 select/confirm")
			.contains("同一轮立即单独调用 navigate_to(page, taskId)")
			.contains("禁止把 create_task 与 navigate_to 放进同一批并行调用")
			.contains("page 按任务类型取对应向导")
			.contains("导航前不要调用 select_definitions / confirm_step");
		assertThat(systemText).as("旧口径（导航成功即结束本轮）必须删除")
			.doesNotContain("导航成功后结束本轮")
			.doesNotContain("导航成功后即结束本轮")
			.contains("导航成功后不要结束本轮");
		assertThat(systemText).as("页面级动作按批串行接续，进入新页面/步骤后用最新披露的工具列表")
			.contains("navigate_to → select_definitions → confirm_step → set_condition")
			.contains("一批只做一步")
			.contains("下一批按最新披露")
			.doesNotContain("推进只对下一轮生效");
		assertThat(systemText).as("动态披露只是当前可用，不等于用户授权；创建与启动仍由确认卡承接")
			.contains("披露不等于授权")
			.contains("create_task 与 start_* 仍必须由各自确认卡承接")
			.contains("页面级动作失败即停");
	}

	/**
	 * 用户 2026-10-27 裁决：select_definitions 后默认不传 step 前进下一步，不主动追问筛选，
	 * 只有用户主动提出才 set_condition；未提条件 = 全量导出，且查询条件始终可选。
	 */
	@Test
	void systemPromptDefaultsToPlainNextStepWithoutProactiveFilterAsk() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("默认 confirm_step 不传 step，只前进一步")
			.contains("不要主动追问\"要筛选还是跳过\"")
			.contains("默认直接调用 confirm_step")
			.contains("不传 step，只前进一步");
		assertThat(systemText).as("筛选只在用户主动提出时设置，未提不展开询问")
			.contains("只有用户主动提出筛选需求时才用 set_condition 设置条件")
			.contains("不要展开询问条件");
		assertThat(systemText).as("不设条件即全量导出并如实说明")
			.contains("不设条件即全量导出");
		assertThat(systemText).as("step 仅目标明确/跳过时才传，查询条件不强制")
			.contains("step 仅在用户点名目标步骤或明确要求跳过筛选时传")
			.contains("查询条件始终可选，不强制要求填写");
	}

	@Test
	void textQuestionsRemainOnlyForOpenEndedInformation() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("E4：给文字询问留唯一出口 —— 开放式信息")
			.contains("开放式信息");
		assertThat(systemText).as("E4：出口限定为开放式信息，不是「什么都先问一句」")
			.contains("才");
	}

	// ── 装配与取证 ──────────────────────────────────────────────────────────

	private String runTurnAndCaptureSystemText() {
		AiChatRequest request = new AiChatRequest(SESSION_ID, "请帮我导出",
				new AiChatRequest.Context("export", "EXPORT", "SELECT_DEFS", TASK_ID, Map.of()), null,
				null);
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.chat(RUN_ID, request, out);
		FrameContract.assertConformant(wire.frames());

		ArgumentCaptor<String> systemText = ArgumentCaptor.forClass(String.class);
		verify(this.requestSpec).system(systemText.capture());
		return systemText.getValue();
	}

	/** {@code chat()} 走 ChatClient 的流式链：逐层返回同一个 spec，末端接回 {@code streamSpec}。 */
	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("好了"),
				terminal()));
	}

	private ProviderRuntime runtime(String modelId) {
		return new ProviderRuntime(7L, "deepseek", modelId, "http://placeholder", "/chat/completions",
				"7|fp|u|p", null, null, this.chatModel, this.chatClient);
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().build());
	}

}
