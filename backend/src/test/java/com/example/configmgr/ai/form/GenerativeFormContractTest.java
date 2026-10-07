package com.example.configmgr.ai.form;

import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tools.AiTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;

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
