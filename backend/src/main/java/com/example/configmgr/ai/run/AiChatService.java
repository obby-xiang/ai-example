package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiKeyPresentCondition;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮对话编排（S4.2 §3 步骤 2/7 的最小形态）。
 *
 * <h2>本棒做什么</h2>
 * 每轮：发 {@code start} 帧 → 组装请求（系统提示 + 工作区上下文 + 用户消息 + 记忆 Advisor +
 * 本上下文披露的工具子集）→ 官方流式循环（工具执行在官方实现内）→ 逐块 {@code delta} →
 * {@code done}/{@code error} 终帧。记忆读写全部交给官方 {@code MessageChatMemoryAdvisor}
 * （{@code CONVERSATION_ID} = sessionId），本轮不手工拼历史、不手工落库。
 *
 * <h2>本棒不做什么（后续棒次）</h2>
 * 断流重试三条件、双超时（ADR-6）、确认门/挂起态外置（M1/M3）、会话串行化（ADR-5）、
 * 重挂收流。因此这里只有"总预算"一个墙钟兜底，属于 <b>ResilientChatService 的前身</b>：
 * 后续棒次在同一落点扩展（或改名替换），本类不承担韧性语义。
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
			5. 主动提示用户下一步可以做什么
			""";

	private final ChatClient chatClient;

	private final ToolRegistry toolRegistry;

	private final ContextBuilder contextBuilder;

	private final AiProperties properties;

	public AiChatService(ChatClient chatClient, ToolRegistry toolRegistry, ContextBuilder contextBuilder,
			AiProperties properties) {
		this.chatClient = chatClient;
		this.toolRegistry = toolRegistry;
		this.contextBuilder = contextBuilder;
		this.properties = properties;
	}

	public void chat(AiChatRequest request, SseChatEmitter emitter) {
		String sessionId = request.sessionId();
		AiContext context = toContext(request.context());
		String runId = UUID.randomUUID().toString();
		emitter.start(runId, sessionId);

		List<ToolCallback> tools = this.toolRegistry.forContext(context);
		log.debug("AI 轮次开始 runId={} sessionId={} context={} 披露工具={}", runId, sessionId,
				context.getContextKey(), tools.stream().map(t -> t.getToolDefinition().name()).toList());

		Duration budget = this.properties.getResilience().getTotalBudget();
		AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();

		try {
			var spec = this.chatClient.prompt()
				.system(SYSTEM_PROMPT)
				.system(this.contextBuilder.buildContextMessage(context))
				.user(request.message())
				// 记忆 advisor 的会话键：官方常量（MessageChatMemoryAdvisor 据此读写 Redis）
				.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, sessionId));
			if (!tools.isEmpty()) {
				spec = spec.toolCallbacks(tools);
			}

			spec.stream()
				.chatResponse()
				.doOnNext(response -> consume(response, emitter, lastResponse))
				.blockLast(budget);

			emitter.done(runId, usageOf(lastResponse.get()), modelOf(lastResponse.get()));
		}
		catch (Exception ex) {
			log.error("AI 轮次失败 runId={} sessionId={}: {}", runId, sessionId, describe(ex), ex);
			emitter.error("AI_RUN_FAILED", describe(ex));
		}
	}

	/** 逐块消费：把本块文本推成 delta 帧。工具调用块不进客户端流（见类注释）。 */
	private void consume(ChatResponse response, SseChatEmitter emitter, AtomicReference<ChatResponse> lastResponse) {
		lastResponse.set(response);
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null) {
			return;
		}
		String text = generation.getOutput().getText();
		if (StringUtils.hasText(text)) {
			emitter.delta(text);
		}
	}

	private Map<String, Object> usageOf(ChatResponse response) {
		if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
			return Map.of();
		}
		Map<String, Object> usage = new LinkedHashMap<>();
		var usageValue = response.getMetadata().getUsage();
		usage.put("promptTokens", usageValue.getPromptTokens());
		usage.put("completionTokens", usageValue.getCompletionTokens());
		usage.put("totalTokens", usageValue.getTotalTokens());
		return usage;
	}

	private String modelOf(ChatResponse response) {
		return response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
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
			return "AI 调用超过总预算 " + this.properties.getResilience().getTotalBudget() + " 未完成（双超时阈值待压测裁决）";
		}
		return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
	}

}
