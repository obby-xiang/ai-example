package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 撞号保护：新号落在<b>已存在的会话键</b>上时，{@code ensure} 只能报告 existed 并续期 TTL，
 * <b>不得重写会话记录</b>（旧会话的归属与历史不能被另一个号覆盖）。
 *
 * <p>发号方（{@code POST /api/ai/session}）据 existed 换号重发；若第二次仍撞号则返回 503，
 * 而不是"登记成功"——静默覆盖等于把两个会话合并成一个。
 */
class AiSessionCollisionProtectionTest {

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private static final String SESSION = "1700000000000007000";

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final AiSessionService sessions = new AiSessionService(this.fake.redis, new ObjectMapper(),
			new AiProperties());

	@Test
	void ensureDoesNotOverwriteAnExistingSessionRecord() {
		String key = AiSessionService.keyFor(SESSION, TENANT, USER);
		String existing = "{\"sessionId\":\"" + SESSION
				+ "\",\"lastActivity\":\"2026-10-29T00:00:00Z\",\"rounds\":7,\"tenantId\":1,\"userId\":101}";
		this.fake.strings.put(key, existing);
		this.fake.ttls.put(key, Duration.ofMinutes(1));

		boolean existed = this.sessions.ensure(SESSION, TENANT, USER);

		assertThat(existed).as("已存在的号必须报告 existed，发号方据此换号").isTrue();
		assertThat(this.fake.strings).as("撞号不得重写旧会话记录（归属/历史保持原样）").containsEntry(key, existing);
		assertThat(this.fake.ttls.get(key)).as("续期只刷新 TTL").isEqualTo(new AiProperties().getSessionTtl());
	}

}
