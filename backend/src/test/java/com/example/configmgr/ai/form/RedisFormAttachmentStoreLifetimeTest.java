package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 附件登记簿的<b>寿命</b>与<b>表单索引</b>（2026-10-29 修复批）。
 *
 * <h2>① TTL 与"无上限等待"不匹配</h2>
 * 旧口径是写死的 {@code 2h}，而 {@code generative_form} 自 2026-10-20 起<b>无等待上限</b>
 * （待决条目的 TTL 是 {@code app.ai.session-ttl}，默认 24h，挂起期还随心跳续期）。
 * 于是"等了两小时零一分"的表单会得到 {@code ATTACHMENT_NOT_FOUND}：登记先于卡失效，
 * 用户必须重传 —— 而失效的那件文件没人删，成了孤儿。
 * 现在登记 TTL 与会话/待决条目同取 {@code app.ai.session-ttl}（同一个旋钮），不再是两套尺子。
 *
 * <h2>② 表单索引（回收面 + 上传限额面）</h2>
 * {@code findByForm(runId, toolCallId)} 是"表单收口回收没提交的件"和"上传按表单限件数"的共同判据；
 * 没有索引就只能扫键空间（生产不可接受）。
 */
class RedisFormAttachmentStoreLifetimeTest {

	private static final String RUN_ID = "run-life";

	private static final String CALL_ID = "call-life";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void findByFormReturnsOnlyThisFormsRegistrations() {
		RedisFormAttachmentStore store = new RedisFormAttachmentStore(providerOf(null), this.mapper);
		FormAttachmentStore.Attachment mine = store.register(RUN_ID, CALL_ID, "sess", "a.pdf", 10L, "application/pdf",
				"/data/a.pdf");
		FormAttachmentStore.Attachment siblingCall = store.register(RUN_ID, "call-other", "sess", "b.pdf", 10L,
				"application/pdf", "/data/b.pdf");
		FormAttachmentStore.Attachment otherRun = store.register("run-other", CALL_ID, "sess", "c.pdf", 10L,
				"application/pdf", "/data/c.pdf");

		assertThat(store.findByForm(RUN_ID, CALL_ID)).extracting(FormAttachmentStore.Attachment::fileId)
			.containsExactly(mine.fileId());
		assertThat(store.findByForm(RUN_ID, CALL_ID)).extracting(FormAttachmentStore.Attachment::fileId)
			.doesNotContain(siblingCall.fileId(), otherRun.fileId());
	}

	@Test
	void registrationTtlFollowsTheSessionTtlKnob() {
		AiProperties properties = new AiProperties();
		assertThat(new RedisFormAttachmentStore(providerOf(null), this.mapper, properties).ttl())
			.as("默认与会话/待决条目同一把尺（24h），不再是写死的 2h")
			.isEqualTo(Duration.ofHours(24));

		properties.setSessionTtl(Duration.ofHours(6));
		assertThat(new RedisFormAttachmentStore(providerOf(null), this.mapper, properties).ttl())
			.as("旋钮改了，登记寿命跟着改（两处不再各写一份常量）")
			.isEqualTo(Duration.ofHours(6));
	}

	private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(template);
		return provider;
	}

}
