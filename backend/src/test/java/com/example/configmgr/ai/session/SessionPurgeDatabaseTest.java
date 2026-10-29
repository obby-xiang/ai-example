package com.example.configmgr.ai.session;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话销毁的 H2 面：只删 {@code ai_feedback} 中<b>该身份 + 该会话</b>的行（V11 三元组）。
 *
 * <p>数据库完整历史链路已移除：本类不再枚举、也不再删除任何历史表 —— 同名遗留表即使还在
 * （迁移删表之前的老库），销毁会话也<b>不碰</b>它（用例 3 钉住）。
 *
 * <p>证红：把 DELETE 的 {@code WHERE session_id = ? AND tenant_id = ? AND user_id = ?} 去掉
 * 身份条件 ⇒ A/B 用例与 {@link #scopedSnapshotAndDeleteOnlyTouchTheRequestedIdentity()} 立刻红。
 */
class SessionPurgeDatabaseTest {

	private JdbcTemplate jdbc;

	private SessionPurgeDatabase database;

	private void setUp() {
		JdbcDataSource source = new JdbcDataSource();
		source.setURL("jdbc:h2:mem:purge-db-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		source.setUser("sa");
		this.jdbc = new JdbcTemplate(source);
		this.jdbc.execute("CREATE TABLE ai_feedback (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
				+ "message_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, rating INT NOT NULL, "
				+ "tenant_id BIGINT NOT NULL DEFAULT 0, user_id BIGINT NOT NULL DEFAULT 0)");
		this.database = new SessionPurgeDatabase(this.jdbc, new DataSourceTransactionManager(source));
	}

	@Test
	void deletesOnlyTheRequestedSessionFeedback() {
		setUp();
		insertFeedback("m-a1", "sess-a");
		insertFeedback("m-a2", "sess-a");
		insertFeedback("m-b1", "sess-b");

		SessionPurgeDatabase.Snapshot snapshot = this.database.snapshot("sess-a");
		assertThat(snapshot.feedbackRows()).isEqualTo(2);

		SessionPurgeDatabase.Deleted deleted = this.database.delete("sess-a");

		assertThat(deleted.feedbackRows()).isEqualTo(2);
		assertThat(count("ai_feedback", "sess-a")).isZero();
		assertThat(count("ai_feedback", "sess-b")).as("B 会话的反馈不得被删").isEqualTo(1);
	}

	@Test
	void repeatDeleteIsIdempotent() {
		setUp();
		insertFeedback("m-a1", "sess-a");

		assertThat(this.database.snapshot("sess-a").feedbackRows()).isEqualTo(1);
		assertThat(this.database.delete("sess-a").feedbackRows()).isEqualTo(1);
		assertThat(this.database.delete("sess-a").feedbackRows()).as("重复删除影响 0 行").isZero();
	}

	/**
	 * 遗留历史表（老库尚未执行删表迁移）不得被销毁流程碰：应用侧已无该表的读/写/删交互。
	 */
	@Test
	void legacyChatMessageTableIsLeftUntouched() {
		setUp();
		this.jdbc.execute("CREATE TABLE ai_chat_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
				+ "session_id VARCHAR(128) NOT NULL, run_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL)");
		this.jdbc.update("INSERT INTO ai_chat_message (session_id, run_id, seq) VALUES (?, ?, ?)", "sess-a",
				"run-a", 1L);
		insertFeedback("m-a1", "sess-a");

		SessionPurgeDatabase.Snapshot snapshot = this.database.snapshot("sess-a");
		SessionPurgeDatabase.Deleted deleted = this.database.delete("sess-a");

		assertThat(snapshot.feedbackRows()).isEqualTo(1);
		assertThat(deleted.feedbackRows()).isEqualTo(1);
		assertThat(count("ai_feedback", "sess-a")).isZero();
		assertThat(count("ai_chat_message", "sess-a")).as("遗留历史表不在销毁面：行必须原样保留").isEqualTo(1);
	}

	/**
	 * <b>身份隔离（V11）</b>：三元组删除只碰本身份；跨租户同 sessionId 的行既不计入枚举、也不会被删。
	 *
	 * <p>证红：把 {@code delete(sessionId, tenantId, userId)} 的 tenant/user 条件去掉 ⇒ 本用例立刻红
	 * （B 的行被 A 的删除带走，且枚举把别人的行算成自己的）。
	 */
	@Test
	void scopedSnapshotAndDeleteOnlyTouchTheRequestedIdentity() {
		setUp();
		insertScopedFeedback("m-a1", "sess-a", 1L, 101L);
		insertScopedFeedback("m-a2", "sess-a", 1L, 101L);
		insertScopedFeedback("m-b1", "sess-a", 2L, 201L);
		insertScopedFeedback("m-b2", "sess-same-user-other-tenant", 2L, 101L);

		assertThat(this.database.snapshot("sess-a", 1L, 101L).feedbackRows()).as("只数本身份的行").isEqualTo(2);
		assertThat(this.database.snapshot("sess-a", 2L, 201L).feedbackRows()).isEqualTo(1);

		assertThat(this.database.delete("sess-a", 1L, 101L).feedbackRows()).isEqualTo(2);

		assertThat(countScoped("sess-a", 1L, 101L)).as("本身份的行已删").isZero();
		assertThat(countScoped("sess-a", 2L, 201L)).as("跨租户同 sessionId 的行不得被误删").isEqualTo(1);
		assertThat(countScoped("sess-same-user-other-tenant", 2L, 101L))
			.as("同 userId、不同租户的行同样不在删除面")
			.isEqualTo(1);
	}

	/**
	 * <b>无身份 = legacy 命名空间</b>：旧调用（无身份上下文）只能看到/删除 {@code (0,0)} 的行，
	 * 绝不落到任何真实租户的反馈上（不猜默认身份）。
	 */
	@Test
	void legacyOverloadsTouchOnlyTheUnattributedNamingSpace() {
		setUp();
		insertFeedback("m-legacy", "sess-a");
		insertScopedFeedback("m-real", "sess-a", 1L, 101L);

		assertThat(this.database.snapshot("sess-a").feedbackRows()).as("只数无归属行").isEqualTo(1);
		assertThat(this.database.delete("sess-a").feedbackRows()).isEqualTo(1);

		assertThat(count("ai_feedback", "sess-a")).as("真实租户的行必须原样保留").isEqualTo(1);
		assertThat(countScoped("sess-a", 1L, 101L)).isEqualTo(1);
	}

	private void insertFeedback(String messageId, String sessionId) {
		this.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating) VALUES (?, ?, ?)", messageId,
				sessionId, 1);
	}

	private void insertScopedFeedback(String messageId, String sessionId, long tenantId, long userId) {
		this.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating, tenant_id, user_id) "
				+ "VALUES (?, ?, ?, ?, ?)", messageId, sessionId, 1, tenantId, userId);
	}

	private int countScoped(String sessionId, long tenantId, long userId) {
		Integer rows = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM ai_feedback WHERE session_id = ? AND tenant_id = ? AND user_id = ?",
				Integer.class, sessionId, tenantId, userId);
		return rows == null ? 0 : rows;
	}

	private int count(String table, String sessionId) {
		Integer rows = this.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?",
				Integer.class, sessionId);
		return rows == null ? 0 : rows;
	}

}
