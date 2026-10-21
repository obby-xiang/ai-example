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

}
