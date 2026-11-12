package org.springframework.ai.openai;

import java.util.Iterator;
import java.util.List;

import com.example.configmgr.ai.memory.ReasoningOrigin;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Spring AI 1.1.7 的 OpenAI 兼容扩展适配：父类 {@code OpenAiChatModel.createRequest}
 * 不传播 assistant 消息 metadata 中的 reasoningContent，而部分兼容服务把非标准的
 * {@code reasoning_content} 作为逐消息扩展，并要求工具续轮回放实际产生的推理段。
 * 本类放在同名包下以 Override 包私有的 createRequest，先由父类完成标准请求组装，
 * 再仅合并受能力与来源约束的 reasoningContent 增量。
 *
 * <p>{@code reasoning_content} 不是 OpenAI 标准字段。只有当前 provider + model 显式声明
 * {@code supportsReasoning=true}、{@link ReasoningOrigin#replayAllowed} 确认同源且值非空白时才回放；
 * 旧构造、缺能力、空白值、来源未知或跨 provider/model 都不生成该字段。其它 metadata 仍由快照和记忆
 * 原样保存，不因此进入请求。升级 Spring AI 版本后应评审官方是否已覆盖该扩展点并移除本适配。
 */
public class ReasoningAwareOpenAiChatModel extends OpenAiChatModel {

	private static final Logger logger = LoggerFactory.getLogger(ReasoningAwareOpenAiChatModel.class);

	/** 当前实例所属供应方主键；{@code null} = 未接线，回放按 fail-closed 处理。 */
	private final Long currentProviderId;

	private final boolean supportsReasoning;

	public ReasoningAwareOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry) {
		this(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry, null);
	}

	public ReasoningAwareOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry, Long currentProviderId) {
		this(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry, currentProviderId, false);
	}

	public ReasoningAwareOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry, Long currentProviderId, boolean supportsReasoning) {
		super(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry);
		this.currentProviderId = currentProviderId;
		this.supportsReasoning = supportsReasoning;
	}

	public boolean isReasoningReplayEnabled() {
		return this.supportsReasoning;
	}

	/** 当前实例的供应方主键（诊断/接线断言用；{@code null} = 未接线）。 */
	public Long currentProviderId() {
		return this.currentProviderId;
	}

	/** 父类负责标准消息、工具及 options；此处只替换 assistant 消息并保留完整请求。 */
	@Override
	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
		ChatCompletionRequest parent = super.createRequest(prompt, stream);
		Iterator<AssistantMessage> assistants = prompt.getInstructions().stream()
			.filter(message -> message.getMessageType() == MessageType.ASSISTANT)
			.map(AssistantMessage.class::cast)
			.iterator();
		List<ChatCompletionMessage> messages = parent.messages().stream().map(message -> {
			if (message.role() != ChatCompletionMessage.Role.ASSISTANT) {
				return message;
			}
			Assert.isTrue(assistants.hasNext(), "Parent request has more assistant messages than the prompt");
			return withReasoning(message, assistants.next(), prompt);
		}).toList();
		Assert.isTrue(!assistants.hasNext(), "Prompt has more assistant messages than the parent request");
		ChatCompletionRequest increment = new ChatCompletionRequest(messages, stream);
		ChatCompletionRequest merged = ModelOptionsUtils.merge(increment, parent, ChatCompletionRequest.class);
		return ModelOptionsUtils.merge(OpenAiChatOptions.builder().extraBody(parent.extraBody()).build(), merged,
				ChatCompletionRequest.class);
	}

	private ChatCompletionMessage withReasoning(ChatCompletionMessage target, AssistantMessage source, Prompt prompt) {
		String reasoningContent = replayableReasoning(source, prompt);
		if (reasoningContent == null) {
			return target;
		}
		return new ChatCompletionMessage(target.rawContent(), target.role(), target.name(), target.toolCallId(),
				target.toolCalls(), target.refusal(), target.audioOutput(), target.annotations(), reasoningContent);
	}

	private String replayableReasoning(AssistantMessage source, Prompt prompt) {
		Object reasoning = source.getMetadata().get("reasoningContent");
		boolean replay = this.supportsReasoning && ReasoningOrigin.replayAllowed(source.getMetadata(),
				this.currentProviderId, ((OpenAiChatOptions) prompt.getOptions()).getModel());
		if (logger.isDebugEnabled() && !replay && reasoning instanceof String) {
			logger.debug("Skipped reasoning content replay because capability or origin does not allow it (currentProviderId={})",
					this.currentProviderId);
		}
		return replay && reasoning instanceof String text && StringUtils.hasText(text) ? text : null;
	}

}
