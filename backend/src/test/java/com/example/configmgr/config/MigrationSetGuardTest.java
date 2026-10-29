package com.example.configmgr.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移集的**静态**守卫（多实例专项 · PostgreSQL 生产 profile + 完整历史表）。
 *
 * <h2>守什么</h2>
 * <ol>
 * <li><b>H2 迁移 V1~V6 的字节指纹不变</b>：这些迁移已经发布过，Flyway 的 checksum 存在既有库的
 * {@code flyway_schema_history} 里；改一个空格都会让存量库在启动时报
 * "Migration checksum mismatch"（且不允许自动修复）。指纹钉死后，任何"顺手改一下 V1"都会在单测里先红。</li>
 * <li><b>两目录版本号对齐且无同号冲突</b>：{@code db/migration}（H2：V1~V5 SQL + V6 Java + V7~V10 SQL）
 * 与 {@code db/postgresql}（V1~V10 全 SQL）合起来必须**恰好覆盖 1~10、每个版本号只出现一次**。
 * 跨分支合并（历史工作树的 V7/V8 并入本树的 V7 app_lock 之后）最容易在这里撞号，
 * 撞号会让 Flyway 在启动期直接失败。</li>
 * <li><b>方言差异只允许出现在 V1/V8</b>（CLOB→TEXT），其余文件两侧逐字节相同；
 * V6 在 PG 侧是纯 SQL（Java 迁移在 PG profile 下被显式关闭）。</li>
 * <li><b>两个目录不会同时进 locations</b>（同版本号会被 Flyway 拒绝）：由
 * application-postgresql.yml 的 {@code locations} 单值保证，本用例兜住目录内容本身的完整性。</li>
 * </ol>
 *
 * <p>PG 的真实执行（连接真实库、跑完 V1~V10、Hibernate validate）不在本机跑，留 CI ——
 * 本用例只做"能在本机确定的部分"：迁移集完整性、版本号唯一性、方言差异的边界、H2 侧不可变。
 */
class MigrationSetGuardTest {

	/** H2 侧已发布迁移的指纹（改这些文件必须先走迁移不可变评审 + 更新本表）。 */
	private static final Map<String, String> PINNED_H2_FINGERPRINTS = new LinkedHashMap<>();

	static {
		PINNED_H2_FINGERPRINTS.put("V1__schema.sql", "89aca9e073180dd539cb4ab2436da561adc1447288a45558d23107fa48acc6cb");
		PINNED_H2_FINGERPRINTS.put("V2__staging_base_version.sql", "ccf4ba2b703a45d1283fad26bf8147f6666dc2ac8ca4175c41571d0f119fafcd");
		PINNED_H2_FINGERPRINTS.put("V3__ai_feedback.sql", "eb2438e7942d99a240219c66d8ba2310e9d755b1531aa3b321655772d3064144");
		PINNED_H2_FINGERPRINTS.put("V4__ai_provider_config.sql", "d5fab3285cb585c6c698c0fa8783c8dd33597308a31e4b5af220b73cb76d6bd4");
		PINNED_H2_FINGERPRINTS.put("V5__ai_model_reasoning_backfill.sql", "ddce4c0de81900aaf029d5164a53e43b5799e7a39c316a2111278c29b4e02086");
		PINNED_H2_FINGERPRINTS.put("V6__Task_child_fk.java", "813df00624aa1fa81aa077d28e1f32a3e90539f7cce9bbec6533ecddd317ec2f");
	}

	/** 无方言差异、两侧必须逐字节相同的迁移。 */
	private static final List<String> SHARED_FILES = List.of(
			"V2__staging_base_version.sql",
			"V3__ai_feedback.sql",
			"V4__ai_provider_config.sql",
			"V5__ai_model_reasoning_backfill.sql",
			"V7__app_lock.sql",
			"V9__ai_chat_message_idempotency.sql",
			"V10__drop_ai_chat_message.sql");

	/** 允许"必要方言兼容"（CLOB→TEXT）的文件：其余结构必须与 H2 一致。 */
	private static final List<String> DIALECT_ADAPTED_FILES = List.of(
			"V1__schema.sql",
			"V8__ai_chat_message.sql");

	private static final Path H2_DIR = Path.of("src/main/resources/db/migration");

	private static final Path PG_DIR = Path.of("src/main/resources/db/postgresql");

	private static final Path JAVA_MIGRATION_DIR = Path.of("src/main/java/db/migration");

	@Test
	void h2MigrationsV1ToV6AreByteStable() throws Exception {
		for (Map.Entry<String, String> pinned : PINNED_H2_FINGERPRINTS.entrySet()) {
			String name = pinned.getKey();
			Path file = name.endsWith(".java")
					? Path.of("src/main/java/db/migration").resolve(name)
					: H2_DIR.resolve(name);
			assertThat(file).as("迁移文件必须存在且不得改名：%s", name).isRegularFile();
			assertThat(sha256(file))
					.as("%s 已发布，checksum 不可变（改动会让存量库启动失败）", name)
					.isEqualTo(pinned.getValue());
		}
	}

	@Test
	void postgresSetHasEveryH2VersionAsSql() {
		assertThat(versionsIn(PG_DIR))
				.as("PG 迁移集 = V1~V10（与 H2 侧版本号逐一对齐；PG 侧没有 Java 迁移）")
				.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
		List<String> pgFiles = sqlFiles(PG_DIR);
		assertThat(pgFiles).as("PG 目录只放 SQL 迁移（Java 迁移在 PG profile 下被显式关闭）")
				.allMatch(name -> name.endsWith(".sql"));
	}

	@Test
	void h2SqlAndJavaMigrationsCoverV1ToV10ExactlyOnce() {
		List<String> sqlVersions = versionsIn(H2_DIR);
		List<String> javaVersions = javaMigrationVersions();

		assertThat(sqlVersions)
				.as("H2 的 SQL 迁移版本（V6 是 Java 迁移，故 SQL 侧没有 6）")
				.containsExactly("1", "2", "3", "4", "5", "7", "8", "9", "10");
		assertThat(javaVersions).as("H2 的 Java 迁移版本").containsExactly("6");

		List<String> all = new java.util.ArrayList<>(sqlVersions);
		all.addAll(javaVersions);
		assertThat(all).as("H2 侧（SQL + Java）合起来必须恰好覆盖 1~10，且同号只出现一次")
				.doesNotHaveDuplicates()
				.containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

		assertThat(versionsIn(PG_DIR))
				.as("两目录版本号对齐：PG 侧不得缺号/多号")
				.containsExactlyElementsOf(all.stream()
						.sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
						.toList());
	}

	@Test
	void sharedMigrationsAreByteIdenticalAcrossDialects() throws Exception {
		for (String name : SHARED_FILES) {
			Path h2 = H2_DIR.resolve(name);
			Path pg = PG_DIR.resolve(name);
			assertThat(pg).as("%s 应在 PG 目录", name).isRegularFile();
			assertThat(normalizedBytes(pg))
					.as("%s 无方言差异 ⇒ 两侧必须同字节（改一侧等于改两处）", name)
					.isEqualTo(normalizedBytes(h2));
		}
	}

	@Test
	void dialectAdaptedMigrationsMatchH2AfterClobToTextAcrossWholeStatementText() throws Exception {
		for (String name : DIALECT_ADAPTED_FILES) {
			String h2Raw = Files.readString(H2_DIR.resolve(name), StandardCharsets.UTF_8);
			String pgRaw = Files.readString(PG_DIR.resolve(name), StandardCharsets.UTF_8);

			assertThat(h2Raw).as("H2 形态的 CLOB 是既有事实，不改：%s", name).contains("CLOB");
			assertThat(stripComments(pgRaw)).as("PG 没有 CLOB 类型，方言副本必须是 TEXT：%s", name)
					.doesNotContain("CLOB");

			String expected = normalizedSql(h2Raw.replace("CLOB", "TEXT"));
			String actual = normalizedSql(pgRaw);

			assertThat(actual)
					.as("除 CLOB→TEXT 外，**整段 SQL 文本**必须与 H2 一致：%s"
							+ "（列 / 约束 / 索引 / 顺序全在比较范围内；只比列定义会漏掉约束与索引的方言改写）", name)
					.isEqualTo(expected);

			// 证明上面的比较确实覆盖了约束与索引行（否则"整段相等"可能只是文件里恰好没有它们）
			assertThat(actual).as("比较文本必须包含约束定义（CONSTRAINT/PRIMARY KEY/UNIQUE）：%s", name)
					.containsIgnoringCase("CONSTRAINT")
					.containsIgnoringCase("PRIMARY KEY");
			if (actual.toUpperCase().contains("CREATE INDEX")) {
				assertThat(actual).as("比较文本必须包含索引定义：%s", name)
						.containsIgnoringCase("CREATE INDEX");
			}
		}
	}

	@Test
	void v10DropsOnlyTargetTableAndKeepsFeedbackAndConfigTables() throws Exception {
		String h2Drop = stripComments(Files.readString(H2_DIR.resolve("V10__drop_ai_chat_message.sql"),
				StandardCharsets.UTF_8));
		String pgDrop = stripComments(Files.readString(PG_DIR.resolve("V10__drop_ai_chat_message.sql"),
				StandardCharsets.UTF_8));

		assertThat(normalizedSql(h2Drop))
				.as("V10 只做一件事：删目标表（多一句 DROP/ALTER/TRUNCATE 都会在这里现形）")
				.isEqualTo("DROP TABLE IF EXISTS ai_chat_message;");
		assertThat(normalizedSql(pgDrop)).as("删表无方言差异 ⇒ 两侧同语义")
				.isEqualTo(normalizedSql(h2Drop));

		assertThat(stripComments(Files.readString(H2_DIR.resolve("V3__ai_feedback.sql"), StandardCharsets.UTF_8)))
				.as("反馈表保留：V10 不得动它")
				.contains("CREATE TABLE ai_feedback");
		String v4 = stripComments(Files.readString(H2_DIR.resolve("V4__ai_provider_config.sql"),
				StandardCharsets.UTF_8));
		for (String table : List.of("ai_provider", "ai_model", "ai_setting", "ai_config_audit")) {
			assertThat(v4).as("AI 配置表保留：V10 不得动它（%s）", table).contains("CREATE TABLE " + table);
		}

		for (Path dir : List.of(H2_DIR, PG_DIR)) {
			for (String name : sqlFiles(dir)) {
				if (name.startsWith("V10__")) {
					continue;
				}
				String upper = stripComments(Files.readString(dir.resolve(name), StandardCharsets.UTF_8))
						.toUpperCase();
				assertThat(upper).as("既有迁移不得删除目标/反馈/配置表：%s/%s", dir, name)
						.doesNotContain("DROP TABLE IF EXISTS AI_CHAT_MESSAGE")
						.doesNotContain("DROP TABLE IF EXISTS AI_FEEDBACK")
						.doesNotContain("DROP TABLE IF EXISTS AI_PROVIDER")
						.doesNotContain("DROP TABLE IF EXISTS AI_MODEL")
						.doesNotContain("DROP TABLE IF EXISTS AI_SETTING")
						.doesNotContain("DROP TABLE IF EXISTS AI_CONFIG_AUDIT");
			}
		}
	}

	@Test
	void postgresV6IsSqlWithChildForeignKeys() throws Exception {
		String v6 = stripComments(Files.readString(PG_DIR.resolve("V6__task_child_fk.sql"), StandardCharsets.UTF_8));

		for (String fk : List.of("fk_task_items_task", "fk_task_files_task", "fk_config_staging_rows_task",
				"fk_job_items_job", "fk_validation_issues_job")) {
			assertThat(v6).contains(fk);
		}
		assertThat(v6).as("子表 FK 必须级联删除（与 H2 的 Java 迁移同语义）").contains("ON DELETE CASCADE");
		assertThat(v6).as("PG 侧孤儿预检必须只报错不改数据").contains("RAISE EXCEPTION")
				.doesNotContain("DELETE FROM tasks")
				.doesNotContain("DELETE FROM jobs");
	}

	@Test
	void appLockHasBothSeedLockRowAndPortableDdl() throws Exception {
		String v7 = stripComments(Files.readString(H2_DIR.resolve("V7__app_lock.sql"), StandardCharsets.UTF_8));

		assertThat(v7).contains("CREATE TABLE IF NOT EXISTS app_lock").contains("lock_name").contains("'seed'");
		assertThat(v7).as("锁表的 DDL 必须两库通用（无方言函数/类型）")
				.doesNotContainIgnoringCase("CLOB")
				.doesNotContainIgnoringCase("SERIAL")
				.doesNotContain("pg_")
				.doesNotContainIgnoringCase("IDENTITY");
	}

	@Test
	void postgresProfileDisablesJavaMigrations() {
		org.flywaydb.core.api.configuration.FluentConfiguration configuration =
				org.flywaydb.core.Flyway.configure();

		new PostgresMigrationConfig().postgresqlUsesSqlMigrationsOnly().customize(configuration);

		assertThat(configuration.getJavaMigrationClassProvider().getClasses())
				.as("PG profile 下 Java 迁移必须全关：否则类路径上的 Java V6 会与 db/postgresql/V6 撞版本号")
				.isEmpty();
	}

	// ---------- helpers ----------

	private static List<String> sqlFiles(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile)
					.map(file -> file.getFileName().toString())
					.filter(name -> !name.startsWith("."))
					.sorted()
					.toList();
		}
		catch (IOException ex) {
			throw new AssertionError("无法读取迁移目录：" + dir.toAbsolutePath(), ex);
		}
	}

	/** 目录里的版本号（{@code V3__x.sql} → {@code "3"}），去重排序。 */
	private static List<String> versionsIn(Path dir) {
		return sqlFiles(dir).stream()
				.filter(name -> name.startsWith("V") && name.contains("__"))
				.map(name -> name.substring(1, name.indexOf("__")))
				.distinct()
				.sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
				.toList();
	}

	/**
	 * Java 迁移（{@code src/main/java/db/migration/V6__*.java}）的版本号。
	 *
	 * <p>Java 迁移由 Flyway 按类路径 {@code db/migration} 包扫描，**不受 locations 约束**；
	 * PG profile 会把它们整体关掉（见 PostgresMigrationConfig）。H2 侧它们与 SQL 迁移共用同一套
	 * 版本号空间，所以撞号检查必须把两边合起来看。
	 */
	private static List<String> javaMigrationVersions() {
		try (Stream<Path> files = Files.list(JAVA_MIGRATION_DIR)) {
			return files.filter(Files::isRegularFile)
					.map(file -> file.getFileName().toString())
					.filter(name -> name.matches("V\\d+__.*\\.java"))
					.map(name -> name.substring(1, name.indexOf("__")))
					.distinct()
					.sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
					.toList();
		}
		catch (IOException ex) {
			throw new AssertionError("无法读取 Java 迁移目录：" + JAVA_MIGRATION_DIR.toAbsolutePath(), ex);
		}
	}

	/**
	 * 整段 SQL 的归一化文本：去注释（行首与行内 {@code --}）、行尾归一、空白折叠。
	 *
	 * <p>口径：方言副本允许差异的**只有** CLOB→TEXT（调用方先替换）与注释（本方法去掉）；
	 * 其余任何字符差异（列、约束名、索引、语句顺序、逗号）都必须现形。
	 * Flyway 的 checksum 按行计算，注释与行尾不影响执行语义，所以这两类归一化不会掩盖真实漂移。
	 */
	private static String normalizedSql(String sql) {
		return sql.replace("\r\n", "\n")
				.replaceAll("--[^\n]*", "")
				.replaceAll("\\s+", " ")
				.trim();
	}

	private static String sha256(Path file) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedBytes(file));
		return HexFormat.of().formatHex(digest);
	}

	/**
	 * 行尾归一化后的字节（CRLF → LF）。
	 *
	 * <p>为什么必须归一化：Windows 工作区（{@code core.autocrlf=true}）里既有 CRLF，
	 * Linux CI 检出的是 LF；Flyway 的 checksum 本身按行读取（行尾不影响），所以指纹守卫
	 * 也必须只看归一化后的内容，否则同一份迁移在两平台会算出两个指纹（假红）。
	 */
	private static byte[] normalizedBytes(Path file) throws Exception {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		return text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
	}

	/** 去掉整行 {@code --} 注释（方言差异的说明文字里会提到 CLOB，不该干扰结构断言）。 */
	private static String stripComments(String sql) {
		return sql.lines()
				.filter(line -> !line.trim().startsWith("--"))
				.reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
				.toString();
	}

}
