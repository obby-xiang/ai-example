package com.example.configmgr.ai.tenant;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.session.SessionPurgeDatabase;
import org.assertj.core.api.SoftAssertions;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨租户同 id 的三条"不共享"底线（租户任务 2 收尾组的最小单点）。
 *
 * <ol>
 * <li>{@code ai:cancel:<runId>}：两个身份各自生成同一个 runId 是合法的，取消旗标必须<b>各自一份</b>；</li>
 * <li>{@code ai:observed:<runId>}：结算观测位同理（跨身份共享会让别人的结算行被永久让位）；</li>
 * <li>{@code ai_feedback}：两个身份用同一个 sessionId，销毁 A 的会话<b>不得</b>删掉 B 的反馈行。</li>
 * </ol>
 *
 * <p>三条断言用软断言汇总：一次红跑就能看全<b>三个</b>面的串身份事实，而不是只看到第一条。
 */
class TenantCancelAndFeedbackIsolationTest {

	private static final TenantUser TENANT_A = new TenantUser(1L, 101L);

	private static final TenantUser TENANT_B = new TenantUser(2L, 201L);

	private static final String RUN_ID = "run-shared-id";

	private static final String SESSION_ID = "sess-shared";

	@Test
	void crossTenantSameRunIdCancelAndSameSessionIdFeedbackStayIsolated() {
		SoftAssertions softly = new SoftAssertions();
		assertCancelFlagIsNotShared(softly);
		assertObservationSlotIsNotShared(softly);
		assertFeedbackPurgeDoesNotCrossTenants(softly);
		softly.assertAll();
	}

	private void assertCancelFlagIsNotShared(SoftAssertions softly) {
		StringRedisTemplate redis = stringRedis();
		CancellationRegistry registry = new CancellationRegistry(redis, new AiProperties());

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A.tenantId(),
				TENANT_A.userId())) {
			registry.cancel(RUN_ID, "inst-a");
			softly.assertThat(registry.cancelState(RUN_ID)).as("A 自己发起的取消必须可见")
				.isEqualTo(CancellationRegistry.CancelState.CANCELLED);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B.tenantId(),
				TENANT_B.userId())) {
			softly.assertThat(registry.cancelState(RUN_ID)).as("B 不得看到 A 的取消旗标（同 runId 不是共享证明）")
				.isEqualTo(CancellationRegistry.CancelState.NOT_CANCELLED);
		}
	}

	private void assertObservationSlotIsNotShared(SoftAssertions softly) {
		SettlementObservationStore store = new SettlementObservationStore();
		boolean claimedByA;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A.tenantId(),
				TENANT_A.userId())) {
			claimedByA = store.claim(RUN_ID, "call-1");
		}
		boolean claimedByB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B.tenantId(),
				TENANT_B.userId())) {
			claimedByB = store.claim(RUN_ID, "call-1");
		}

		softly.assertThat(claimedByA).isTrue();
		softly.assertThat(claimedByB).as("B 的结算观测位不得被 A 占掉（同 runId + 同 toolCallId）").isTrue();
	}

	private void assertFeedbackPurgeDoesNotCrossTenants(SoftAssertions softly) {
		JdbcDataSource source = new JdbcDataSource();
		source.setURL("jdbc:h2:mem:tenant-feedback-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		source.setUser("sa");
		JdbcTemplate jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE TABLE ai_feedback (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
				+ "message_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, rating INT NOT NULL, "
				+ "tenant_id BIGINT NOT NULL DEFAULT 0, user_id BIGINT NOT NULL DEFAULT 0)");
		jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating, tenant_id, user_id) "
				+ "VALUES (?, ?, ?, ?, ?)", "m-a", SESSION_ID, 1, TENANT_A.tenantId(), TENANT_A.userId());
		jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating, tenant_id, user_id) "
				+ "VALUES (?, ?, ?, ?, ?)", "m-b", SESSION_ID, -1, TENANT_B.tenantId(), TENANT_B.userId());
		SessionPurgeDatabase database = new SessionPurgeDatabase(jdbc, new DataSourceTransactionManager(source));

		SessionPurgeDatabase.Deleted deleted = database.delete(SESSION_ID, TENANT_A.tenantId(), TENANT_A.userId());

		softly.assertThat(deleted.feedbackRows()).as("只删本身份的反馈行").isEqualTo(1);
		softly.assertThat(countOf(jdbc, TENANT_A)).as("A 的行已删").isZero();
		softly.assertThat(countOf(jdbc, TENANT_B)).as("B 的反馈行不得被跨租户同 sessionId 的销毁误删").isEqualTo(1);
	}

	private static int countOf(JdbcTemplate jdbc, TenantUser identity) {
		Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ai_feedback WHERE session_id = ? AND tenant_id = ? "
				+ "AND user_id = ?", Integer.class, SESSION_ID, identity.tenantId(), identity.userId());
		return rows == null ? 0 : rows;
	}

	@SuppressWarnings("unchecked")
	private static StringRedisTemplate stringRedis() {
		Map<String, String> values = new ConcurrentHashMap<>();
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(template.opsForValue()).thenReturn(ops);
		when(ops.get(any())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
		doAnswer(invocation -> {
			// 显式转型：{@code String.valueOf(<T>)}} 会按 char[] 重载解析，String 实参会 CCE
			values.put((String) invocation.getArgument(0), (String) invocation.getArgument(1));
			return null;
		}).when(ops).set(anyString(), anyString(), any(Duration.class));
		return template;
	}

}
