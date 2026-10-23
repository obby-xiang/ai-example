package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-87：{@code kb_read_doc} 的页大小必须与模型侧 {@link ToolResultLimiter} <b>同一口径</b> ——
 * 一页要么整页可见（含 {@code </knowledge>} 与 {@code next_offset} 尾巴），要么把页缩小；
 * 绝不能出现"限流器按 200 行切掉页尾、模型按 next_offset 跳读漏段"。
 *
 * <p>证红形态：短行文档的一页超过 200 行 ⇒ 限流器 head 截断 ⇒ 结构尾巴丢失。
 */
class KbPagingBudgetTest {

    private static final String TOOL = "kb_read_doc";

    private static final String SECTION = "明细";

    private static final String BODY_MARK = "--- 正文 ---\n";

    private static final Pattern NEXT_OFFSET = Pattern.compile("next_offset=(\\d+)");

    private static ToolResultLimiter limiter() {
        return new ToolResultLimiter(new AiProperties());
    }

    /** 短行很多：8000 字符一页约 260 行，远超模型侧 200 行上限（真机实测形态）。 */
    private static String manyLineDoc() {
        StringBuilder body = new StringBuilder("# 多行文档\n## 明细\n");
        for (int i = 1; i <= 400; i++) {
            body.append("第 ").append(i).append(" 行：区域费率口径说明，用于验证分页不漏读。\n");
        }
        return body.toString();
    }

    private static KbService service() throws Exception {
        return KbTestSupport.service(
                KbTestSupport.corpus("many-lines", KbTestSupport.mapOf("many-lines", manyLineDoc())),
                KbTestSupport.tempDir("kb-many-idx-"));
    }

    /** 模型能看到的"正文"：{@code --- 正文 ---} 之后、分页尾行之前（渲染时补的那个换行不属于正文）。 */
    private static String pageText(String page) {
        int start = page.indexOf(BODY_MARK);
        int tail = page.lastIndexOf("\n[truncated=");
        assertThat(start).as("必须有正文标记").isGreaterThanOrEqualTo(0);
        assertThat(tail).as("必须有分页尾行").isGreaterThan(start);
        return page.substring(start + BODY_MARK.length(), tail);
    }

    @Test
    void aFullPageStaysUnderTheModelRowBudgetAndKeepsItsTail() throws Exception {
        KbTools tools = new KbTools(service());

        String page = tools.kbReadDoc("many-lines", SECTION, null, 8000);

        assertThat(page).contains("</knowledge>");
        assertThat(page).containsPattern("\\[truncated=(true|false)");
        assertThat(limiter().forModel(TOOL, page).truncated())
                .as("模型侧限流器不得再截断本页（截断即丢尾巴）")
                .isFalse();
    }

    @Test
    void paginationFromModelVisiblePagesCoversTheWholeDocumentWithoutGaps() throws Exception {
        KbService service = service();
        KbTools tools = new KbTools(service);
        String expected = service.document("many-lines").content();

        StringBuilder assembled = new StringBuilder();
        int offset = 0;
        for (int pageNo = 1; pageNo <= 30; pageNo++) {
            String visible = tools.kbReadDoc("many-lines", null, offset, 8000);
            assertThat(limiter().forModel(TOOL, visible).truncated())
                    .as("第 %d 页（offset=%d）不得被限流器截断", pageNo, offset)
                    .isFalse();
            assertThat(visible).as("第 %d 页必须保留结构尾巴", pageNo).endsWith("</knowledge>");

            String text = pageText(visible);
            assertThat(text).as("第 %d 页正文必须紧接 offset（不得跳读）", pageNo)
                    .isEqualTo(expected.substring(offset, offset + text.length()));
            assembled.append(text);
            if (visible.contains("[truncated=false")) {
                break;
            }
            Matcher matcher = NEXT_OFFSET.matcher(visible);
            assertThat(matcher.find()).as("truncated=true 必须给出 next_offset").isTrue();
            offset = Integer.parseInt(matcher.group(1));
        }

        assertThat(assembled.toString()).as("逐页拼接必须等于原文（零漏读）").isEqualTo(expected);
    }
}
