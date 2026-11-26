package com.example.configmgr.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import reactor.core.publisher.Flux;

public final class ConcurrencyLimitedChatModel implements ChatModel {

    private final ChatModel delegate;

    private final ModelConcurrencyLimiter limiter;

    ConcurrencyLimitedChatModel(ChatModel delegate, ModelConcurrencyLimiter limiter) {
        this.delegate = delegate;
        this.limiter = limiter;
    }

    public static Boolean getReasoningReplayEnabled(ChatModel model) {
        if (model instanceof ReasoningAwareOpenAiChatModel reasoningModel) {
            return reasoningModel.isReasoningReplayEnabled();
        }
        if (model instanceof ConcurrencyLimitedChatModel limited) {
            return getReasoningReplayEnabled(limited.delegate);
        }
        return null;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return this.limiter.call(() -> this.delegate.call(prompt));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model call interrupted", ex);
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("model call failed", ex);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return this.limiter.stream(() -> this.delegate.stream(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return this.delegate.getDefaultOptions();
    }
}
