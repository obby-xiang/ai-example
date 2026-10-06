package com.example.spike.config;

import java.time.Duration;
import java.util.List;

import com.example.spike.ai.Sp02ToolCallingManager;
import com.example.spike.ai.SpikeEligibilityPredicate;
import com.example.spike.core.ExplicitLoopRunner;
import com.example.spike.core.RunStore;
import com.example.spike.core.RuntimeRegistry;
import com.example.spike.core.ToolCallExecutor;
import com.example.spike.memory.MessageJsonCodec;
import com.example.spike.tools.SpikeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.support.RetryTemplate;

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
	public MessageJsonCodec messageJsonCodec(ObjectMapper mapper) {
		return new MessageJsonCodec(mapper);
	}

	@Bean
	public RunStore runStore(StringRedisTemplate redis, ObjectMapper mapper, SpikeProperties properties) {
		return new RunStore(redis, mapper, Duration.ofHours(properties.getRunTtlHours()));
	}

	/** 官方 DefaultToolCallingManager（只借它执行后端工具，不接管协议）。 */
	@Bean
	public ToolCallingManager defaultToolCallingManager(ToolCallbackResolver toolCallbackResolver,
			ToolExecutionExceptionProcessor exceptionProcessor, ObjectProvider<ObservationRegistry> observationRegistry) {
		return ToolCallingManager.builder()
			.toolCallbackResolver(toolCallbackResolver)
			.toolExecutionExceptionProcessor(exceptionProcessor)
			.observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
			.build();
	}

	@Bean
	public ToolCallExecutor toolCallExecutor(ToolCallingManager defaultToolCallingManager, RunStore store,
			RuntimeRegistry registry, SpikeProperties properties, ObjectMapper mapper) {
		return new ToolCallExecutor(defaultToolCallingManager, store, registry, properties, mapper);
	}

	@Bean
	public Sp02ToolCallingManager sp02ToolCallingManager(ToolCallingManager defaultToolCallingManager, RunStore store,
			RuntimeRegistry registry, ToolCallExecutor executor, MessageJsonCodec codec) {
		return new Sp02ToolCallingManager(defaultToolCallingManager, store, registry, executor, codec);
	}

	@Bean
	public SpikeEligibilityPredicate spikeEligibilityPredicate(SpikeProperties properties) {
		return new SpikeEligibilityPredicate(properties.isSuppressExternalRounds());
	}

	@Bean
	@Primary
	public OpenAiChatModel spikeChatModel(OpenAiApi openAiApi, OpenAiChatProperties properties,
			Sp02ToolCallingManager hooks, SpikeEligibilityPredicate predicate, ObjectProvider<RetryTemplate> retryTemplate,
			ObjectProvider<ObservationRegistry> observationRegistry) {
		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.model(properties.getOptions().getModel())
			.temperature(properties.getOptions().getTemperature())
			.build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(options)
			.toolCallingManager(hooks)
			.toolExecutionEligibilityPredicate(predicate)
			.retryTemplate(retryTemplate.getIfAvailable(RetryTemplate::new))
			.observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
			.build();
	}

	@Bean
	public ExplicitLoopRunner explicitLoopRunner(OpenAiChatModel spikeChatModel, ToolCallback[] spikeToolCallbacks,
			RunStore store, RuntimeRegistry registry, ToolCallExecutor executor, MessageJsonCodec codec,
			SpikeProperties properties) {
		return new ExplicitLoopRunner(spikeChatModel, List.of(spikeToolCallbacks), store, registry, executor, codec,
				properties);
	}

}
