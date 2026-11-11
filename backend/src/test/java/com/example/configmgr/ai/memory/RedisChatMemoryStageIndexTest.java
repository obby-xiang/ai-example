package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE_WRITE / STAGING_COMMIT / STAGING_DISCARD 的<b>索引面</b>契约：
 * {@code SADD staging token + PEXPIRE 两键} 与提交/丢弃时的 {@code SREM} 必须真实建模，
 * 且 SADD / PEXPIRE 失败按真实 Lua 写序<b>不回滚</b>已写内容，由 finally 的 discard 清理。
 */
class RedisChatMemoryStageIndexTest {

	private static final String CID = "s-stage-index";

	private static final Duration TTL = Duration.ofHours(6);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis,
			new MessageJsonCodec(new ObjectMapper()), TTL);

	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void bindIdentity() {
		this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void releaseIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void stageWriteAddsIndexTokenAndCommitRemovesIt() {
		this.repository.saveAll(CID, List.of(new UserMessage("one")));

		assertEquals(List.of("one"), texts());
		assertEquals(1, this.fake.indexAdds.get(), "STAGE_WRITE 必须 SADD staging token");
		assertEquals(1, this.fake.indexRemoves.get(), "STAGING_COMMIT 必须 SREM staging token");
		assertFalse(this.fake.sets.containsKey(RedisChatMemoryRepository.stagingIndexKey(CID)),
				"提交后 staging 索引为空必须删除");
	}

	@Test
	void saddFailureKeepsOldHistoryAndDiscardCleansStaging() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failIndexAdd = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(), "SADD 失败不得丢旧历史");
		assertEquals(0, this.fake.indexAdds.get(), "SADD 失败时 token 不得进入索引");
		assertEquals(1, this.fake.indexRemoves.get(), "finally 的 STAGING_DISCARD 必须 SREM 一次");
		assertFalse(this.fake.sets.containsKey(RedisChatMemoryRepository.stagingIndexKey(CID)),
				"失败路径不得留下 staging 索引");
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "失败路径不得留下 staging 数据键");

		this.fake.failIndexAdd = false;
		this.repository.saveAll(CID, List.of(new UserMessage("新")));
		assertEquals(List.of("新"), texts(), "故障恢复后照常覆盖");
	}

	@Test
	void ttlFailureDoesNotRollBackIndexTokenAndDiscardCleansIt() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failExpire = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(), "PEXPIRE 失败不得丢旧历史");
		assertEquals(1, this.fake.indexAdds.get(), "PEXPIRE 失败发生在 RPUSH/SADD 之后（真实 Lua 不回滚）");
		assertEquals(1, this.fake.indexRemoves.get(), "finally 的 STAGING_DISCARD 必须清掉 staging token");
		assertFalse(this.fake.sets.containsKey(RedisChatMemoryRepository.stagingIndexKey(CID)),
				"失败路径不得留下 staging 索引");
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "失败路径不得留下 staging 数据键");
	}

	private List<String> texts() {
		return this.repository.findByConversationId(CID).stream().map(Message::getText).toList();
	}

}
