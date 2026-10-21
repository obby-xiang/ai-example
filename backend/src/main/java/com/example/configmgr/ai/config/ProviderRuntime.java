package com.example.configmgr.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 一个 provider 实例的运行态（模型配置专项 §3.4）。
 *
 * <p>
 * 由 {@link AiProviderRegistry} 按 {@code RuntimeKey = providerId|keyFingerprint|baseUrl|completionsPath}
 * 缓存并复用：同一个 key 下 api/model/client 是三件套；key 变了就整套重建（下一次取用），
 * 在飞请求继续持有旧引用跑完（语义正确，不需要取消在飞轮）。
 *
 * <p>
 * {@link #api} 单独暴露，是因为"同 provider 换 model"不需要重建 HTTP 客户端（WebClient）——
 * 换模型只换 {@link #chatModel}/{@link #chatClient}（批③ 改成逐请求 options 后连这一步都不需要）。
 */
public record ProviderRuntime(Long providerId, String providerCode, String modelId, String baseUrl,
        String completionsPath, String runtimeKey, OpenAiChatOptions options, OpenAiApi api, ChatModel chatModel,
        ChatClient chatClient) {
}
