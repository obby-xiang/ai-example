package com.example.configmgr.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移集的<b>静态</b>守卫（当前基线 = V1/V2）。
 *
 * <h2>守什么</h2>
 * <ol>
 * <li><b>H2 已发布迁移的 checksum 不可变</b>：{@code V1__schema.sql} / {@code V2__ai_model_request_extra_body.sql}
 * 已在存量库的 {@code flyway_schema_history} 里登记；改一个空格都会让启动报 checksum mismatch。
 * 指纹按行尾归一后的字节钉死（Windows CRLF / Linux LF 不至于假红）。</li>
 * <li><b>两目录版本完整对齐且无同号冲突</b>：{@code db/migration} 与 {@code db/postgresql}
 * 的版本集合必须逐一对齐、每个版本号只出现一次；文件必须都是 {@code V<number>__*.sql}。
 * 不再为不存在的 V3~V11 写特判 —— 用通用版本/命名守卫覆盖。</li>
 * <li><b>方言差异只允许 V1 的 CLOB→TEXT</b>：V1 除该类型替换与注释/空白外必须与 H2 一致；
 * 其余迁移（当前 V2）两侧<b>逐字节相同</b>。</li>
 * <li><b>V2 extra body 结构与表保留</b>：V2 只对 {@code ai_model} 追加
 * {@code request_extra_body TEXT}，且实体映射仍在。</li>
 * <li><b>PG profile 只跑方言副本且不 baseline</b>：{@code locations=classpath:db/postgresql}、
 * {@code baseline-on-migrate=false}。</li>
 * </ol>
 */
class MigrationSetGuardTest {

	/** H2 侧已发布迁移的指纹（改这些文件必须先走迁移不可变评审 + 更新本表）。 */
	private static final Map<String, String> PINNED_H2_FINGERPRINTS = new LinkedHashMap<>();

	static {
		PINNED_H2_FINGERPRINTS.put("V1__schema.sql",
				"0c58d4cd08e40d61005a72e6cc4edef9128bf316da8ec494f4e70273166917a1");
		PINNED_H2_FINGERPRINTS.put("V2__ai_model_request_extra_body.sql",
				"0f471934069c4f900d971706846062f99e51e0ff8acd56a0ddfff2bc8b23aa18");
	}

	private static final Path BACKEND = backendRoot();

	private static final Path H2_DIR = BACKEND.resolve("src/main/resources/db/migration");

	private static final Path PG_DIR = BACKEND.resolve("src/main/resources/db/postgresql");

	private static final Path ENTITY = BACKEND.resolve(
			"src/main/java/com/example/configmgr/ai/config/db/AiProviderModel.java");

	private static final String V1 = "V1__schema.sql";

	private static final String V2 = "V2__ai_model_request_extra_body.sql";

	@Test
	void h2MigrationsV1AndV2AreByteStable() throws Exception {
		for (Map.Entry<String, String> pinned : PINNED_H2_FINGERPRINTS.entrySet()) {
			Path file = H2_DIR.resolve(pinned.getKey());
			assertThat(file).as("迁移文件必须存在且不得改名：%s", pinned.getKey()).isRegularFile();
			assertThat(sha256(file))
					.as("%s 已发布，checksum 不可变（改动会让存量库启动失败）", pinned.getKey())
					.isEqualTo(pinned.getValue());
		}
	}

	@Test
	void h2AndPgVersionSetsAlignExactlyOnce() {
		List<String> h2Versions = rawVersions(H2_DIR);
		List<String> pgVersions = rawVersions(PG_DIR);

		assertThat(sqlFiles(H2_DIR)).as("H2 目录只放 SQL 迁移（当前基线无 Java 迁移）")
				.allMatch(name -> name.matches("V\\d+__.*\\.sql"));
		assertThat(sqlFiles(PG_DIR)).as("PG 目录只放 SQL 迁移")
				.allMatch(name -> name.matches("V\\d+__.*\\.sql"));

		assertThat(h2Versions).as("H2 版本号不得重复（同号冲突会让 Flyway 启动失败）")
				.doesNotHaveDuplicates();
		assertThat(pgVersions).as("PG 版本号不得重复").doesNotHaveDuplicates();
		assertThat(h2Versions).as("当前 H2 基线").containsExactly("1", "2");
		assertThat(pgVersions).as("两目录版本完整对齐：PG 不得缺号/多号")
				.containsExactlyElementsOf(h2Versions);
	}

	@Test
	void onlyV1AdaptsClobToTextAndV2IsByteIdentical() throws Exception {
		String h2Raw = Files.readString(H2_DIR.resolve(V1), StandardCharsets.UTF_8);
		String pgRaw = Files.readString(PG_DIR.resolve(V1), StandardCharsets.UTF_8);
		assertThat(h2Raw).as("H2 形态的 CLOB 是既有事实，不改").contains("CLOB");
		assertThat(stripComments(pgRaw)).as("PG 没有 CLOB 类型，V1 方言副本必须是 TEXT")
				.doesNotContain("CLOB");
		assertThat(normalizedSql(pgRaw))
				.as("V1 除 CLOB→TEXT（及注释/空白）外必须与 H2 一致")
				.isEqualTo(normalizedSql(h2Raw.replace("CLOB", "TEXT")));

		Path h2V2 = H2_DIR.resolve(V2);
		Path pgV2 = PG_DIR.resolve(V2);
		assertThat(Files.readAllBytes(pgV2))
				.as("V2 无方言差异 ⇒ 两侧必须逐字节相同")
				.isEqualTo(Files.readAllBytes(h2V2));
	}

	@Test
	void v2ExtraBodyMigrationKeepsAiModelTableAndEntityMapping() throws Exception {
		String h2V2 = Files.readString(H2_DIR.resolve(V2), StandardCharsets.UTF_8);
		String pgV2 = Files.readString(PG_DIR.resolve(V2), StandardCharsets.UTF_8);
		for (String sql : List.of(h2V2, pgV2)) {
			String normalized = normalizedSql(sql);
			assertThat(normalized).as("V2 只对 ai_model 追加请求扩展列")
					.contains("ALTER TABLE ai_model ADD COLUMN IF NOT EXISTS request_extra_body TEXT");
			assertThat(normalized.toUpperCase())
					.as("V2 是 additive 迁移：不得 DROP/TRUNCATE 既有结构")
					.doesNotContain("DROP TABLE")
					.doesNotContain("DROP COLUMN")
					.doesNotContain("TRUNCATE");
		}
		assertThat(ENTITY).as("ai_model 实体映射必须存在").isRegularFile();
		String entity = Files.readString(ENTITY, StandardCharsets.UTF_8);
		assertThat(entity).as("实体仍映射 ai_model 表").contains("@Table(name = \"ai_model\"");
		assertThat(entity).as("实体仍映射 request_extra_body 列")
				.contains("@Column(name = \"request_extra_body\"");
	}

	@Test
	void postgresProfilePinsTheDialectMigrationSetAndDisablesBaseline() throws Exception {
		var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
		var sources = loader.load("pg", new org.springframework.core.io.ClassPathResource("application-postgresql.yml"));
		assertThat(sources).isNotEmpty();
		var source = sources.get(0);
		assertThat(source.getProperty("spring.flyway.locations"))
				.as("PG profile 必须只跑 db/postgresql 方言副本（不能与 db/migration 并列）")
				.isEqualTo("classpath:db/postgresql");
		assertThat(source.getProperty("spring.flyway.baseline-on-migrate"))
				.as("生产库不得静默 baseline（迁移未真正执行却记成功）").isEqualTo(false);
	}

	// ---------- helpers ----------

	/**
	 * 定位 backend 目录：兼容从 backend 目录运行（Maven/IDE 常规）与从仓库根运行两种 cwd。
	 */
	private static Path backendRoot() {
		List<Path> candidates = new ArrayList<>();
		candidates.add(Path.of("."));
		candidates.add(Path.of("backend"));
		for (Path candidate : candidates) {
			if (Files.isDirectory(candidate.resolve("src/main/resources/db/migration"))) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		throw new AssertionError("无法定位 backend/src/main/resources/db/migration（cwd="
				+ Path.of(".").toAbsolutePath().normalize() + "）");
	}

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

	/** 目录里的版本号（{@code V3__x.sql} → {@code "3"}），<b>不去重</b>，按数值排序 —— 让同号冲突现形。 */
	private static List<String> rawVersions(Path dir) {
		return sqlFiles(dir).stream()
				.map(name -> name.substring(1, name.indexOf("__")))
				.sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
				.toList();
	}

	/**
	 * 整段 SQL 的归一化文本：去注释（行首与行内 {@code --}）、行尾归一、空白折叠。
	 * V1 两侧的注释/格式可以不同；调用方先做 CLOB→TEXT，其余字符差异必须现形。
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

	/** 行尾归一化后的字节（CRLF → LF），避免同一份迁移在两平台算出两个指纹。 */
	private static byte[] normalizedBytes(Path file) throws Exception {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		return text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
	}

	/** 去掉整行 {@code --} 注释（方言说明文字里会提到 CLOB，不该干扰结构断言）。 */
	private static String stripComments(String sql) {
		return sql.lines()
				.filter(line -> !line.trim().startsWith("--"))
				.reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), StringBuilder::append)
				.toString();
	}

}
