package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出侧 DLP 的流式行为（防护批③ B3-T01/B3-T03/B3-T04/B3-T07/T08）。
 *
 * <p>
 * 三条硬约束在这里钉死：
 * <ol>
 * <li><b>跨分片不漏</b>：密钥被切成三片喂进来，拼接结果里不得出现原文；</li>
 * <li><b>短回复不延迟</b>：普通正文分片<b>立刻</b>返回（不是等到轮末 flush）—— 尾部保留窗口只在
 * "末尾可能落在模式中间"时才生效；</li>
 * <li><b>阻断只给私钥</b>：普通 key 只脱敏，{@link OutputGuard#blocked()} 保持 false。</li>
 * </ol>
 *
 * <p>样本一律拼接构造（{@code "s" + "k-"}），仓库里不出现完整凭据字面量。
 */
class OutputGuardTest {

	private static String apiKey() {
		return "s" + "k-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4";
	}

	private static String pemHead(String algo) {
		return "-----BEGIN " + algo + " PRIVATE KEY-----";
	}

	private static String pemFoot(String algo) {
		return "-----END " + algo + " PRIVATE KEY-----";
	}

	// ── 跨分片（B3-T03） ────────────────────────────────────────────────────

	@Test
	void keySplitAcrossThreeChunksIsMaskedAfterConcatenation() {
		String key = apiKey();
		OutputGuard guard = new OutputGuard();
		StringBuilder out = new StringBuilder();
		out.append(guard.feed("你的 key 是 " + key.substring(0, 3)));
		out.append(guard.feed(key.substring(3, 20)));
		out.append(guard.feed(key.substring(20) + " 请保存"));
		out.append(guard.flush());

		assertThat(out.toString()).doesNotContain(key);
		assertThat(out.toString()).contains("[REDACTED:API_KEY]");
		assertThat(guard.redacted()).isEqualTo(1);
		assertThat(guard.rules()).containsExactly("API_KEY");
	}

	@Test
	void singleChunkKeyIsMaskedByTheTimeTheTurnEnds() {
		OutputGuard guard = new OutputGuard();
		String text = "抬头 " + apiKey() + " 尾巴";
		StringBuilder out = new StringBuilder(guard.feed(text));
		out.append(guard.flush());

		// 尾部保留窗口会先扣住"从锚字符起"的一段（这里是 key 本身），轮末清仓后整体脱敏
		assertThat(out.toString()).doesNotContain(apiKey()).contains("[REDACTED:API_KEY]");
		assertThat(guard.redacted()).isEqualTo(1);
	}

	// ── 首字延迟（B3-T04） ─────────────────────────────────────────────────

	@Test
	void plainTextIsEmittedImmediatelyWithoutWaitingForFlush() {
		OutputGuard guard = new OutputGuard();

		assertThat(guard.feed("你好")).as("普通正文不该被扣住").isEqualTo("你好");
		assertThat(guard.feed("，世界")).isEqualTo("，世界");
		assertThat(guard.flush()).isEmpty();
	}

	@Test
	void suspiciousTailIsHeldButBoundedByTheTailWindow() {
		OutputGuard guard = new OutputGuard();
		String head = "长".repeat(400);

		String first = guard.feed(head + " s");
		assertThat(first).as("尾部可疑字符被扣住").doesNotEndWith(" s");
		// 扣住量 = 尾部窗口（1 个可疑字符 + 最多 128 字符的上下文），绝不是整段缓冲
		assertThat(head.length() + 2 - first.length()).isLessThanOrEqualTo(SafetyRulesTailWindow());
	}

	private static int SafetyRulesTailWindow() {
		return com.example.configmgr.ai.gate.SafetyRules.TAIL_WINDOW;
	}

	@Test
	void flushReleasesHeldRemainder() {
		OutputGuard guard = new OutputGuard();
		guard.feed("短回答 s");

		String tail = guard.flush();
		assertThat(tail).isNotEmpty();
		assertThat(guard.flush()).as("flush 幂等").isEmpty();
	}

	// ── 阻断分级（B3-T07） ─────────────────────────────────────────────────

	@Test
	void privateKeyBlockBlocksTheStreamAndNeverLeaksTheBody() {
		OutputGuard guard = new OutputGuard();
		String body = "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun";
		StringBuilder out = new StringBuilder();
		out.append(guard.feed("这是私钥：" + pemHead("RSA") + "\n" + body + "\n" + pemFoot("RSA") + " 完"));
		out.append(guard.flush());

		assertThat(guard.blocked()).isTrue();
		assertThat(out.toString()).doesNotContain("MIIEowIBAAKCAQEA").doesNotContain("-----BEGIN");
		assertThat(out.toString()).contains("[REDACTED:PRIVATE_KEY]");
		assertThat(guard.feed("阻断之后的新文本")).as("阻断后不再外发").isEmpty();
	}

	@Test
	void unterminatedPrivateKeyHeaderIsHeldThenBlockedAtFlush() {
		OutputGuard guard = new OutputGuard();
		String out = guard.feed("输出 " + pemHead("OPENSSH") + "\nAAAA");

		assertThat(out).doesNotContain("-----BEGIN");
		assertThat(guard.blocked()).as("未闭合前还不能断言阻断").isFalse();

		String tail = guard.flush();
		assertThat(guard.blocked()).isTrue();
		assertThat(tail).contains("[REDACTED:PRIVATE_KEY]").doesNotContain("-----BEGIN");
	}

	@Test
	void ordinaryKeyDoesNotBlockTheTurn() {
		OutputGuard guard = new OutputGuard();
		guard.feed(apiKey());
		guard.flush();

		assertThat(guard.blocked()).isFalse();
		assertThat(guard.safety()).containsEntry("blocked", false).containsEntry("redacted", 1);
	}

	// ── 终帧 safety（B3-T08） ──────────────────────────────────────────────

	@Test
	void safetyIsNullWhenNothingHit() {
		OutputGuard guard = new OutputGuard();
		guard.feed("一切正常");
		guard.flush();

		assertThat(guard.safety()).as("未命中不写该键（与 done.context 同姿态）").isNull();
	}

	@Test
	void safetyCarriesRulesAndCounts() {
		OutputGuard guard = new OutputGuard();
		guard.feed(apiKey() + " 与 " + apiKey());
		guard.flush();

		Map<String, Object> safety = guard.safety();
		assertThat(safety).containsEntry("redacted", 2).containsEntry("blocked", false);
		assertThat((List<String>) safety.get("rules")).containsExactly("API_KEY");
	}

	// ── 审计元数据（B3-T11 的载荷面） ──────────────────────────────────────

	@Test
	void eventsCarryOffsetAndLengthButNeverTheSecret() {
		OutputGuard guard = new OutputGuard();
		guard.feed("前缀 " + apiKey());
		guard.flush();

		List<OutputGuard.Event> events = guard.events();
		assertThat(events).hasSize(1);
		OutputGuard.Event event = events.get(0);
		assertThat(event.ruleId()).isEqualTo("API_KEY");
		assertThat(event.offset()).isEqualTo("前缀 ".length());
		assertThat(event.length()).isEqualTo(apiKey().length());
		assertThat(event.action()).isEqualTo("masked");
	}

	@Test
	void emptyChunksAreNoOps() {
		OutputGuard guard = new OutputGuard();
		assertThat(guard.feed(null)).isEmpty();
		assertThat(guard.feed("")).isEmpty();
		assertThat(guard.flush()).isEmpty();
		assertThat(guard.redacted()).isZero();
	}

	// ── 批 D · D1：不再攒批（只认规则真实前缀） ──────────────────────────────

	@Test
	void markdownParagraphIsEmittedImmediatelyWithoutBatching() {
		String doc = "- 导出范围：已选配置项\n"
				+ "- 字段口径：按条件过滤\n"
				+ "- 交付形式：xlsx 附件\n"
				+ "请确认以上口径后再启动导出。";
		OutputGuard guard = new OutputGuard();

		assertThat(guard.feed(doc)).as("Markdown 标点不得触发尾部扣留").isEqualTo(doc);
		assertThat(guard.flush()).isEmpty();
		assertThat(guard.redacted()).isZero();
	}

	@Test
	void longParagraphIsNotHeldBehindTheTailWindow() {
		OutputGuard guard = new OutputGuard();
		String head = "这是一段用于验证流式输出不再攒批的中文正文。".repeat(20);
		StringBuilder out = new StringBuilder();

		out.append(guard.feed(head));
		out.append(guard.feed("- 第二步：确认字段\n- 第三步：启动导出\n说明：按向导提示操作。"));

		assertThat(out.length()).as("已可发的正文不得被尾部窗口压在缓冲区里")
				.isGreaterThanOrEqualTo(head.length());
		assertThat(guard.flush().length()).as("轮末最多只剩尾部候选").isLessThanOrEqualTo(SafetyRulesTailWindow());
	}

	@Test
	void keySplitInsideTheLiteralPrefixIsStillMasked() {
		String key = apiKey();
		OutputGuard guard = new OutputGuard();
		StringBuilder out = new StringBuilder();

		// 第一帧只到前缀的第一个字符：旧实现靠"单字符锚"侥幸接住，新实现必须靠"未写全前缀"
		out.append(guard.feed("你的 key 是 " + key.substring(0, 1)));
		out.append(guard.feed(key.substring(1, 12)));
		out.append(guard.feed(key.substring(12) + " 请保存"));
		out.append(guard.flush());

		assertThat(out.toString()).doesNotContain(key);
		assertThat(out.toString()).contains("[REDACTED:API_KEY]");
		assertThat(guard.redacted()).isEqualTo(1);
	}

	@Test
	void markdownTextAfterAKeyIsStillMasked() {
		OutputGuard guard = new OutputGuard();
		StringBuilder out = new StringBuilder();
		out.append(guard.feed("- 当前 key：" + apiKey()));
		out.append(guard.feed("\n- 请勿外发"));

		assertThat(out.toString()).doesNotContain(apiKey()).contains("[REDACTED:API_KEY]");
		assertThat(guard.flush()).doesNotContain(apiKey());
	}

}
