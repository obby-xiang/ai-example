package com.example.configmgr.ai.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTranscriptStoreRemoveTest {

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private LettuceConnectionFactory factory;

	private ConversationTranscriptStore store;

	private String sessionId;

	private TranscriptEntry plain;

	@BeforeEach
	void setUp() {
		this.factory = new LettuceConnectionFactory("127.0.0.1", 18779);
		this.factory.afterPropertiesSet();
		this.store = new ConversationTranscriptStore(new StringRedisTemplate(this.factory), new ObjectMapper(),
				Duration.ofHours(1));
		this.sessionId = "remove-" + UUID.randomUUID();
		this.plain = TranscriptEntries.assistant("run-remove", 0L, 1L, "被接管正文", null, null);
	}

	@AfterEach
	void tearDown() {
		if (this.factory != null) {
			this.factory.destroy();
		}
	}

	@Test
	void sameEpochRemoveBlocksResurrectionAndAllowsNewEpoch() {
		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 5L)).isTrue();
		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 5L)).isTrue();

		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 5L)).isFalse();
		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 5L)).isTrue();
		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 6L)).isTrue();
		assertThat(entryIds()).containsExactly(this.plain.entryId());
	}

	@Test
	void olderFenceCannotRemoveOrResurrectTheEntry() {
		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 5L)).isTrue();

		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 4L)).isFalse();
		assertThat(entryIds()).containsExactly(this.plain.entryId());
		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 5L)).isTrue();
		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 4L)).isFalse();
		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 4L)).isFalse();
	}

	@Test
	void repeatedRemoveIsIdempotentWithoutReopeningTheSameFence() {
		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 5L)).isTrue();
		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 5L)).isTrue();

		assertThat(this.store.remove(this.sessionId, TENANT, USER, List.of(this.plain.entryId()), 5L)).isTrue();

		assertThat(entryIds()).isEmpty();
		assertThat(this.store.append(this.sessionId, TENANT, USER, List.of(this.plain), 5L)).isFalse();
	}

	private List<String> entryIds() {
		return this.store.readPage(this.sessionId, TENANT, USER, null, 500).entries()
			.stream().map(TranscriptEntry::entryId).toList();
	}
}
