package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.kb.KbText;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共用定界符实现的表征用例：三个数据段（工作区 / 工具结果 / 知识）用同一份标签识别与中和机制。
 *
 * <p>锁三件事：空白/大小写/Unicode 分隔符的识别口径、中和的幂等性、以及信封用的等长形态
 * 不改变字符数（截断契约依赖这一点）。
 */
class PromptDelimitersTest {

	private static final Pattern TOOL_RESULT = PromptDelimiters.closingTags("tool_result");

	@Test
	void closingTagsTolerateCaseWhitespaceAndUnicodeSeparators() {
		assertThat(PromptDelimiters.neutralizeClosing("</tool_result>", TOOL_RESULT)).isEqualTo("＜/tool_result＞");
		assertThat(PromptDelimiters.neutralizeClosing("</TOOL_RESULT>", TOOL_RESULT)).isEqualTo("＜/TOOL_RESULT＞");
		assertThat(PromptDelimiters.neutralizeClosing("</\u00A0tool_result\u3000>", TOOL_RESULT))
			.isEqualTo("＜/tool_result＞");
		assertThat(PromptDelimiters.neutralizeClosing("</ tool_result >", TOOL_RESULT)).isEqualTo("＜/tool_result＞");
		assertThat(PromptDelimiters.neutralizeClosing("< / tool_result >", TOOL_RESULT)).isEqualTo("＜/tool_result＞");
	}

	@Test
	void canonicalNeutralizationMatchesContextBuilderOutput() {
		assertThat(PromptDelimiters.neutralizeClosing("</ workspace_state >", PromptDelimiters.closingTags(
				"workspace_state", "tool_result", "knowledge"))).isEqualTo("＜/workspace_state＞");
		assertThat(ContextBuilder.neutralizeClosingDelimiters("</\u2007workspace_state>")).isEqualTo("＜/workspace_state＞");
	}

	@Test
	void edgeNeutralizationPreservesLengthAndIsIdempotent() {
		String raw = "A</tool_result >B\u00A0</TOOL_RESULT>C";

		String once = PromptDelimiters.neutralizeTagEdges(raw, TOOL_RESULT);
		String twice = PromptDelimiters.neutralizeTagEdges(once, TOOL_RESULT);

		assertThat(once).hasSameSizeAs(raw).doesNotContain("</tool_result>").doesNotContain("</TOOL_RESULT>");
		assertThat(once).contains("A＜/tool_result ＞B").contains("＜/TOOL_RESULT＞C");
		assertThat(twice).isEqualTo(once);
	}

	@Test
	void knowledgeSanitizationKeepsBothDirectionsAndDoesNotShadowCatalogTags() {
		assertThat(KbText.sanitize("<knowledge>x</knowledge>")).isEqualTo("＜knowledge＞x＜knowledge＞");
		assertThat(KbText.sanitize("< / knowledge >")).isEqualTo("＜knowledge＞");
		assertThat(KbText.sanitize("<knowledge_catalog>y</knowledge_catalog>"))
			.isEqualTo("＜knowledge_catalog＞y＜knowledge_catalog＞");
		// 幂等：已是全角形态的正文不会被再次改写
		assertThat(KbText.sanitize("＜knowledge＞x＜knowledge＞")).isEqualTo("＜knowledge＞x＜knowledge＞");
	}

}
