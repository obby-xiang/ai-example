package com.example.configmgr.ai.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Objects;

/**
 * 会话销毁的 <b>H2 面</b>（{@code ai_feedback} / {@code ai_chat_message}）。
 *
 * <h2>为什么单独成类</h2>
 * 销毁流程的顺序是"先枚举、后 H2、再 Redis"（用户裁决：H2 先删）。H2 面需要的是
 * {@link JdbcTemplate} + 一个<b>短事务</b>，与 Redis 面（单键/多键删除）没有任何共同依赖；
 * 拆开后，服务编排的用例可以只关心"数据库这一步成功/失败"，不必为了断 H2 起一个 Spring 上下文。
 *
 * <h2>表存在性</h2>
 * {@code ai_chat_message} 是后加的表（完整对话历史底座）。本类按 {@link DatabaseMetaData}
 * 现查表是否存在：<b>不存在 = 这一面没有数据可删</b>（返回 0，不报错），这样在"历史表尚未
 * 上线/已回滚"的部署里销毁会话仍然可用，而不是让整个端点因一张可选表 500。
 * {@code ai_feedback} 是会话反馈的既有表，缺失视为 schema 异常（如实抛出，销毁中止，
 * 不进入 Redis 删除）。
 *
 * <h2>原子性</h2>
 * 两张表的 DELETE 放在<b>同一个本地事务</b>里（{@link TransactionTemplate}）：任一条失败则整体
 * 回滚，调用方据此在"还没碰 Redis"的阶段中止 —— 不会出现"反馈删了、历史没删"。这不是分布式事务：
 * Redis 面本来就不参与该事务，顺序由服务显式保证。
 */
@Slf4j
@Component
public class SessionPurgeDatabase {

	/** 完整对话历史表（可选：表不存在 = 0 行）。 */
	static final String CHAT_MESSAGE_TABLE = "ai_chat_message";

	/** 赞踩反馈表（既有表）。 */
	static final String FEEDBACK_TABLE = "ai_feedback";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	public SessionPurgeDatabase(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager,
				"会话销毁的 H2 面需要事务管理器（两张表 DELETE 必须同一事务）"));
		// 独立本地事务：销毁不是调用方业务事务的一部分（HTTP 端点本身无事务）。
		// 外层事务回滚不得把"已删的反馈/历史"回滚回来 —— 否则会出现 Redis 已删、H2 又复活的
		// 反方向半删。只隔离 H2 两表，不参与任何分布式事务。
		this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 只读枚举（销毁前必须成功的一步）。
	 *
	 * @param chatTablePresent 该部署是否有 {@code ai_chat_message}
	 * @param feedbackRows     该会话的反馈行数
	 * @param chatMessageRows  该会话的历史消息行数（表不存在 = 0）
	 */
	public record Snapshot(boolean chatTablePresent, int feedbackRows, int chatMessageRows) {
	}

	/** 实际删除结果（行数 = DB 真实影响行，不是枚举时的估算）。 */
	public record Deleted(int feedbackRows, int chatMessageRows) {
	}

	/** 枚举该会话在 H2 面有多少数据（表不存在按 0；读失败抛出，调用方不得继续删 Redis）。 */
	public Snapshot snapshot(String sessionId) {
		boolean chatTable = tableExists(CHAT_MESSAGE_TABLE);
		int feedback = count(FEEDBACK_TABLE, sessionId);
		int chat = chatTable ? count(CHAT_MESSAGE_TABLE, sessionId) : 0;
		return new Snapshot(chatTable, feedback, chat);
	}

	/**
	 * 删除该会话的两张表数据（同一本地事务）。
	 *
	 * @param snapshot 枚举结果（决定是否对可选表发 DELETE）
	 */
	public Deleted delete(String sessionId, Snapshot snapshot) {
		Deleted result = this.transaction.execute(status -> {
			int feedback = this.jdbc.update("DELETE FROM " + FEEDBACK_TABLE + " WHERE session_id = ?", sessionId);
			int chat = snapshot != null && snapshot.chatTablePresent()
					? this.jdbc.update("DELETE FROM " + CHAT_MESSAGE_TABLE + " WHERE session_id = ?", sessionId)
					: 0;
			return new Deleted(feedback, chat);
		});
		return result == null ? new Deleted(0, 0) : result;
	}

	private int count(String table, String sessionId) {
		Long rows = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Long.class, sessionId);
		return rows == null ? 0 : rows.intValue();
	}

	/** 表是否存在（大小写不敏感；元数据里 schema/catalog 的差异不影响判定）。 */
	private boolean tableExists(String table) {
		Boolean exists = this.jdbc.execute((ConnectionCallback<Boolean>) connection -> {
			DatabaseMetaData meta = connection.getMetaData();
			try (ResultSet tables = meta.getTables(connection.getCatalog(), null, null,
					new String[] { "TABLE" })) {
				while (tables.next()) {
					String name = tables.getString("TABLE_NAME");
					if (name != null && table.equalsIgnoreCase(name)) {
						return Boolean.TRUE;
					}
				}
			}
			return Boolean.FALSE;
		});
		return Boolean.TRUE.equals(exists);
	}

}
