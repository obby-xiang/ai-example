package com.example.configmgr.ai.session;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话销毁的 H2 面：只删 {@code ai_feedback} 中该会话的行。
 *
 * <p>数据库完整历史链路已移除：本类不再枚举、也不再删除任何历史表 —— 同名遗留表即使还在
 * （迁移删表之前的老库），销毁会话也<b>不碰</b>它（用例 3 钉住）。
 *
 * <p>证红：把 DELETE 的 {@code WHERE session_id = ?} 去掉 ⇒ A/B 用例立刻红。
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
				+ "message_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, rating INT NOT NULL)");
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

	private void insertFeedback(String messageId, String sessionId) {
		this.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating) VALUES (?, ?, ?)", messageId,
				sessionId, 1);
	}

	private int count(String table, String sessionId) {
		Integer rows = this.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?",
				Integer.class, sessionId);
		return rows == null ? 0 : rows;
	}

}
