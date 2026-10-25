package com.example.configmgr.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 方言 SQL 的**契约**守卫（本机能确定的部分；真实 PG 执行留 CI）。
 *
 * <p>重点守 V6 的"约束是否已存在"判断：只按 {@code pg_constraint.conname} 全局查重是错的 ——
 * 约束名在 PG 里只保证<b>同表</b>唯一（不同 schema/不同表可以有同名约束）。他表的同名约束会让
 * {@code IF NOT EXISTS} 误判为"已存在"，本表的 FK 就被<b>静默漏建</b>（迁移成功、结构缺失，
 * 只有后续业务才会以奇怪的方式炸）。正确判据必须把 schema 与表一起限定：
 * {@code conrelid = format('%I.%I', current_schema(), r.child_table)::regclass}（或等价 join）。
 *
 * <p>这是纯文件级断言（不连库）：真正的"同名约束不干扰建表"行为只能在真实 PG 上验证，见交付报告的
 * CI 未验证项。FK 名称集合 / 级联语义 / 预检不改数据仍由 {@link MigrationSetGuardTest} 守着。
 */
class PostgresMigrationSqlContractTest {

	private static final Path PG_DIR = Path.of("src/main/resources/db/postgresql");

	@Test
	void v6ScopesConstraintExistenceToCurrentSchemaAndTable() throws IOException {
		String sql = stripComments(Files.readString(PG_DIR.resolve("V6__task_child_fk.sql"),
				StandardCharsets.UTF_8));

		String lookup = between(sql, "FROM pg_constraint", "THEN");

		assertThat(lookup).as("必须按约束所在表限定（conrelid），不能只按 conname 全局判重")
				.contains("conrelid");
		assertThat(lookup).as("schema 必须显式限定（current_schema()），否则同名约束会让他表命中")
				.contains("current_schema()");
		assertThat(lookup).as("表 oid 由 schema + 表名解析（regclass）").contains("regclass");
		assertThat(lookup).as("被限定的约束名仍取自循环变量").contains("conname = r.fk_name");
		assertThat(lookup).as("限定 schema 与表名要拼成合法的 regclass 字面量")
				.contains("format('%I.%I'");
	}

	// ---------- helpers ----------

	private static String stripComments(String sql) {
		return sql.lines()
				.filter(line -> !line.trim().startsWith("--"))
				.reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
				.toString();
	}

	private static String between(String sql, String from, String to) {
		int start = sql.indexOf(from);
		int end = sql.indexOf(to, start < 0 ? 0 : start);
		assertThat(start).as("SQL 中应存在片段：%s", from).isNotNegative();
		assertThat(end).as("SQL 中 %s 之后应存在：%s", from, to).isGreaterThan(start);
		return sql.substring(start, end);
	}

}
