package com.example.configmgr.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * {@code db} 维度：一次 {@code SELECT 1}。
 *
 * <p>为什么不只查池统计：池里有空闲连接 ≠ 库可写（库被停、网络黑洞、只读副本切换都可能
 * 让空闲连接仍"存在"）。一次真实往返是唯一能证明"现在这条链路通"的判据。
 *
 * <p>超时：查询 2s（{@code setQueryTimeout}）。取连接的上界由 Hikari {@code connection-timeout}
 * 决定（application-postgresql.yml 收到 5s）—— 探针必须在 LB 探测超时内给结论。
 * 不缓存结果：LB 要的是"此刻"。
 *
 * <p>失败诊断只用异常类名 + 消息（**不再**为拼诊断去另开一次连接：那会在失败路径上再等一轮
 * connection-timeout）。消息里可能出现主机/端口，但不会出现口令 —— JDBC 驱动的异常不带密码。
 */
@Component
class DbReadinessProbe implements ReadinessProbe {

	static final String NAME = "db";

	private static final int QUERY_TIMEOUT_SECONDS = 2;

	private final JdbcTemplate jdbcTemplate;

	DbReadinessProbe(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProbeResult check() {
		long startedAt = System.nanoTime();
		try {
			Integer one = this.jdbcTemplate.queryForObject("SELECT 1", Integer.class);
			if (one == null || one != 1) {
				return ProbeResult.down("SELECT 1 返回 " + one);
			}
			return ProbeResult.up("SELECT 1 正常（" + elapsedMs(startedAt) + "ms）");
		}
		catch (RuntimeException ex) {
			return ProbeResult.down(ex.getClass().getSimpleName()
					+ (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
		}
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000L;
	}

}
