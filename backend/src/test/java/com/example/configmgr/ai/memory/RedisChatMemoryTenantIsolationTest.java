package com.example.configmgr.ai.memory;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code chat:mem} 的身份隔离（租户任务2 核心组）。
 *
 * <h2>被钉住的缺陷</h2>
 * 记忆键原先是 {@code chat:mem:{<conversationId>}}：<b>没有身份</b>。两个租户（或同租户两个用户）
 * 用同一个 sessionId 是合法且必然的（sessionId 不是跨租户唯一性证明），于是后建的一方会
 * {@code LRANGE} 到别人的整窗历史 —— 这是最直接的跨租户内容泄露。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>数据键/staging 键/索引成员都带身份（{@code chat:mem:<tenantId>:<userId>:{<id>}}、
 *     成员 {@code tenantId/userId/<id>}）；</li>
 * <li><b>无归属 legacy 键（{@code chat:mem:<id>}）不得进入新身份读路径</b>：带身份时既不回落读、
 *     也不删（删就是替别人做决定），只按 TTL 自然消亡。</li>
 * </ul>
 */
class RedisChatMemoryTenantIsolationTest {

	private static final String CONVERSATION = "conv-shared-20261030";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private static final Duration TTL = Duration.ofHours(24);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			TTL);

	@Test
	void sameConversationIdInTwoTenantsNeverSharesMemoryKeys() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			this.repository.saveAll(CONVERSATION, List.of(new UserMessage("A 的历史")));
			assertThat(this.repository.keyFor(CONVERSATION))
				.as("记忆键必须带身份段")
				.isEqualTo("chat:mem:1:101:{" + CONVERSATION + "}");
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.repository.findByConversationId(CONVERSATION))
				.as("另一个租户的同名会话不得读到别人的历史")
				.isEmpty();
			assertThat(this.repository.hasLiveMemory(CONVERSATION)).isFalse();

			this.repository.saveAll(CONVERSATION, List.of(new UserMessage("B 的历史")));
			assertThat(texts(this.repository.findByConversationId(CONVERSATION))).containsExactly("B 的历史");

			this.repository.deleteByConversationId(CONVERSATION);
			assertThat(this.repository.hasLiveMemory(CONVERSATION)).isFalse();
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(texts(this.repository.findByConversationId(CONVERSATION)))
				.as("另一租户的删除不得删掉本例的历史")
				.containsExactly("A 的历史");
		}

		assertThat(this.fake.sets.get(RedisChatMemoryRepository.CONVERSATION_IDS_KEY))
			.as("全局会话索引成员必须带身份")
			.contains("1/101/" + CONVERSATION)
			.doesNotContain("2/202/" + CONVERSATION);
	}

	/**
	 * 无归属 legacy 键（旧版本写的 {@code chat:mem:<id>}）不得进入新身份读路径：读不到、判活为假，
	 * 也<b>不得</b>被新身份的 save/delete 顺手删掉（那是在替另一个身份做决定）。
	 */
	@Test
	void unownedLegacyMemoryKeyIsNotReadOrDeletedOnTheNewIdentityPath() {
		String legacyKey = "chat:mem:" + CONVERSATION;
		this.fake.lists.put(legacyKey, List.of(this.codec.serialize(new UserMessage("旧版本的历史"))));

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.repository.findByConversationId(CONVERSATION))
				.as("无归属 legacy 键不得在新身份路径读取")
				.isEmpty();
			assertThat(this.repository.hasLiveMemory(CONVERSATION)).isFalse();

			this.repository.saveAll(CONVERSATION, List.of(new UserMessage("新身份的历史")));
			this.repository.deleteByConversationId(CONVERSATION);
		}

		assertThat(this.fake.lists)
			.as("新身份路径不得删除/改写无归属 legacy 键（只按 TTL 自然消亡）")
			.containsKey(legacyKey);
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

}
