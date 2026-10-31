package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.configmgr.ai.config.AiModelConfig;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ToolExecutionEnvelope;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>记忆 advisor 的写必须与人工收敛走同一个按轮合并入口</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 生产装配里 advisor 写的是 {@code MessageWindowChatMemory#add}：它读整窗、追加本轮两条、
 * 再整窗 {@code saveAll}。同一会话里"挂起轮 A 的收敛"与"新轮 B 的 advisor 写"交错时，
 * 后落地的那一笔会把对方整轮从 {@code /history}（唯一读源）删掉；多实例下这一笔还可能是
 * 基于过期读的覆盖。若只在 {@code convergeMemory} 里做合并、advisor 仍走整窗覆盖，
 * 这条旁路会把修复吞掉 —— 因此本用例锁的是<b>生产装配</b>（{@link AiModelConfig#chatMemory}）。
 *
 * <p>断言对象：最终窗口内容（顺序、正文、归属），不是调用次数。
 */
class RunScopedAdvisorConvergenceTest {

	private static final String SESSION = "sess-run-scoped-advisor";

	private static final long TENANT = 7L;

	private static final long USER = 701L;

	private static final String RUN_A = "run-a";

	private static final String RUN_B = "run-b";

	private static final int WINDOW = 40;

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			Duration.ofHours(24));

	private final AiProperties properties = new AiProperties();

	private final ChatMemory memory = new AiModelConfig().chatMemory(this.repository, this.properties);

	@Test
	void advisorWritesAreScopedToItsOwnRunAndASiblingConvergeCannotDeleteThem() {
		assertThat(this.memory)
			.as("生产装配的记忆写必须落在按轮合并入口（否则 advisor 还是整窗覆盖的旁路）")
			.isInstanceOf(RunScopedChatMemory.class);

		streamOneTurnThroughTheAdvisor();

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			// 挂起轮 A 在 B 之后才收敛（"B 先收 / A 后收"的 advisor 版本）。
			this.repository.upsertRun(SESSION, new MemoryTurn(RUN_A, 1_000L),
					List.of(new UserMessage("A问"), AssistantMessage.builder().content("A答").build()), WINDOW, true);

			List<Message> window = this.repository.findByConversationId(SESSION);
			assertThat(window).extracting(Message::getText)
				.as("A 的收敛不得删掉 advisor 写下的 B 整轮")
				.containsExactly("A问", "A答", "B问", "B答");
			assertThat(RunMemoryMerge.runIdOf(window.get(2))).as("advisor 的写必须带本轮归属").isEqualTo(RUN_B);
			assertThat(RunMemoryMerge.runIdOf(window.get(0))).isEqualTo(RUN_A);
		}
	}

	private void streamOneTurnThroughTheAdvisor() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_B);
		snapshot.setSessionId(SESSION);
		snapshot.setTenantId(TENANT);
		snapshot.setUserId(USER);
		snapshot.setCreatedAtMs(2_000L);
		snapshot.setCreatedBy("test-instance");
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(snapshot, 0L);

		ChatClient client = ChatClient.builder(new EchoModel())
			.defaultAdvisors(new AiModelConfig().messageChatMemoryAdvisor(this.memory))
			.build();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			client.prompt()
				.user("B问")
				.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, SESSION))
				.toolContext(Map.of(ToolExecutionEnvelope.TOOL_CONTEXT_KEY, envelope))
				.stream()
				.chatResponse()
				.collectList()
				.block(Duration.ofSeconds(10));
		}
	}

	/** 只回一条助手回答的模型替身。 */
	private static final class EchoModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("本用例只走流式回调");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(new ChatResponse(
					List.of(new Generation(AssistantMessage.builder().content("B答").build()))));
		}

	}

}
