package com.example.configmgr.ai.session;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话销毁的 H2 面：只删 {@code ai_feedback} / {@code ai_chat_message} 中该会话的行。
 *
 * <p>证红：把 DELETE 的 {@code WHERE session_id = ?} 去掉 ⇒ A/B 用例立刻红；把可选表检查去掉
 * ⇒ "表不存在"用例红（SQL 直接失败）。
 */
class SessionPurgeDatabaseTest {

	private JdbcTemplate jdbc;

	private SessionPurgeDatabase database;

	private void setUp(boolean withChatTable) {
		JdbcDataSource source = new JdbcDataSource();
		source.setURL("jdbc:h2:mem:purge-db-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		source.setUser("sa");
		this.jdbc = new JdbcTemplate(source);
		this.jdbc.execute("CREATE TABLE ai_feedback (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
				+ "message_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, rating INT NOT NULL)");
		if (withChatTable) {
			this.jdbc.execute("CREATE TABLE ai_chat_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "session_id VARCHAR(128) NOT NULL, run_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL)");
		}
		this.database = new SessionPurgeDatabase(this.jdbc, new DataSourceTransactionManager(source));
	}

	@Test
	void deletesOnlyTheRequestedSessionFromBothTables() {
		setUp(true);
		insertFeedback("m-a1", "sess-a");
		insertFeedback("m-a2", "sess-a");
		insertFeedback("m-b1", "sess-b");
		insertChat("sess-a", "run-a", 1L);
		insertChat("sess-a", "run-a", 2L);
		insertChat("sess-b", "run-b", 1L);

		SessionPurgeDatabase.Snapshot snapshot = this.database.snapshot("sess-a");
		assertThat(snapshot.chatTablePresent()).isTrue();
		assertThat(snapshot.feedbackRows()).isEqualTo(2);
		assertThat(snapshot.chatMessageRows()).isEqualTo(2);

		SessionPurgeDatabase.Deleted deleted = this.database.delete("sess-a", snapshot);

		assertThat(deleted.feedbackRows()).isEqualTo(2);
		assertThat(deleted.chatMessageRows()).isEqualTo(2);
		assertThat(count("ai_feedback", "sess-a")).isZero();
		assertThat(count("ai_chat_message", "sess-a")).isZero();
		assertThat(count("ai_feedback", "sess-b")).as("B 会话的反馈不得被删").isEqualTo(1);
		assertThat(count("ai_chat_message", "sess-b")).as("B 会话的历史不得被删").isEqualTo(1);
	}

	@Test
	void missingChatTableIsTreatedAsZeroRowsNotAnError() {
		setUp(false);
		insertFeedback("m-a1", "sess-a");

		SessionPurgeDatabase.Snapshot snapshot = this.database.snapshot("sess-a");
		assertThat(snapshot.chatTablePresent()).isFalse();
		assertThat(snapshot.chatMessageRows()).isZero();

		SessionPurgeDatabase.Deleted deleted = this.database.delete("sess-a", snapshot);

		assertThat(deleted.feedbackRows()).isEqualTo(1);
		assertThat(deleted.chatMessageRows()).isZero();
		assertThat(count("ai_feedback", "sess-a")).isZero();
	}

	@Test
	void repeatDeleteIsIdempotent() {
		setUp(true);
		insertFeedback("m-a1", "sess-a");
		insertChat("sess-a", "run-a", 1L);

		SessionPurgeDatabase.Snapshot snapshot = this.database.snapshot("sess-a");
		assertThat(this.database.delete("sess-a", snapshot).feedbackRows()).isEqualTo(1);
		assertThat(this.database.delete("sess-a", snapshot).feedbackRows()).as("重复删除影响 0 行").isZero();
		assertThat(this.database.delete("sess-a", snapshot).chatMessageRows()).isZero();
	}

	private void insertFeedback(String messageId, String sessionId) {
		this.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating) VALUES (?, ?, ?)", messageId,
				sessionId, 1);
	}

	private void insertChat(String sessionId, String runId, long seq) {
		this.jdbc.update("INSERT INTO ai_chat_message (session_id, run_id, seq) VALUES (?, ?, ?)", sessionId, runId,
				seq);
	}

	private int count(String table, String sessionId) {
		Integer rows = this.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?",
				Integer.class, sessionId);
		return rows == null ? 0 : rows;
	}

}
