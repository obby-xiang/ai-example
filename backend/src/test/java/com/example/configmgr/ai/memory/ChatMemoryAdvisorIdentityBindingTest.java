package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.configmgr.ai.config.AiModelConfig;
import com.example.configmgr.ai.gate.ToolExecutionEnvelope;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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
 * 模型流式回调里的记忆读写必须用<b>本轮请求/记录的身份</b>，不得落进无归属 legacy 命名空间。
 *
 * <h2>被钉住的缺陷</h2>
 * 官方 {@code MessageChatMemoryAdvisor} 的读在 {@code before}（跑在 {@code boundedElastic}）、写在
 * {@code after}（跑在上游发元素的线程）。两条线程都<b>没有请求身份</b>，而仓库键由线程身份拼出 ⇒
 * 记忆被写成无身份上下文的键形 {@code chat:mem:{<sessionId>}}（legacy 命名空间；迁移期裸键
 * {@code chat:mem:<sessionId>} 同族）与裸索引成员 {@code <sessionId>}：
 * <ul>
 * <li>同一 sessionId 在另一个租户/用户下会读到这段正文（跨身份泄露）；</li>
 * <li>按身份拼键的会话销毁删不到它 ⇒ {@code complete=true} 之后 legacy 正文仍留在 Redis；</li>
 * <li>下一轮在正常线程上（有身份）读到的是身份键（空窗）—— 用户看到"上下文丢了"。</li>
 * </ul>
 *
 * <h2>本用例的口径</h2>
 * 发起线程与模型发元素的线程<b>都</b>没有身份（{@link RunIdentityScope#denied()} + 裸线程池），
 * 身份只存在于本轮请求携带的记录事实（{@link ToolExecutionEnvelope}）里：修好后读（身份键里的旧历史
 * 必须进模型上下文）与写（身份键、身份索引成员）都按它闭合，legacy 键/裸索引成员一个都不出现；
 * 按身份删除（会话销毁的记忆面）之后也不得留下 legacy 正文。
 */
class ChatMemoryAdvisorIdentityBindingTest {

	private static final String SESSION = "sess-upstream-callback-20261030";

	private static final long TENANT = 7L;

	private static final long USER = 701L;

	private static final String USER_TEXT = "第一句";

	private static final String ASSISTANT_TEXT = "助手回答";

	private static final String OLD_IDENTITY_TEXT = "身份旧历史";

	private static final Duration TTL = Duration.ofHours(24);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final ChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec, TTL);

	private final ChatMemory memory = MessageWindowChatMemory.builder()
		.chatMemoryRepository(this.repository)
		.maxMessages(40)
		.build();

	private final UpstreamStreamingModel model = new UpstreamStreamingModel();

	@Test
	void streamingCallbacksOnIdentityLessThreadsUseTheTurnRecordIdentityForReadAndWrite() throws Exception {
		// 身份键上先有一份旧历史：读路径必须命中它（而不是 legacy 键，也不是空窗）。
		String identityKey = "chat:mem:" + TENANT + ":" + USER + ":{" + SESSION + "}";
		this.fake.lists.put(identityKey,
				new CopyOnWriteArrayList<>(this.codec.serializeAll(List.of(new UserMessage(OLD_IDENTITY_TEXT)))));

		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId("run-identity-binding");
		snapshot.setSessionId(SESSION);
		snapshot.setTenantId(TENANT);
		snapshot.setUserId(USER);
		snapshot.setCreatedBy("test-instance");
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(snapshot, 0L);

		ChatClient client = ChatClient.builder(this.model)
			// 生产装配口：AiModelConfig 的记忆 advisor bean（修好后 = 身份绑定装饰器）
			.defaultAdvisors(new AiModelConfig().messageChatMemoryAdvisor(this.memory))
			.build();

		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			List<ChatResponse> responses = client.prompt()
				.user(USER_TEXT)
				.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, SESSION))
				.toolContext(Map.of(ToolExecutionEnvelope.TOOL_CONTEXT_KEY, envelope))
				.stream()
				.chatResponse()
				.collectList()
				.block(Duration.ofSeconds(10));

			assertThat(responses).as("上游必须给出终帧").isNotEmpty();
		}

		// 无归属的两个 legacy 形：旧版本写过的裸键 chat:mem:<sid>，与"无身份上下文"的键形
		// chat:mem:{<sid>}（仓储把后者叫 legacy 命名空间）。两者都不该由本轮写出来。
		String legacyKey = "chat:mem:" + SESSION;
		String legacyNamespaceKey = "chat:mem:{" + SESSION + "}";
		assertThat(this.fake.lists).as("无线程身份的上游回调不得写无归属 legacy 键")
			.doesNotContainKeys(legacyKey, legacyNamespaceKey);
		assertThat(this.fake.sets.get(RedisChatMemoryRepository.CONVERSATION_IDS_KEY))
			.as("全局索引成员必须带本轮身份，不得出现裸索引")
			.contains(TENANT + "/" + USER + "/" + SESSION)
			.doesNotContain(SESSION);

		assertThat(this.fake.lists).as("身份键必须存在").containsKey(identityKey);
		assertThat(this.fake.lists.get(identityKey)).as("本轮用户消息与助手回答都写在身份键上")
			.extracting(payload -> this.codec.deserialize(payload).getText())
			.contains(OLD_IDENTITY_TEXT, USER_TEXT, ASSISTANT_TEXT);

		assertThat(this.model.promptTexts())
			.as("读路径必须命中本轮记录身份的记忆键（旧历史要进模型上下文）")
			.hasSize(1)
			.allSatisfy(texts -> assertThat(texts).contains(OLD_IDENTITY_TEXT, USER_TEXT));

		// 会话销毁的记忆面：按身份删除（请求线程身份）后不得残留 legacy 正文。
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(TENANT, USER)) {
			this.repository.deleteByConversationId(SESSION);
		}
		assertThat(this.fake.lists).as("销毁后身份键必须删净").doesNotContainKey(identityKey);
		assertThat(this.fake.lists).as("销毁后不得残留 legacy 正文").doesNotContainKeys(legacyKey, legacyNamespaceKey);
	}

	/**
	 * 流式模型替身：<b>在独立线程池上发元素</b>（该池没有 TTL 装饰 ⇒ 发元素时没有任何线程身份），
	 * 并记下每次请求真正的消息文本（读路径的证据）。
	 */
	private static final class UpstreamStreamingModel implements ChatModel {

		private final ExecutorService upstream = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "upstream-no-identity");
			thread.setDaemon(true);
			return thread;
		});

		private final List<List<String>> prompts = new CopyOnWriteArrayList<>();

		List<List<String>> promptTexts() {
			return this.prompts;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("本用例只走流式回调");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			this.prompts.add(prompt.getInstructions().stream().map(Message::getText).toList());
			ChatResponse response = new ChatResponse(
					List.of(new Generation(AssistantMessage.builder().content(ASSISTANT_TEXT).build())));
			return Flux.create(sink -> this.upstream.execute(() -> {
				sink.next(response);
				sink.complete();
			}));
		}

	}

}
