package com.example.spike.config;

import com.example.spike.ai.SpikeToolCallingManager;
import com.example.spike.bus.EventHub;
import com.example.spike.tools.SpikeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 官方扩展点接法（与合流参照实现 AiModelConfig 完全一致）：
 * OpenAiChatModel.builder().toolCallingManager(hooks)——delegate 为官方 ToolCallingManager.builder()，
 * 本 PoC 只组合包装，不接管协议与循环。
 */
@Configuration
@EnableConfigurationProperties(SpikeProperties.class)
public class SpikeConfig {

	@Bean
	public SpikeTools spikeTools() {
		return new SpikeTools();
	}

	@Bean
	public ToolCallback[] spikeToolCallbacks(SpikeTools tools) {
		return MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
	}

	@Bean
	public SpikeToolCallingManager spikeToolCallingManager(ToolCallbackResolver toolCallbackResolver,
			ToolExecutionExceptionProcessor exceptionProcessor, ObjectProvider<ObservationRegistry> observationRegistry,
			EventHub hub, SpikeProperties properties, ObjectMapper mapper) {
		ToolCallingManager delegate = ToolCallingManager.builder()
			.toolCallbackResolver(toolCallbackResolver)
			.toolExecutionExceptionProcessor(exceptionProcessor)
			.observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
			.build();
		return new SpikeToolCallingManager(delegate, hub, properties, mapper);
	}

	@Bean
	@Primary
	public OpenAiChatModel spikeChatModel(OpenAiApi openAiApi, OpenAiChatProperties properties,
			SpikeToolCallingManager hooks, ObjectProvider<RetryTemplate> retryTemplate,
			ObjectProvider<ObservationRegistry> observationRegistry) {
		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.model(properties.getOptions().getModel())
			.temperature(properties.getOptions().getTemperature())
			.build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(options)
			.toolCallingManager(hooks)
			.retryTemplate(retryTemplate.getIfAvailable(RetryTemplate::new))
			.observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
			.build();
	}

	@Bean
	public ChatClient spikeChatClient(OpenAiChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}

	@Bean("spikeExecutor")
	public TaskExecutor spikeExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(8);
		executor.setQueueCapacity(32);
		executor.setThreadNamePrefix("spike-agent-");
		return executor;
	}

}
