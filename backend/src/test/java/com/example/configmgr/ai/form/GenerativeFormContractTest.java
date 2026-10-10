package com.example.configmgr.ai.form;

import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tools.AiTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-15 用例④：<b>契约对账</b>（工具名 / 生成的 JSON Schema / 白名单 / 复核器自认领）。
 *
 * <p>这里锁的是"三份描述同一件事的东西不许漂移"：
 * <ol>
 * <li>{@code @Tool(name=…)} 的工具名 == {@link GenerativeFormRules#TOOL_NAME}
 * （后者是复核器自认领的判据 —— 漂移就等于安全闸对不上工具，
 * 且挂起机制对工具名零知识、不会报错）；</li>
 * <li>{@code AiTools} 里那组 record 的属性名 == 白名单属性集
 * （改 record 忘改白名单 / 反之，都会让"模型看到的形状"与"闸门接受的形状"分叉）；</li>
 * <li>复核器只认领自己的工具（不误伤既有前端工具）。</li>
 * </ol>
 *
 * <p>另含一条跨端锚定：{@link #forbiddenFieldKeysMatchAcrossEnds} 锁"原型键黑名单三键"在后端事实源
 * （{@link GenerativeFormRules#FORBIDDEN_FIELD_KEYS}）、前端副本（`frontend/src/types/form-schema.ts`）、
 * 桩副本（`scripts/ci/stub-upstream.mjs`）三处的逐键相等（排期条目 16 / M2-T4 T4-5）。
 *
 * <p>不启 Spring：{@code MethodToolCallbackProvider} 直接对 {@code AiTools} 实例生成回调，
 * 与运行时（{@code ToolRegistry} 扫描得到的）是同一份生成逻辑。
 */
class GenerativeFormContractTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 服务依赖传 null：本类只读方法签名（生成 Schema），不调用任何工具方法体。 */
	private final AiTools aiTools = new AiTools(null, null, null, null);

	private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

	@Test
	void toolNameConstantMatchesTheRegisteredTool() {
		assertThat(callback().getToolDefinition().name()).isEqualTo(GenerativeFormRules.TOOL_NAME);
	}

	@Test
	void guardClaimsOnlyItsOwnTool() {
		assertThat(this.guard.supports(GenerativeFormRules.TOOL_NAME)).isTrue();
		assertThat(this.guard.supports("navigate_to")).isFalse();
		assertThat(this.guard.supports("set_condition")).isFalse();
		assertThat(this.guard.supports(null)).isFalse();
	}

	// ── 跨端契约锚定：原型键黑名单（排期条目 16 / M2-T4 T4-5） ───────────────────

	/**
	 * 原型键三键在后端事实源、前端副本、桩副本之间<b>逐键相等</b>。
	 *
	 * <p>排期条目 16 的实现形态（裁决 5 = Java 用例读仓库相对路径）：本类原有的三例锁的是
	 * 工具名 / 生成的 JSON Schema / 白名单属性，"原型键黑名单"不在其中，本用例补上该缺口。
	 *
	 * <p><b>文件位置依赖属期望行为</b>：本用例以仓库相对路径读
	 * {@code ../frontend/src/types/form-schema.ts} 与 {@code ../scripts/ci/stub-upstream.mjs}
	 * （工作目录 = {@code backend}，本地与 CI 一致）。这两个副本被移动/改名导致本用例红，
	 * 正是"跨端契约漂移可见"的设计意图，不是环境问题。
	 */
	@Test
	void forbiddenFieldKeysMatchAcrossEnds() throws IOException {
		Set<String> backend = GenerativeFormRules.FORBIDDEN_FIELD_KEYS;
		Set<String> frontend = quotedValuesOf(readDeclaration(
				Path.of("..", "frontend", "src", "types", "form-schema.ts"),
				"const FORBIDDEN_FIELD_KEYS = new Set\\(\\[([^\\]]*)\\]\\)"));
		Set<String> stub = quotedValuesOf(readDeclaration(
				Path.of("..", "scripts", "ci", "stub-upstream.mjs"),
				"const FORBIDDEN_KEYS = \\[([^\\]]*)\\]"));

		// 反例守卫：三键缺一即红 —— 防"空集对空集"式的恒真断言
		assertThat(backend).containsExactlyInAnyOrder("__proto__", "constructor", "prototype");
		assertThat(frontend).containsExactlyInAnyOrderElementsOf(backend);
		assertThat(stub).containsExactlyInAnyOrderElementsOf(backend);
	}

	@Test
	void generatedSchemaIsTheWhitelistMirror() throws Exception {
		JsonNode root = this.mapper.readTree(callback().getToolDefinition().inputSchema());

		// 顶层：只有 form 一个参数，且不允许额外汇总键（与 argumentReasons 的"只接受 form"同口径）
		assertThat(fieldNames(root.path("properties"))).containsExactly("form");
		assertThat(textValues(root.path("required"))).containsExactly("form");
		assertThat(root.path("additionalProperties").asBoolean()).isFalse();

		// 表单级属性 == 白名单
		JsonNode form = root.path("properties").path("form");
		assertThat(fieldNames(form.path("properties")))
			.containsExactlyInAnyOrderElementsOf(GenerativeFormRules.FORM_ATTRIBUTES);
		assertThat(textValues(form.path("required"))).containsExactlyInAnyOrder("scenario", "fields");

		// 字段级属性 == 白名单（模型看到的形状必须与闸门接受的形状一致）
		JsonNode field = form.path("properties").path("fields").path("items");
		assertThat(fieldNames(field.path("properties")))
			.containsExactlyInAnyOrderElementsOf(GenerativeFormRules.FIELD_ATTRIBUTES);
		assertThat(textValues(field.path("required"))).containsExactlyInAnyOrder("key", "label", "type");

		// 选项级属性 == 白名单
		JsonNode option = field.path("properties").path("options").path("items");
		assertThat(fieldNames(option.path("properties")))
			.containsExactlyInAnyOrderElementsOf(GenerativeFormRules.OPTION_ATTRIBUTES);
		assertThat(textValues(option.path("required"))).containsExactly("value");

		// 字段类型白名单以文本形式告知模型（type 是字符串枚举，非封闭 enum —— 越界由闸门拦）
		assertThat(field.path("properties").path("type").path("description").asText())
			.contains(GenerativeFormRules.FIELD_TYPES.toArray(String[]::new));
	}

	@Test
	void guardRejectsArgumentsThatAreNotJson() {
		assertRejected(this.guard.inspectArguments("{不是 JSON"), GenerativeFormRules.SCHEMA_REJECTED);
		assertRejected(this.guard.inspectArguments(null), GenerativeFormRules.SCHEMA_REJECTED);
	}

	@Test
	void guardAcceptsWhitelistedSchema() {
		String args = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
				+ "{\"key\":\"mode\",\"label\":\"导入模式\",\"type\":\"enum\",\"options\":[{\"value\":\"ADD\"}]}]}}";
		assertThat(this.guard.inspectArguments(args).accepted()).isTrue();
	}

	@Test
	void guardAcceptsValuesThatMatchTheSchema() {
		String args = "{\"form\":{\"scenario\":\"FILTER\",\"fields\":["
				+ "{\"key\":\"keyword\",\"label\":\"关键字\",\"type\":\"text\",\"required\":true}]}}";
		assertThat(this.guard.inspectResult(args, "{\"keyword\":\"CNY\"}").accepted()).isTrue();
	}

	/**
	 * 回灌必须是"值 JSON 对象"或显式取消：前端若把"这个工具我渲染不了"的说明文本当作结果回灌，
	 * 会被判 {@code FORM_RESULT_REJECTED}（挂起不消费）——这是给 GF-C 前端的契约提醒：
	 * 表单能力不可用时应带 {@code cancelled=true} 回灌，而不是回灌一段说明文字
	 * （见 {@code docs/evidence/GFa-生成式表单后端工具验证.md} 的 GF-C 对接清单）。
	 */
	@Test
	void guardRejectsFreeTextResultAndTellsTheFrontendWhatToDo() {
		String args = "{\"form\":{\"scenario\":\"FILTER\",\"fields\":["
				+ "{\"key\":\"keyword\",\"label\":\"关键字\",\"type\":\"text\"}]}}";
		assertRejected(this.guard.inspectResult(args, "该工具前端不可用：无表单渲染器"),
				GenerativeFormRules.RESULT_REJECTED);
		assertRejected(this.guard.inspectResult(args, "{\"other\":1}"), GenerativeFormRules.RESULT_REJECTED);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	/** 读跨端副本里的常量声明（工作目录 = {@code backend}，路径为仓库相对）；取不到即断言失败。 */
	private static String readDeclaration(Path relative, String declaration) throws IOException {
		assertThat(Files.exists(relative))
			.as("跨端副本必须存在（工作目录 = backend）：%s", relative.toAbsolutePath())
			.isTrue();
		Matcher matcher = Pattern.compile(declaration).matcher(Files.readString(relative, StandardCharsets.UTF_8));
		assertThat(matcher.find())
			.as("未在 %s 中找到声明 %s", relative, declaration)
			.isTrue();
		return matcher.group(1);
	}

	/** 声明片段里的单引号字面量集合（顺序保持）。 */
	private static Set<String> quotedValuesOf(String literalList) {
		Set<String> values = new LinkedHashSet<>();
		Matcher matcher = Pattern.compile("'([^']+)'").matcher(literalList);
		while (matcher.find()) {
			values.add(matcher.group(1));
		}
		return values;
	}

	private ToolCallback callback() {
		for (ToolCallback callback : MethodToolCallbackProvider.builder()
			.toolObjects(this.aiTools)
			.build()
			.getToolCallbacks()) {
			if (GenerativeFormRules.TOOL_NAME.equals(callback.getToolDefinition().name())) {
				return callback;
			}
		}
		throw new AssertionError("未找到工具：" + GenerativeFormRules.TOOL_NAME);
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static List<String> textValues(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(element -> values.add(element.asText()));
		return values;
	}

	/** 被拒判定的公共形态：不接受、码正确、原因非空、可渲染文本带码与原因清单。 */
	private static void assertRejected(FrontendToolGuard.Verdict verdict, String code) {
		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.code()).isEqualTo(code);
		assertThat(verdict.reasons()).isNotEmpty();
		assertThat(verdict.render()).startsWith(code).contains("被拒原因");
	}

}
