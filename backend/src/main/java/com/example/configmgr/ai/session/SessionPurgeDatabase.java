package com.example.configmgr.ai.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 会话销毁的 <b>H2 面</b>（{@code ai_feedback}）。
 *
 * <h2>为什么单独成类</h2>
 * 销毁流程的顺序是"先枚举、后 H2、再 Redis"（用户裁决：H2 先删）。H2 面需要的是
 * {@link JdbcTemplate} + 一个<b>短事务</b>，与 Redis 面（单键/多键删除）没有任何共同依赖；
 * 拆开后，服务编排的用例可以只关心"数据库这一步成功/失败"，不必为了断 H2 起一个 Spring 上下文。
 *
 * <h2>删除面</h2>
 * 只删 {@code ai_feedback} 里该会话的行（赞踩反馈）。对话正文已不落业务数据库
 * （数据库完整历史链路已移除），本类因此不再枚举或删除任何历史表。
 * 反馈表是既有表，缺失视为 schema 异常（如实抛出，销毁中止，不进入 Redis 删除）。
 *
 * <h2>原子性</h2>
 * DELETE 在<b>独立本地事务</b>（{@link TransactionTemplate}）里执行：外层业务事务回滚不得把
 * "已删的反馈"回滚回来 —— 否则会出现 Redis 已删、H2 又复活的反方向半删。这不是分布式事务：
 * Redis 面本来就不参与该事务，顺序由服务显式保证。
 */
@Slf4j
@Component
public class SessionPurgeDatabase {

	/** 赞踩反馈表（既有表）。 */
	static final String FEEDBACK_TABLE = "ai_feedback";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	public SessionPurgeDatabase(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager,
				"会话销毁的 H2 面需要事务管理器（DELETE 必须与调用方事务隔离）"));
		// 独立本地事务：销毁不是调用方业务事务的一部分（HTTP 端点本身无事务）。
		// 外层事务回滚不得把"已删的反馈"回滚回来 —— 否则会出现 Redis 已删、H2 又复活的
		// 反方向半删。只隔离 H2 删除，不参与任何分布式事务。
		this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 只读枚举（销毁前必须成功的一步）。
	 *
	 * @param feedbackRows 该会话的反馈行数
	 */
	public record Snapshot(int feedbackRows) {
	}

	/** 实际删除结果（行数 = DB 真实影响行，不是枚举时的估算）。 */
	public record Deleted(int feedbackRows) {
	}

	/** 枚举该会话在 H2 面有多少数据（读失败抛出，调用方不得继续删 Redis）。 */
	public Snapshot snapshot(String sessionId) {
		return new Snapshot(count(FEEDBACK_TABLE, sessionId));
	}

	/** 删除该会话的反馈行（独立本地事务）。 */
	public Deleted delete(String sessionId) {
		Deleted result = this.transaction
			.execute(status -> new Deleted(this.jdbc.update("DELETE FROM " + FEEDBACK_TABLE + " WHERE session_id = ?",
					sessionId)));
		return result == null ? new Deleted(0) : result;
	}

	private int count(String table, String sessionId) {
		Long rows = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Long.class, sessionId);
		return rows == null ? 0 : rows.intValue();
	}

}
