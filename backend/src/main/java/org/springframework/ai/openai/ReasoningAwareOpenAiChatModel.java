package org.springframework.ai.openai;

import java.util.List;

import com.example.configmgr.ai.memory.ReasoningOrigin;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Spring AI 1.1.7 的 OpenAI 兼容扩展适配：父类 {@code OpenAiChatModel.createRequest}
 * 不传播 assistant 消息 metadata 中的 reasoningContent，而部分兼容服务把非标准的
 * {@code reasoning_content} 作为逐消息扩展，并要求工具续轮回放实际产生的推理段。
 * 本类放在同名包下以 Override 包私有的 createRequest，保留父类的标准消息映射、
 * 工具定义和 extraBody 合并行为，仅增加受来源约束的扩展字段传播。
 *
 * <p>{@code reasoning_content} 不是 OpenAI 标准字段。只有
 * {@link ReasoningOrigin#replayAllowed} 确认消息与当前目标同源，且值是非空白字符串时才回放；
 * 空白值、来源未知或跨来源消息都不生成该字段。其它 metadata 仍由快照和记忆原样保存，
 * 不因此进入请求。升级 Spring AI 版本后应评审官方是否已覆盖该扩展点并移除本适配。
 */
public class ReasoningAwareOpenAiChatModel extends OpenAiChatModel {

	private static final Logger logger = LoggerFactory.getLogger(ReasoningAwareOpenAiChatModel.class);

	private final ToolCallingManager toolCallingManager;

	/**
	 * 当前实例所属供应方的主键（DB 供应方 id；env 合成行为 {@code null}）。
	 *
	 * <p>用于对比消息 metadata 里的 {@link ReasoningOrigin}：只有同供应方才回放 reasoning_content。
	 * {@code null} = 未接线（轻装配/直接构造的单测）：<b>有标记</b>的消息因此判为"当前未知"
	 * 而不回放；<b>无标记</b>的消息同样不回放（来源未知 = fail-closed）—— 见
	 * {@link ReasoningOrigin#replayAllowed}。
	 */
	private final Long currentProviderId;

	public ReasoningAwareOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry) {
		// 旧签名（单测/非 registry 装配）：不携带当前供应方身份，隔离判定按 fail-closed 处理
		this(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry, null);
	}

	public ReasoningAwareOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry, Long currentProviderId) {
		super(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry);
		this.toolCallingManager = toolCallingManager;
		this.currentProviderId = currentProviderId;
	}

	/** 当前实例的供应方主键（诊断/接线断言用；{@code null} = 未接线）。 */
	public Long currentProviderId() {
		return this.currentProviderId;
	}

	/**
	 * 复制父类实现，唯一差异：ASSISTANT 分支传播 properties 中的 reasoningContent（父类硬编码 null）。
	 *
	 * <p>本批：传播前先过<b>来源隔离</b> —— 消息的来源标记与当前供应方不一致时，本字段置 null
	 * （正文 / toolCalls / 后续 role:tool 一律不动）。
	 */
	@Override
	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

		List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(message -> {
			if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
				// 简化为纯文本（本项目不涉及多模态输入）
				return List.of(new ChatCompletionMessage(message.getText(),
						ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				var assistantMessage = (AssistantMessage) message;
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ChatCompletionFunction(toolCall.name(), toolCall.arguments());
						return new ToolCall(toolCall.id(), toolCall.type(), function);
					}).toList();
				}
				// 补丁要点：回放 metadata 中的 reasoningContent（父类第 9 组件恒为 null）。
				// 跨供应方隔离：来源标记与当前供应方一致才回放（无标记 = 来源未知，不回放）。
				Object reasoning = assistantMessage.getMetadata().get("reasoningContent");
				boolean replay = ReasoningOrigin.replayAllowed(assistantMessage.getMetadata(),
						this.currentProviderId);
				String reasoningContent = replay && reasoning instanceof String s && StringUtils.hasText(s) ? s : null;
				if (logger.isDebugEnabled() && !replay && reasoning instanceof String) {
					// 只影响请求体；历史原文仍在快照/记忆里（隔离不删字）
					logger.debug("Skipped reasoning content replay because the message origin does not match the current provider (currentProviderId={})",
							this.currentProviderId);
				}
				return List.of(new ChatCompletionMessage(assistantMessage.getText(),
						ChatCompletionMessage.Role.ASSISTANT, null, null, toolCalls, null, null, null,
						reasoningContent));
			}
			else if (message.getMessageType() == MessageType.TOOL) {
				ToolResponseMessage toolMessage = (ToolResponseMessage) message;
				toolMessage.getResponses()
					.forEach(response -> Assert.isTrue(response.id() != null, "ToolResponseMessage must have an id"));
				return toolMessage.getResponses()
					.stream()
					.map(tr -> new ChatCompletionMessage(tr.responseData(), ChatCompletionMessage.Role.TOOL, tr.name(),
							tr.id(), null, null, null, null, null))
					.toList();
			}
			else {
				throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
			}
		}).flatMap(List::stream).toList();

		ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages, stream);

		OpenAiChatOptions requestOptions = (OpenAiChatOptions) prompt.getOptions();
		request = ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class);

		// Add the tool definitions to the request's tools parameter.
		List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			request = ModelOptionsUtils.merge(OpenAiChatOptions.builder()
				.tools(this.getFunctionTools(toolDefinitions))
				.extraBody(request.extraBody())
				.build(), request, ChatCompletionRequest.class);
		}

		// Remove `streamOptions` from the request if it is not a streaming request
		if (request.streamOptions() != null && !stream) {
			logger.warn("Removing streamOptions from the request as it is not a streaming request!");
			request = request.streamOptions(null);
		}

		return request;
	}

	private List<OpenAiApi.FunctionTool> getFunctionTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var function = new OpenAiApi.FunctionTool.Function(toolDefinition.description(), toolDefinition.name(),
					toolDefinition.inputSchema());
			return new OpenAiApi.FunctionTool(function);
		}).toList();
	}

}
