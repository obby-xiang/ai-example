package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B13 / B14（热生效）：改 base-url ⇒ <b>下一个请求打到新端点</b>；同 provider 换 model ⇒ 不重建 HTTP 客户端。
 *
 * <p>
 * 这里不是"断言注册表的字段变了"，而是<b>真发一次流式请求</b>：两个本地 HTTP 桩各记命中次数与请求体，
 * 于是"下一轮打到新端点"是端口计数的事实，而不是我的推断；"请求体 model 变了"是桩抓到的原值。
 *
 * <p>
 * <b>证红</b>
 * <ul>
 * <li>B13：把 {@code RuntimeKey} 里的 {@code baseUrl} 去掉 ⇒ 缓存键不变 ⇒ 仍打旧端点 ⇒ 红；</li>
 * <li>B14：把 model 放进 {@code RuntimeKey} ⇒ 换模型后 api 实例被重建（{@code isSameAs} 红）；</li>
 * <li>B14 的另一面：把"模型变了"当无事发生（不重绑）⇒ 请求体里的 model 仍是旧值 ⇒ 红。</li>
 * </ul>
 */
class ProviderRegistryHotReloadTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final List<StubEndpoint> stubs = new ArrayList<>();

	private AiProvider provider;

	@BeforeEach
	void setUp() {
		this.provider = this.fixture.addEnvProvider("deepseek", "http://placeholder");
		this.fixture.addModel(this.provider.getId(), "deepseek-flash", true);
	}

	@AfterEach
	void tearDown() {
		this.stubs.forEach(StubEndpoint::stop);
	}

	// ── B13：base-url 变 ⇒ 重建 + 下一轮打到新端点 ────────────────────────────

	@Test
	void baseUrlChangeRebuildsTheRuntimeAndTheNextRequestHitsTheNewEndpoint() throws Exception {
		StubEndpoint oldEndpoint = stub("old");
		StubEndpoint newEndpoint = stub("new");
		this.provider.setBaseUrl(oldEndpoint.baseUrl());
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);

		ProviderRuntime first = registry.current();
		assertThat(first.baseUrl()).isEqualTo(oldEndpoint.baseUrl());
		assertThat(chat(first)).isNotEmpty();
		assertThat(oldEndpoint.hits()).isEqualTo(1);
		assertThat(newEndpoint.hits()).isZero();

		// 写路径语义：改行 + 版本号 +1（批② 的 UPDATE ... + INCR ai:cfg:ver）
		this.provider.setBaseUrl(newEndpoint.baseUrl());
		this.fixture.bumpVersion();

		ProviderRuntime second = registry.current();
		assertThat(second.runtimeKey()).isNotEqualTo(first.runtimeKey());
		assertThat(second.api()).as("base-url 变了必须换一套实例（新 WebClient）").isNotSameAs(first.api());
		assertThat(registry.runtimeCount()).as("旧 runtime 被淘汰，缓存只有当前一条").isEqualTo(1);

		assertThat(chat(second)).isNotEmpty();
		assertThat(newEndpoint.hits()).as("下一轮请求打到新端点").isEqualTo(1);
		assertThat(oldEndpoint.hits()).as("旧端点不再收到新请求").isEqualTo(1);
		assertThat(newEndpoint.lastBody()).contains("\"model\":\"deepseek-flash\"");
	}

	// ── B14：换 model ⇒ 不重建 HTTP 客户端，但请求体换了模型 ─────────────────

	@Test
	void modelChangeRebindsTheModelButKeepsTheSameHttpApi() throws Exception {
		StubEndpoint endpoint = stub("single");
		this.provider.setBaseUrl(endpoint.baseUrl());
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);

		ProviderRuntime first = registry.current();
		assertThat(chat(first)).isNotEmpty();
		assertThat(endpoint.lastBody()).contains("\"model\":\"deepseek-flash\"");

		this.fixture.models.get(0).setModelId("deepseek-pro");
		this.fixture.bumpVersion();

		ProviderRuntime second = registry.current();
		assertThat(second.modelId()).isEqualTo("deepseek-pro");
		assertThat(second.runtimeKey()).as("模型不在 RuntimeKey 里").isEqualTo(first.runtimeKey());
		assertThat(second.api()).as("换模型不重建 HTTP 客户端").isSameAs(first.api());
		assertThat(second.chatClient()).as("模型/客户端轻量重绑").isNotSameAs(first.chatClient());
		assertThat(registry.runtimeCount()).isEqualTo(1);

		assertThat(chat(second)).isNotEmpty();
		assertThat(endpoint.lastBody()).as("请求体的 model 跟着换").contains("\"model\":\"deepseek-pro\"");
	}

	// ── 选项等价：spring.ai.openai.chat.options 的逐值继承 ───────────────────

	@Test
	void runtimeOptionsInheritTheEnvDefaultsIncludingStreamUsage() {
		this.provider.setBaseUrl("http://placeholder");
		AiProviderRegistry registry = this.fixture.registry(this.fixture.service());

		ProviderRuntime runtime = registry.current();

		assertThat(runtime.options().getModel()).isEqualTo("deepseek-flash");
		assertThat(runtime.options().getTemperature()).isEqualTo(0.2d);
		assertThat(runtime.options().getMaxTokens()).isEqualTo(4096);
		assertThat(runtime.options().getStreamUsage()).as("done 终帧 usage 的来源，换 provider 不许丢")
			.isTrue();
	}

	@Test
	void aiSettingDefaultsOverrideTheEnvOptionsWhenPresent() {
		this.provider.setBaseUrl("http://placeholder");
		this.fixture.settings.put("default.temperature", "0.7");
		this.fixture.settings.put("default.topP", "0.9");
		this.fixture.settings.put("default.maxTokens", "2048");
		AiProviderRegistry registry = this.fixture.registry(this.fixture.service());

		ProviderRuntime runtime = registry.current();

		assertThat(runtime.options().getTemperature()).isEqualTo(0.7d);
		assertThat(runtime.options().getTopP()).isEqualTo(0.9d);
		assertThat(runtime.options().getMaxTokens()).isEqualTo(2048);
		assertThat(runtime.options().getStreamUsage()).isTrue();
	}

	/**
	 * M3（复审）：{@code ai_setting} 是人工可写的键值表，脏值不许把整条链路打成 500/异常。
	 *
	 * <p>期望：坏项逐项 WARN + 忽略（沿用 {@code spring.ai.openai.*} 缺省），好项照常生效。
	 * <b>证红</b>：把 {@code buildOptions} 里的 try/catch 去掉（直接 {@code Double.valueOf}）⇒
	 * 本例在 {@code NumberFormatException} 上抛错 ⇒ 红。
	 */
	@Test
	void dirtyAiSettingValuesAreIgnoredWithAWarningInsteadOfBlowingUp() {
		this.provider.setBaseUrl("http://placeholder");
		this.fixture.settings.put("default.temperature", "hot");
		this.fixture.settings.put("default.topP", "NaN");
		this.fixture.settings.put("default.maxTokens", "-5");
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiProviderRegistry.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
				new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			ProviderRuntime runtime = this.fixture.registry(this.fixture.service()).current();

			// 三项都被忽略 ⇒ 值与 spring.ai.openai.chat.options 的缺省逐值相同
			assertThat(runtime.options().getTemperature()).isEqualTo(0.2d);
			assertThat(runtime.options().getTopP()).isNull();
			assertThat(runtime.options().getMaxTokens()).isEqualTo(4096);
			assertThat(runtime.options().getStreamUsage()).isTrue();
			assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
				.hasSize(3);
			assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
				.doesNotContain("env-probe"));
		}
		finally {
			logger.detachAppender(appender);
		}
	}

	// ── 桩与调用 ────────────────────────────────────────────────────────────

	private String chat(ProviderRuntime runtime) {
		List<ChatResponse> responses = runtime.chatModel()
			.stream(new Prompt(List.of(new UserMessage("你好"))))
			.collectList()
			.block(Duration.ofSeconds(15));
		return responses == null ? ""
				: responses.stream().map(response -> String.valueOf(response.getResult().getOutput().getText()))
					.reduce("", String::concat);
	}

	private StubEndpoint stub(String name) throws IOException {
		StubEndpoint endpoint = new StubEndpoint(name);
		this.stubs.add(endpoint);
		return endpoint;
	}

	/** 最小 OpenAI 兼容流式端点：记命中次数与请求体，回一串 chunk + {@code [DONE]}。 */
	private static final class StubEndpoint {

		private final HttpServer server;

		private final List<String> bodies = new CopyOnWriteArrayList<>();

		StubEndpoint(String name) throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", this::handle);
			this.server.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		int hits() {
			return this.bodies.size();
		}

		String lastBody() {
			return this.bodies.isEmpty() ? "" : this.bodies.get(this.bodies.size() - 1);
		}

		void stop() {
			this.server.stop(0);
		}

		private void handle(HttpExchange exchange) throws IOException {
			String body;
			try (InputStream input = exchange.getRequestBody()) {
				body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}
			this.bodies.add(body);
			String chunk = "data: {\"id\":\"stub-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
					+ "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
					+ "\"content\":\"桩回复\"},\"finish_reason\":null}]}\n\n" + "data: {\"id\":\"stub-1\","
					+ "\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub-model\",\"choices\":"
					+ "[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n";
			byte[] payload = chunk.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
		}
	}

}
