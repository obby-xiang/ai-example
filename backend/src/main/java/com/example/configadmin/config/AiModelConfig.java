package com.example.configadmin.config;

import com.example.configadmin.ai.AiSessionStore;
import com.example.configadmin.ai.HooksToolCallingManager;
import com.example.configadmin.ai.Services;
import com.example.configadmin.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

/**
 * 组装带 HITL/可见性钩子的 OpenAI 模型（Spring AI 官方扩展点：
 * OpenAiChatModel.Builder.toolCallingManager(...)），协议与循环仍由 Spring AI 完成。
 * - delegate 用官方 DefaultToolCallingManager（ToolCallingManager.builder() + 自动配置的
 *   ToolCallbackResolver/ToolExecutionExceptionProcessor），组合包装而非自行实现；
 * - 复用自动配置的 OpenAiApi 与 OpenAiChatProperties（密钥/地址/模型名来自 application.yml）。
 */
@Configuration
public class AiModelConfig {

    @Bean
    public HooksToolCallingManager hooksToolCallingManager(
            ToolCallbackResolver toolCallbackResolver,
            ToolExecutionExceptionProcessor exceptionProcessor,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectMapper mapper,
            AiSessionStore sessionStore) {
        ToolCallingManager delegate = ToolCallingManager.builder()
                .toolCallbackResolver(toolCallbackResolver)
                .toolExecutionExceptionProcessor(exceptionProcessor)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
        return new HooksToolCallingManager(delegate, mapper, sessionStore);
    }

    @Bean
    public Services aiServices(ConfigDefService defService, ConfigDataService dataService,
                               ExportService exportService, ImportService importService,
                               TaskRunner taskRunner, ObjectMapper mapper) {
        return new Services(defService, dataService, exportService, importService, taskRunner, mapper);
    }

    @Bean
    @Primary
    public OpenAiChatModel toolHooksChatModel(OpenAiApi openAiApi,
                                              OpenAiChatProperties properties,
                                              HooksToolCallingManager hooks,
                                              ObjectProvider<RetryTemplate> retryTemplate,
                                              ObjectProvider<ObservationRegistry> observationRegistry) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getOptions().getModel())
                .temperature(properties.getOptions().getTemperature())
                .topP(properties.getOptions().getTopP())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .toolCallingManager(hooks)
                .retryTemplate(retryTemplate.getIfAvailable(RetryTemplate::new))
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }
}
