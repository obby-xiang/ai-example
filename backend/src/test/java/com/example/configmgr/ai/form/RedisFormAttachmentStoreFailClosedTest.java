package com.example.configmgr.ai.form;

import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3：附件登记簿在多实例/Cluster 下必须 fail-closed。
 *
 * <p>旧实现把"写 Redis 失败"降级成"落进程内 Map"：上传发生在 A、回灌发生在 B 时，B 看不到
 * A 的登记 —— 表现是"附件不存在/未消费"判定失真，而日志只写"降级进程内登记"，把数据面丢失
 * 伪装成性能降级。现在：Redis 模板存在 ⇒ Redis 是唯一事实源；写失败就直接告知上传失败。
 *
 * <p>只有<b>模板本身未装配</b>（单测/无 Redis 的轻装配面）才保留进程内 Map。
 */
@SuppressWarnings("unchecked")
class RedisFormAttachmentStoreFailClosedTest {

	private static final String RUN_ID = "run-att";

	private static final String CALL_ID = "call-att";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void registerWriteFailureFailsClosedInsteadOfFallingBackToTheProcessMap() {
		long before = RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_REGISTER);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		doThrow(new IllegalStateException("模拟 Redis 写失败"))
			.when(values).set(anyString(), anyString(), any(Duration.class));
		RedisFormAttachmentStore store = new RedisFormAttachmentStore(providerOf(redis), this.mapper);

		assertThatThrownBy(() -> store.register(RUN_ID, CALL_ID, "sess", "a.pdf", 10L, "application/pdf",
				"/data/a.pdf"))
			.as("多实例下登记失败不得静默写进程内 Map")
			.isInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining("附件登记");
		assertThat(store.snapshot()).as("失败后进程内不得留下半条登记").isEmpty();
		assertThat(RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_REGISTER)).isGreaterThan(before);
	}

	@Test
	void registerFallsBackToTheProcessMapOnlyWhenTheTemplateIsAbsent() {
		RedisFormAttachmentStore store = new RedisFormAttachmentStore(providerOf(null), this.mapper);

		FormAttachmentStore.Attachment attachment = store.register(RUN_ID, CALL_ID, "sess", "a.pdf", 10L,
				"application/pdf", "/data/a.pdf");

		assertThat(store.find(attachment.fileId())).isEqualTo(attachment);
	}

	@Test
	void readFailureAlsoFailsClosedWhenRedisIsTheConfiguredBackend() {
		long before = RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_READ);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenThrow(new IllegalStateException("模拟 Redis 读失败"));
		RedisFormAttachmentStore store = new RedisFormAttachmentStore(providerOf(redis), this.mapper);

		assertThatThrownBy(() -> store.find("att_missing"))
			.as("读不出来 ≠ 不存在；多实例下必须让调用方知道无法校验")
			.isInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining("附件读取");
		assertThat(RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_READ)).isGreaterThan(before);
	}

	@Test
	void consumeAndTombstoneFailuresAreCountedAndFailClosed() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.delete(anyString())).thenThrow(new IllegalStateException("模拟 delete 失败"));
		when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("模拟 hasKey 失败"));
		RedisFormAttachmentStore store = new RedisFormAttachmentStore(providerOf(redis), this.mapper);
		long consumeBefore = RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_CONSUME);
		long tombstoneBefore = RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_TOMBSTONE);

		assertThatThrownBy(() -> store.consume("att_x"))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThatThrownBy(() -> store.wasConsumed("att_x"))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_CONSUME)).isGreaterThan(consumeBefore);
		assertThat(RedisDegradation.count(RedisDegradation.B3_ATTACHMENT_TOMBSTONE)).isGreaterThan(tombstoneBefore);
	}

	/** 索引写失败（SADD）同样 fail-closed：数据键必须回滚，否则留下一条谁也枚举不到的登记。 */
	@Test
	void indexWriteFailureRollsBackTheRegistrationInsteadOfLeavingAnUnlistableEntry() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		SetOperations<String, String> sets = mock(SetOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(redis.opsForSet()).thenReturn(sets);
		when(sets.add(anyString(), any(String[].class))).thenThrow(new IllegalStateException("模拟 SADD 失败"));
		RedisFormAttachmentStore store = new RedisFormAttachmentStore(providerOf(redis), this.mapper);

		assertThatThrownBy(() -> store.register(RUN_ID, CALL_ID, "sess", "a.pdf", 10L, "application/pdf",
				"/data/a.pdf"))
			.isInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining("索引");

		verify(redis, atLeastOnce()).delete(startsWith("ai:form:att:"));
	}

	private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(template);
		return provider;
	}

}
