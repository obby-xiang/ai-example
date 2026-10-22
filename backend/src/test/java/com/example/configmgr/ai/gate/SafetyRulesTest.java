package com.example.configmgr.ai.gate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高置信凭据规则表（防护批③ B3-T01/B3-T02）：六类命中 + 五类误报 + 跨分片可拼。
 *
 * <p>
 * <b>样本纪律</b>：本文件里所有凭据样本一律拼接构造（{@code "s" + "k-"}），
 * 与 {@code scripts/githooks/scan-lib.sh} 的"扫描器不自报"同一口径 —— 仓库里不得出现
 * 能被自家扫描器命中的完整字面量。
 */
class SafetyRulesTest {

	private static String apiKey() {
		return "s" + "k-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4";
	}

	private static String awsKey() {
		return "AK" + "IA" + "ABCDEFGHIJKLMNOP";
	}

	private static String pemHead(String algo) {
		return "-----BEGIN " + algo + " PRIVATE KEY-----";
	}

	private static String pemFoot(String algo) {
		return "-----END " + algo + " PRIVATE KEY-----";
	}

	private static String privateKeyBlock() {
		String body = "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun";
		return pemHead("RSA") + "\n" + body + "\n" + pemFoot("RSA");
	}

	// ── 六类命中（B3-T01） ──────────────────────────────────────────────────

	@Test
	void apiKeyIsMaskedWithRuleId() {
		SafetyRules.Masked masked = SafetyRules.mask("我的 key 是 " + apiKey() + " 请帮我看看");

		assertThat(masked.redacted()).isEqualTo(1);
		assertThat(masked.rules()).containsExactly(SafetyRules.RULE_API_KEY);
		assertThat(masked.text()).contains(SafetyRules.token(SafetyRules.RULE_API_KEY)).doesNotContain(apiKey());
		assertThat(masked.blocked()).as("普通 key 只脱敏，不阻断").isFalse();
	}

	@Test
	void awsAccessKeyIsMasked() {
		SafetyRules.Masked masked = SafetyRules.mask("export AWS_KEY=" + awsKey());

		assertThat(masked.rules()).containsExactly(SafetyRules.RULE_AWS_KEY);
		assertThat(masked.text()).doesNotContain(awsKey()).contains("[REDACTED:AWS_ACCESS_KEY]");
	}

	@Test
	void bearerTokenIsMasked() {
		String token = "abcdefghijklmnopqrstuvwxyz0123456789";
		SafetyRules.Masked masked = SafetyRules.mask("Authorization: Bearer " + token);

		assertThat(masked.rules()).containsExactly(SafetyRules.RULE_BEARER);
		assertThat(masked.text()).doesNotContain(token);
	}

	@Test
	void jdbcCredentialIsMasked() {
		String url = "jdbc:mysql://appuser:s3cr3t-pass@db.internal:3306/config";
		SafetyRules.Masked masked = SafetyRules.mask("连接串 " + url);

		assertThat(masked.redacted()).isEqualTo(1);
		assertThat(masked.text()).doesNotContain("s3cr3t-pass");
		assertThat(masked.rules()).contains(SafetyRules.RULE_JDBC);
	}

	@Test
	void urlUserinfoIsMasked() {
		String url = "redis://default:topsecret123@cache.internal:6379/0";
		SafetyRules.Masked masked = SafetyRules.mask(url);

		assertThat(masked.text()).doesNotContain("topsecret123");
		assertThat(masked.rules()).contains(SafetyRules.RULE_URL_USERINFO);
	}

	@Test
	void passwordAssignmentIsMasked() {
		SafetyRules.Masked masked = SafetyRules.mask("spring.datasource.password=Zx9$veryLongPwd");

		assertThat(masked.text()).doesNotContain("Zx9$veryLongPwd");
		assertThat(masked.rules()).contains(SafetyRules.RULE_PASSWORD_ASSIGN);
	}

	@Test
	void completePrivateKeyBlockIsBlockedNotOnlyMasked() {
		SafetyRules.Masked masked = SafetyRules.mask("这是配置：" + privateKeyBlock() + " 完");

		assertThat(masked.blocked()).as("私钥整块是高置信高危 —— 唯一走 block 的一档").isTrue();
		assertThat(masked.rules()).contains(SafetyRules.RULE_PRIVATE_KEY);
		assertThat(masked.text()).doesNotContain("MIIEowIBAAKCAQEA");
	}

	@Test
	void openPrivateKeyHeaderAloneIsAlsoBlocked() {
		SafetyRules.Masked masked = SafetyRules.mask("截断的输出 " + pemHead("OPENSSH"));

		assertThat(SafetyRules.openPrivateKeyHeader(masked.text())).as("脱敏后不再剩裸头").isEqualTo(-1);
		assertThat(masked.blocked()).isTrue();
		assertThat(masked.text()).doesNotContain("-----BEGIN");
	}

	// ── 五类误报（B3-T02）：逐字不变 ─────────────────────────────────────────

	@Test
	void normalChineseParagraphIsUntouched() {
		String text = "请帮我把导出任务的状态汇总一下，并按配置定义分组给出数量。";
		SafetyRules.Masked masked = SafetyRules.mask(text);

		assertThat(masked.redacted()).isZero();
		assertThat(masked.text()).isEqualTo(text);
	}

	@Test
	void memoryJdbcWithoutCredentialIsUntouched() {
		String text = "spring.datasource.url=jdbc:h2:mem:configmgr;DB_CLOSE_DELAY=-1";
		SafetyRules.Masked masked = SafetyRules.mask(text);

		assertThat(masked.redacted()).isZero();
		assertThat(masked.text()).isEqualTo(text);
	}

	@Test
	void plainUrlWithQueryStringIsUntouched() {
		String text = "文档见 https://example.com/help?topic=export&lang=zh 这一页";
		SafetyRules.Masked masked = SafetyRules.mask(text);

		assertThat(masked.redacted()).isZero();
		assertThat(masked.text()).isEqualTo(text);
	}

	@Test
	void emailAddressIsUntouched() {
		String text = "有问题发邮件到 ops@example.com 即可";
		SafetyRules.Masked masked = SafetyRules.mask(text);

		assertThat(masked.redacted()).isZero();
		assertThat(masked.text()).isEqualTo(text);
	}

	@Test
	void discussionAboutPrefixIsUntouched() {
		String text = "AK" + "IA 是一种访问密钥 id 的前缀写法，不要贴真值。";
		SafetyRules.Masked masked = SafetyRules.mask(text);

		assertThat(masked.redacted()).as("讨论前缀本身不该被当成密钥").isZero();
		assertThat(masked.text()).isEqualTo(text);
	}

	// ── 结构与幂等 ─────────────────────────────────────────────────────────

	@Test
	void maskIsIdempotent() {
		SafetyRules.Masked once = SafetyRules.mask("key=" + apiKey());
		SafetyRules.Masked twice = SafetyRules.mask(once.text());

		assertThat(twice.redacted()).isZero();
		assertThat(twice.text()).isEqualTo(once.text());
	}

	@Test
	void hitsAreSortedAndNonOverlapping() {
		List<SafetyRules.Hit> hits = SafetyRules.hits(apiKey() + " 与 " + awsKey());
		assertThat(hits).hasSize(2);
		assertThat(hits.get(0).start()).isLessThan(hits.get(1).start());
		assertThat(hits.get(0).end()).isLessThanOrEqualTo(hits.get(1).start());
	}

	@Test
	void couldExtendMatchOnlyForSuspiciousTails() {
		assertThat(SafetyRules.couldExtendMatch("你好，世界")).isFalse();
		assertThat(SafetyRules.couldExtendMatch("一段正文 s")).as("尾部可能是 key 前缀").isTrue();
		assertThat(SafetyRules.couldExtendMatch("一段正文 " + "s" + "k-")).isTrue();
		assertThat(SafetyRules.couldExtendMatch("一段正文 -----BEGIN ")).isTrue();
	}

	@Test
	void nullAndEmptyAreSafe() {
		assertThat(SafetyRules.mask(null).text()).isEmpty();
		assertThat(SafetyRules.mask("").redacted()).isZero();
		assertThat(SafetyRules.hits(null)).isEmpty();
		assertThat(SafetyRules.openPrivateKeyHeader("")).isEqualTo(-1);
	}

	// ── 尾部扣留口径（批 D · D1）：只认规则的真实字面前缀 ─────────────────────
	//
	// 根因：旧实现用单字符锚集合 "sAkK-:jJbBpP@/"，Markdown 的 `-` / `:` 与常见字母全命中，
	// 任何正文都会在最后 128 字符里找到锚点 ⇒ 输出侧恒攒满 TAIL_WINDOW 才吐（"断续"）。
	// 新口径：只有"窗口尾部真的匹配某条规则的前缀（或该前缀的未写全片段）"才扣，
	// 扣住量 = 从该前缀起点到末尾（≤ 前缀长度 + 该前缀之后的续写区）。

	@Test
	void markdownListsAndCjkPunctuationAreNotHeld() {
		String markdown = "- 导出范围：已选配置项\n"
				+ "- 字段口径：按条件过滤\n"
				+ "- 交付形式：xlsx 附件\n"
				+ "请确认以上口径后再启动导出。";

		assertThat(SafetyRules.holdFrom(markdown)).as("Markdown 的 - 与 ： 都不是任何规则的前缀")
				.isEqualTo(markdown.length());
		assertThat(SafetyRules.couldExtendMatch(markdown)).isFalse();
	}

	@Test
	void englishIdentifierTailIsNotHeld() {
		// 尾部是 "class"：末字符 s 的前一个字符是字母 ⇒ 连 sk- 的"未写全片段"都不算
		assertThat(SafetyRules.holdFrom("请把 exportDefinitions 里的 class")).isEqualTo("请把 exportDefinitions 里的 class".length());
		assertThat(SafetyRules.holdFrom("改名为 ExportRequest")).isEqualTo("改名为 ExportRequest".length());
		// 半角冒号也不是 "://" 的未写全片段（只有 : 与 :/ 才可能是）—— 单字符扣留可接受但必须有界
		String colon = "注意:";
		assertThat(colon.length() - SafetyRules.holdFrom(colon)).as("最多扣住 1 个字符").isLessThanOrEqualTo(1);
	}

	@Test
	void holdAmountEqualsTheMatchedPrefixOrItsContinuation() {
		// 未写全的前缀：只扣住已写出的那几位
		assertThat(SafetyRules.holdFrom("一段正文 s")).isEqualTo("一段正文 s".length() - 1);
		assertThat(SafetyRules.holdFrom("一段正文 s" + "k")).isEqualTo("一段正文 s".length() - 1);
		// 前缀已成形：扣住整个未完成的候选（前缀起点到末尾）
		String inProgress = "一段正文 s" + "k-" + "A1b2C3";
		assertThat(SafetyRules.holdFrom(inProgress)).isEqualTo("一段正文 ".length());
		// 私钥头：从头位置起扣
		String pem = "输出 " + pemHead("RSA").substring(0, 14);
		assertThat(SafetyRules.holdFrom(pem)).isEqualTo("输出 ".length());
		// 扣住量恒 ≤ TAIL_WINDOW
		assertThat(inProgress.length() - SafetyRules.holdFrom(inProgress))
				.isLessThanOrEqualTo(SafetyRules.TAIL_WINDOW);
		// URL：{@code ://} 只写出冒号时扣留点退回 scheme 起点 —— 否则 next 帧的
		// {@code //user:pass@host} 里没有 scheme，URL 凭据规则认不出（凭据就漏了）
		assertThat(SafetyRules.holdFrom("见 https:")).isEqualTo("见 ".length());
		assertThat(SafetyRules.holdFrom("见 https://appuser:pass")).isEqualTo("见 ".length());
		// 已出现路径分隔符的普通 URL：候选已结束，不扣
		String plainUrl = "见 https://example.com/help";
		assertThat(SafetyRules.holdFrom(plainUrl)).isEqualTo(plainUrl.length());
	}

	@Test
	void completedMatchAtTheTailDoesNotNeedHolding() {
		// 完整密钥后面已经跟了空格 + 中文：这不是"未完成候选"，不必扣住（脱敏仍在 emit 时生效）
		String done = "key 是 " + apiKey() + " 请保存";
		assertThat(SafetyRules.holdFrom(done)).isEqualTo(done.length());
		assertThat(SafetyRules.mask(done).redacted()).isEqualTo(1);
	}

}
