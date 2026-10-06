package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiKeyPresentCondition;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮对话编排（S4.2 §3 时序的落地形态）。
 *
 * <h2>本棒做什么</h2>
 * <ul>
 * <li><b>开流前后</b>：登记会话（{@link AiSessionService}）→ 组装完整请求历史 →
 * 写 {@link RunStore} 快照 → 发 {@code start} 帧；</li>
 * <li><b>本轮请求</b>：官方 {@code ChatClient} + 官方 {@code MessageChatMemoryAdvisor}
 * （{@code CONVERSATION_ID}=sessionId）+ 本上下文披露的工具子集 + {@code toolContext}（runId）；</li>
 * <li><b>工具执行</b>：全部经 {@code gate/SpToolCallingManager}（确认门 / 前端挂起 / 可见性钩子）；</li>
 * <li><b>落定</b>：终帧 {@code done}/{@code error} → 快照 DONE → <b>记忆收敛</b>（把本轮完整历史
 * 经官方 {@code ChatMemory} 整窗口覆盖写回）。</li>
 * </ul>
 *
 * <h2>Q13 一致性风险点（规格 §3 末段）的处置</h2>
 * 记忆窗口（{@code chat:mem:}）与挂起快照（{@code ai:run:}）是两套存储：官方
 * {@code MessageChatMemoryAdvisor} 在流式路径上只把 {@code USER + ASSISTANT(最终文本)} 写进记忆
 * （P1 §4.4-1 实测：工具配对从不落记忆）。本类因此以 <b>RunStore 快照为准</b>重建记忆窗口：
 * 落定时把快照历史去掉两条 system 提示后整体覆盖写回记忆，
 * 并做<b>配对保护</b>的窗口裁剪（窗口首条不得是孤立的 {@code TOOL} 消息），
 * 使"含挂起轮的 tool 配对"既进快照也进记忆，两边收敛。
 *
 * <h2>命名（P1 遗留 N6）</h2>
 * 本类仍是第一棒 {@code AiChatService} 的延展，尚未承担"双超时 + 三条件重试 + 取消"
 * 的韧性语义，故不冒名为 {@code ResilientChatService}（规格 §2 给出的目标名）。
 * 韧性棒次在同一落点扩展或替换。
 */
@Slf4j
@Service
@Conditional(AiKeyPresentCondition.class)
public class AiChatService {

	private static final String SYSTEM_PROMPT = """
			你是一个专业的配置管理 AI 助手，帮助用户完成配置数据的导出、导入和管理工作。

			你的能力：
			- 帮助用户创建和管理快速实施任务（导出/导入配置）
			- 查询配置定义和字段信息
			- 设置查询条件、选择配置项
			- 启动导出、预检查、导入等作业并监控进度
			- 解释检查结果、解答业务疑问

			工作原则：
			1. 始终使用中文回复
			2. 用户明确要求执行某个操作时，直接调用对应工具完成任务，
			   不要只在对话里描述步骤
			3. 你只能使用系统当前披露给你的工具；没有对应工具时，说明原因并给出下一步建议
			4. 工具调用失败时给出清晰的错误说明和建议
			5. 若某个操作被用户拒绝、确认超时或未执行，如实说明，禁止宣称已执行
			6. 主动提示用户下一步可以做什么
			""";

	private final ChatClient chatClient;

	private final ChatModel chatModel;

	private final ChatMemory chatMemory;

	private final MessageJsonCodec codec;

	private final RunStore store;

	private final RunRegistry registry;

	private final ToolRegistry toolRegistry;

	private final ContextBuilder contextBuilder;

	private final AiSessionService sessionService;

	private final AiProperties properties;

	public AiChatService(ChatClient chatClient, ChatModel chatModel, ChatMemory chatMemory, MessageJsonCodec codec,
			RunStore store, RunRegistry registry, ToolRegistry toolRegistry, ContextBuilder contextBuilder,
			AiSessionService sessionService, AiProperties properties) {
		this.chatClient = chatClient;
		this.chatModel = chatModel;
		this.chatMemory = chatMemory;
		this.codec = codec;
		this.store = store;
		this.registry = registry;
		this.toolRegistry = toolRegistry;
		this.contextBuilder = contextBuilder;
		this.sessionService = sessionService;
		this.properties = properties;
	}

	// ── 首轮：ChatClient + 记忆 Advisor 路径 ────────────────────────────────

	public void chat(String runId, AiChatRequest request, SseChatEmitter out) {
		String sessionId = request.sessionId();
		AiContext context = toContext(request.context());
		boolean existed = this.sessionService.ensure(sessionId);

		List<Message> memory = this.chatMemory.get(sessionId);
		// 人设与工作区快照合成"一条"系统消息：官方 ChatClient 的 system 文本是单槽位
		// （Spec 内部 systemTextParams 只有一个 key），连续两次 .system() 会覆盖前者 ——
		// 实测：只发第二条时，主提示词整段消失（快照里 system 只剩工作区快照）。
		String systemText = SYSTEM_PROMPT + "\n\n" + this.contextBuilder.buildContextMessage(context);
		List<Message> instructions = List.of(new SystemMessage(systemText), new UserMessage(request.message()));

		// 与官方 MessageChatMemoryAdvisor.before 的变换逐字对齐（1.1.8 字节码实测）：
		// [首个 SystemMessage] + 记忆窗口 + 其余指令 —— 快照里存的就是模型真正看到的那串消息。
		List<Message> full = new ArrayList<>(memory.size() + instructions.size());
		full.add(instructions.get(0));
		full.addAll(memory);
		full.add(instructions.get(1));
		this.store.create(runId, sessionId, context, full, this.codec);
		out.start(sessionId);

		List<ToolCallback> tools = this.toolRegistry.forContext(context);
		log.debug("AI 轮次开始 runId={} sessionId={} 会话已存在={} 上下文={} 披露工具={}", runId, sessionId, existed,
				context.getContextKey(), tools.stream().map(t -> t.getToolDefinition().name()).toList());

		Accumulator accumulator = new Accumulator();
		try {
			var spec = this.chatClient.prompt()
				.system(systemText)
				.user(request.message())
				// 记忆 advisor 的会话键：官方常量（MessageChatMemoryAdvisor 据此读写 Redis）
				.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, sessionId))
				// 显式 runId 通道：ToolCallingManager 由此拿到外置/挂起所需的轮次 id
				.toolContext(Map.of("runId", runId, "sessionId", sessionId));
			if (!tools.isEmpty()) {
				spec = spec.toolCallbacks(tools);
			}
			spec.stream().chatResponse().doOnNext(response -> consume(response, out, accumulator)).blockLast(budget());
			finish(runId, sessionId, accumulator, out);
		}
		catch (Exception ex) {
			fail(runId, sessionId, ex, out);
		}
	}

	// ── 续跑：重建的完整历史 + 官方 ChatModel 路径（由 ResumeService 驱动） ──

	/**
	 * 用重建好的<b>完整历史</b>继续官方循环（硬规范②）。
	 *
	 * <p>
	 * 这里刻意不经 {@code ChatClient}/{@code MessageChatMemoryAdvisor}：历史已经由
	 * {@link ResumeService} 从 RunStore 重建好，再过一遍 advisor 会把记忆窗口重复拼一遍。
	 * 工具执行仍必须走同一个官方扩展点（options 里带 toolCallbacks + toolContext），
	 * 这样续跑后模型新发的工具调用照旧经过确认门/前端挂起（SM-06 / TC-AI-03b）。
	 */
	public void driveResumed(String runId, String sessionId, AiContext context, List<Message> history,
			SseChatEmitter out) {
		Accumulator accumulator = new Accumulator();
		try {
			Prompt prompt = buildResumedPrompt(runId, sessionId, context, history);
			this.chatModel.stream(prompt).doOnNext(response -> consume(response, out, accumulator)).blockLast(budget());
			finish(runId, sessionId, accumulator, out);
		}
		catch (Exception ex) {
			fail(runId, sessionId, ex, out);
		}
	}

	/**
	 * 续跑请求与首轮同构：同一模型默认选项 + 同一披露子集 + 同一 toolContext。
	 *
	 * <p>
	 * 也供 {@code ResumeService} 在重建阶段结算挂起项时复用（工具的委托执行需要一个带
	 * toolCallbacks 的 Prompt）。
	 */
	public Prompt buildResumedPrompt(String runId, String sessionId, AiContext context, List<Message> history) {
		ChatOptions base = this.chatModel.getDefaultOptions();
		OpenAiChatOptions options = base instanceof OpenAiChatOptions openAiOptions
				? OpenAiChatOptions.fromOptions(openAiOptions) : OpenAiChatOptions.builder().build();
		options.setToolCallbacks(this.toolRegistry.forContext(context));
		options.setInternalToolExecutionEnabled(true);
		options.setToolContext(Map.of("runId", runId, "sessionId", sessionId));
		return new Prompt(history, options);
	}

	// ── 落定 ────────────────────────────────────────────────────────────────

	/** 成功终局：补最终 assistant 消息 → 快照 DONE → 记忆收敛 → {@code done} 终帧。 */
	private void finish(String runId, String sessionId, Accumulator accumulator, SseChatEmitter out) {
		String text = accumulator.text();
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot != null) {
			List<Message> history = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			if (StringUtils.hasText(text)) {
				history.add(AssistantMessage.builder().content(text).build());
			}
			snapshot.setMessageJson(this.codec.serializeAll(history));
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setAssistantContent(null);
			snapshot.setFinalText(text);
			snapshot.setStatus(RunSnapshot.DONE);
			this.store.save(snapshot);
			convergeMemory(sessionId, history);
			log.debug("AI 轮次结束 runId={} sessionId={} 历史条数={} 终帧=done", runId, sessionId, history.size());
		}
		out.done(accumulator.usage(), accumulator.model());
	}

	private void fail(String runId, String sessionId, Exception ex, SseChatEmitter out) {
		String message = describe(ex);
		log.error("AI 轮次失败 runId={} sessionId={}: {}", runId, sessionId, message, ex);
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot != null && !RunSnapshot.DONE.equals(snapshot.getStatus())) {
			snapshot.setStatus(RunSnapshot.FAILED);
			snapshot.setError(message);
			this.store.save(snapshot);
		}
		out.error("AI_RUN_FAILED", message);
	}

	/**
	 * 记忆收敛（规格 §3 末段）：以本轮完整历史整体覆盖写回记忆窗口（去掉两条 system 提示 ——
	 * 系统提示每轮由请求侧重新拼装，写进记忆会导致下一轮重复叠加）。
	 */
	private void convergeMemory(String sessionId, List<Message> history) {
		List<Message> memoryPortion = history.stream()
			.filter(message -> message.getMessageType() != MessageType.SYSTEM)
			.toList();
		if (memoryPortion.isEmpty()) {
			return;
		}
		List<Message> windowed = trimKeepingToolPairs(memoryPortion,
				this.properties.getMemory().getMaxMessages());
		try {
			this.chatMemory.clear(sessionId);
			this.chatMemory.add(sessionId, windowed);
		}
		catch (Exception ex) {
			log.warn("记忆收敛失败 sessionId={}：{}", sessionId, ex.getMessage());
		}
	}

	/**
	 * 窗口裁剪的配对保护：窗口首条不得是孤立的 {@code TOOL} 消息（它的
	 * {@code assistant(tool_calls)} 已被裁掉的话，下一次请求会因"tool 没有对应的 tool_calls"被上游拒绝）。
	 * 先做一次保守裁剪，再交给官方 {@code MessageWindowChatMemory} 自己裁剪（此时已 ≤ 上限，不再切）。
	 */
	private List<Message> trimKeepingToolPairs(List<Message> messages, int maxMessages) {
		if (maxMessages <= 0 || messages.size() <= maxMessages) {
			return messages;
		}
		int drop = messages.size() - maxMessages;
		while (drop < messages.size() && messages.get(drop).getMessageType() == MessageType.TOOL) {
			drop++;
		}
		return new ArrayList<>(messages.subList(drop, messages.size()));
	}

	// ── 流式消费 ────────────────────────────────────────────────────────────

	private void consume(ChatResponse response, SseChatEmitter out, Accumulator accumulator) {
		accumulator.accept(response);
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null) {
			return;
		}
		String text = generation.getOutput().getText();
		if (StringUtils.hasText(text)) {
			out.delta(text);
		}
	}

	/** 本轮累计文本 + 终帧所需的 usage/model（工具轮不产生客户端文本，见管理器注释）。 */
	private static final class Accumulator {

		private final StringBuilder text = new StringBuilder();

		private final AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();

		void accept(ChatResponse response) {
			this.lastResponse.set(response);
			Generation generation = response.getResult();
			if (generation != null && generation.getOutput() != null) {
				String chunk = generation.getOutput().getText();
				if (chunk != null) {
					this.text.append(chunk);
				}
			}
		}

		String text() {
			return this.text.toString();
		}

		Map<String, Object> usage() {
			ChatResponse response = this.lastResponse.get();
			if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
				return Map.of();
			}
			var usage = response.getMetadata().getUsage();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("promptTokens", usage.getPromptTokens());
			out.put("completionTokens", usage.getCompletionTokens());
			out.put("totalTokens", usage.getTotalTokens());
			return out;
		}

		String model() {
			ChatResponse response = this.lastResponse.get();
			return response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
		}

	}

	private Duration budget() {
		return this.properties.getResilience().getTotalBudget();
	}

	private AiContext toContext(AiChatRequest.Context context) {
		if (context == null) {
			return AiContext.empty();
		}
		return AiContext.of(context.page(), context.taskType(), context.step(), context.taskId(), context.extra());
	}

	/**
	 * 上游错误要留住原始响应体：reasoning 回填缺失等协议错误只有这里能看清（400 的 body 才是证据）。
	 */
	private String describe(Throwable error) {
		Throwable cause = error;
		while (cause != null && !(cause instanceof WebClientResponseException)) {
			cause = cause.getCause();
		}
		if (cause instanceof WebClientResponseException upstream) {
			return "上游返回 HTTP " + upstream.getStatusCode().value() + "：" + upstream.getResponseBodyAsString();
		}
		if (error instanceof IllegalStateException && error.getMessage() != null
				&& error.getMessage().startsWith("Timeout on blocking read")) {
			return "AI 调用超过总预算 " + budget() + " 未完成（双超时阈值待压测裁决）";
		}
		return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
	}

}
