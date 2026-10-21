package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M1/M2：切块边界、路径前缀、代码块/表格原样、深层标题。
 */
class MarkdownChunkerTest {

    private static final int MIN = 300;

    private static final int MAX = 800;

    @Test
    void splitsBySectionAndKeepsChunksWithinMax() {
        StringBuilder body = new StringBuilder("# 区域费率口径\n\n## 计算规则\n\n");
        for (int i = 0; i < 12; i++) {
            body.append("第").append(i).append("段：费率按业务量与档位系数计算，金额只在最终步骤取整。")
                    .append("这一段用于填充长度，确保切块逻辑有足够内容可以合并与切分。\n\n");
        }
        body.append("## 审批流程\n\n提交变更单后由配置管理员发布，发布前必须通过预检查。\n");

        List<KbChunk> chunks = MarkdownChunker.chunk("doc-1", "区域费率口径", body.toString(), MIN, MAX);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.body().length()).isLessThanOrEqualTo(MAX);
            assertThat(chunk.sectionPath()).startsWith("区域费率口径");
            assertThat(chunk.indexText()).startsWith(chunk.sectionPath() + "\n");
        });
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.sectionPath()).isEqualTo("区域费率口径 > 计算规则"));
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.sectionPath()).isEqualTo("区域费率口径 > 审批流程"));
    }

    @Test
    void deepHeadingsProduceNestedPath() {
        String content = """
                # 总览
                ## 二级章节
                ### 三级章节
                #### 四级章节
                深层内容：口径的最小单位在这里定义。
                """;

        List<KbChunk> chunks = MarkdownChunker.chunk("doc-deep", "总览", content, MIN, MAX);

        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.sectionPath()).isEqualTo("总览 > 二级章节 > 三级章节 > 四级章节"));
    }

    @Test
    void mermaidAndCodeBlocksAreKeptVerbatimAndNotSplit() {
        String content = """
                # 流程图文档
                ## 流程图
                ```mermaid
                flowchart TD
                    A[开始] --> B{判断}
                    B -- 是 --> C[结束]

                    B -- 否 --> D[继续]
                ```
                正文说明。
                ## 代码
                ```java
                int a = 1;

                int b = 2;
                ```
                """;

        List<KbChunk> chunks = MarkdownChunker.chunk("doc-flow", "流程图文档", content, MIN, MAX);

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.body()).contains("```mermaid").contains("flowchart TD").contains("B -- 否 --> D[继续]");
            assertThat(chunk.body()).contains("```");
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.body()).contains("int a = 1;").contains("int b = 2;"));
        // 围栏内的 # 不是标题：不产生 "流程图文档 > 开始" 之类的伪章节
        assertThat(chunks).noneSatisfy(chunk -> assertThat(chunk.sectionPath()).contains("开始"));
    }

    @Test
    void wideTableIsSplitByRowsWithHeaderRepeated() {
        StringBuilder table = new StringBuilder("| 区域 | 币种 | A | B | C | 比例 | 生效 | 失效 | 最小 | 最大 | 周期 | 审批 | 备注 |\n");
        table.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < 8; i++) {
            table.append("| R-").append(i).append(" | CNY | 1.00 | 1.05 | 1.10 | 5.0% | 2026-01-01 | 2026-12-31 | 0 | 1000 | 月结 | 区域 | 演示行 |\n");
        }
        String content = "# 宽表\n## 参数矩阵\n" + table;

        List<KbChunk> chunks = MarkdownChunker.chunk("doc-table", "宽表", content, MIN, MAX);

        List<KbChunk> tableChunks = chunks.stream()
                .filter(chunk -> chunk.body().stripLeading().startsWith("|")).toList();
        assertThat(tableChunks).isNotEmpty();
        assertThat(tableChunks).allSatisfy(chunk -> {
            assertThat(chunk.body()).contains("| 区域 | 币种 | A | B | C | 比例 | 生效 | 失效 | 最小 | 最大 | 周期 | 审批 | 备注 |");
            assertThat(chunk.body().length()).isLessThanOrEqualTo(MAX);
        });
        String merged = String.join("\n", tableChunks.stream().map(KbChunk::body).toList());
        for (int i = 0; i < 8; i++) {
            assertThat(merged).contains("| R-" + i + " |");
        }
    }

    @Test
    void longParagraphIsHardSplitWithinMax() {
        String longText = "费率口径说明".repeat(400);
        String content = "# 长段落\n## 大段\n" + longText + "\n";

        List<KbChunk> chunks = MarkdownChunker.chunk("doc-long", "长段落", content, MIN, MAX);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.body().length()).isLessThanOrEqualTo(MAX));
        assertThat(String.join("", chunks.stream().map(KbChunk::body).toList())).contains("费率口径说明");
    }

    @Test
    void sectionsExposeOffsetsAndBody() {
        String content = "# 标题\n\n## 第一章\n第一章正文。\n### 子节\n子节正文。\n## 第二章\n第二章正文。\n";

        List<KbSection> sections = MarkdownChunker.sections(content, "标题");

        assertThat(sections).extracting(KbSection::title).containsExactly("第一章", "子节", "第二章");
        assertThat(sections).allSatisfy(section -> {
            assertThat(section.startOffset()).isGreaterThanOrEqualTo(0);
            assertThat(section.bodyOffset()).isGreaterThan(section.startOffset());
            assertThat(section.endOffset()).isGreaterThan(section.bodyOffset());
        });
        KbSection first = sections.get(0);
        assertThat(first.body(content)).contains("第一章正文");
        KbSection second = sections.get(1);
        assertThat(second.path()).isEqualTo("标题 > 第一章 > 子节");
    }
}
