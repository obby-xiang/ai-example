package org.springframework.ai.openai;

import java.util.List;

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

/**
 * 对 Spring AI 1.1.8 已知缺口的补丁：{@code OpenAiChatModel.createRequest} 的 ASSISTANT
 * 分支把第 9 组件 reasoningContent 硬编码为 null，而 deepseek-flash（思考模式，OpenAI
 * 兼容协议）要求多轮请求回填 assistant 消息时携带本轮 reasoning_content，缺失会 400。
 * 本类放在同名包下以 Override 包私有的 createRequest，逻辑复制父类（USER 媒体分支
 * 简化为纯文本、TOOL 的 ToolResponseMessage 展开、options 用 ModelOptionsUtils.merge、
 * 工具定义 resolveToolDefinitions + FunctionTool 构造且合并时保留 extraBody、非流式
 * 移除 streamOptions），唯一差异：ASSISTANT 分支第 9 组件从
 * {@code assistantMessage.getMetadata().get("reasoningContent")} 传播。
 * 升级 Spring AI 版本后应评审官方是否已修复并移除本补丁。
 */
public class ReasoningAwareOpenAiChatModel extends OpenAiChatModel {

	private static final Logger logger = LoggerFactory.getLogger(ReasoningAwareOpenAiChatModel.class);

	private final ToolCallingManager toolCallingManager;

	public ReasoningAwareOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry) {
		super(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry);
		this.toolCallingManager = toolCallingManager;
	}

	/**
	 * 复制父类实现，唯一差异：ASSISTANT 分支传播 properties 中的 reasoningContent（父类硬编码 null）。
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
				// 补丁要点：回放 metadata 中的 reasoningContent（父类第 9 组件恒为 null）
				Object reasoning = assistantMessage.getMetadata().get("reasoningContent");
				String reasoningContent = reasoning instanceof String s ? s : null;
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
