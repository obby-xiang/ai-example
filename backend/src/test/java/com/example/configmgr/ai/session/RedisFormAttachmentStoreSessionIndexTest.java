package com.example.configmgr.ai.session;

import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.form.RedisFormAttachmentStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 附件登记簿的会话索引生命周期：登记入索引、消费/销毁出索引。
 *
 * <p>没有这个索引，会话销毁只能扫全键空间才能找到"这个会话上传过哪些附件" —— 既慢又会误伤。
 */
class RedisFormAttachmentStoreSessionIndexTest {

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	@Test
	void registerAddsToSessionIndexAndConsumeRemovesIt() {
		RedisFormAttachmentStore store = store();

		FormAttachmentStore.Attachment a = store.register("run-a", "call-a", "sess-a", "a.pdf", 10L,
				"application/pdf", "/tmp/a.pdf");
		FormAttachmentStore.Attachment b = store.register("run-b", "call-b", "sess-b", "b.pdf", 20L,
				"application/pdf", "/tmp/b.pdf");

		assertThat(store.findBySessionId("sess-a")).containsExactly(a);
		assertThat(store.findBySessionId("sess-b")).containsExactly(b);

		assertThat(store.consume(a.fileId())).isTrue();
		assertThat(store.findBySessionId("sess-a")).as("消费后不再属于该会话的待销毁面").isEmpty();
		assertThat(store.findBySessionId("sess-b")).as("别的会话不受影响").containsExactly(b);

		assertThat(store.deleteRegistration(b.fileId())).isEqualTo(b);
		assertThat(store.findBySessionId("sess-b")).isEmpty();
		assertThat(store.find(b.fileId())).isNull();
	}

	@Test
	void blankSessionIdIsNotIndexedAndEnumerationIsEmpty() {
		RedisFormAttachmentStore store = store();

		FormAttachmentStore.Attachment attachment = store.register("run-a", "call-a", null, "a.pdf", 10L,
				"application/pdf", "/tmp/a.pdf");

		assertThat(store.findBySessionId("")).isEmpty();
		assertThat(store.find(attachment.fileId())).isNotNull();
	}

	/**
	 * <b>跨租户相同 sessionId 的附件隔离</b>：登记、枚举、查询、删登记都不跨身份。
	 *
	 * <p>fileId 由服务端签发（全局唯一），但"删文件"的依据是登记里的 {@code storagePath} ——
	 * 只要别的身份<b>查不到这条登记</b>，就删不到那个文件。
	 */
	@Test
	void sameSessionIdInTwoIdentitiesDoesNotShareRegistrations() {
		RedisFormAttachmentStore store = store();
		FormAttachmentStore.Attachment a;
		FormAttachmentStore.Attachment b;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(1L, 101L)) {
			a = store.register("run-a", "call-a", "sess-dup", "a.pdf", 10L, "application/pdf", "/tmp/a.pdf");
			assertThat(store.findBySessionId("sess-dup")).containsExactly(a);
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(2L, 201L)) {
			b = store.register("run-b", "call-b", "sess-dup", "b.pdf", 20L, "application/pdf", "/tmp/b.pdf");
			assertThat(store.findBySessionId("sess-dup")).as("B 只看到自己的附件").containsExactly(b);
			assertThat(store.find(a.fileId())).as("A 的 fileId 在 B 身份下不可见").isNull();
			assertThat(store.consume(a.fileId())).as("不得跨身份消费 A 的附件").isFalse();
			assertThat(store.deleteRegistration(a.fileId())).as("不得跨身份删 A 的登记").isNull();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(1L, 101L)) {
			assertThat(store.find(a.fileId())).as("A 的登记原样保留").isEqualTo(a);
			assertThat(store.findBySessionId("sess-dup")).containsExactly(a);
		}
	}

	@SuppressWarnings("unchecked")
	private RedisFormAttachmentStore store() {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.fake.redis);
		return new RedisFormAttachmentStore(provider, new ObjectMapper());
	}

}
