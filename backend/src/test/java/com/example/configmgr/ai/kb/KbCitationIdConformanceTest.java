package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用的可读性契约（E2E 用例全集第 53 件 · K-09 的后端一半）。
 *
 * <p>蓝本判据：引用为 {@code [doc_id §章节]} 形态、{@code doc_id} 为**可读 slug**、不渲染原始 JSON。
 * 为什么必须新增：{@code KbDisclosurePromptTest} 已锁"提示词里有 {@code [doc_id §章节]} 这个格式串"，
 * 但**没有任何用例锁 {@code doc_id} 本身长什么样** —— 一旦 id 退化成哈希/序号（或某篇样例被改成
 * 数字名），模型给出的引用就会变成 {@code [d41d8cd9 §…]} 这种读者无法回查的东西，而提示词断言依旧全绿。
 *
 * <p>kill：把某篇样例的 {@code id} 改成 32 位哈希或纯数字，本类变红。
 */
class KbCitationIdConformanceTest {

    /** 可读 slug：小写字母开头，允许小写字母/数字/连字符（与仓内样例命名一致）。 */
    private static final Pattern READABLE_SLUG = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    /**
     * 真实示例形态：{@code [<slug> §<章节路径>]}。
     *
     * <p>占位形态 {@code [doc_id §章节]} 因下划线不匹配 slug 而被排除 —— 本模式取到的就是
     * "必须可被工具返回"的那一条。
     */
    private static final Pattern CITATION_EXAMPLE = Pattern.compile("\\[([a-z][a-z0-9-]*) §([^\\]]+)]");

    /**
     * 判据①：目录里每一篇的 {@code id} 都是可读 slug（不含哈希形态、不含下划线/大写、不为纯数字）。
     */
    @Test
    void everyCatalogDocIdIsAReadableSlug() throws Exception {
        KbService service = KbTestSupport.samplesService();

        List<KbDocMeta> catalog = service.catalog();

        assertThat(catalog).hasSizeGreaterThanOrEqualTo(10);
        for (KbDocMeta meta : catalog) {
            assertThat(meta.id()).as("doc_id 必须是可读 slug：%s", meta.id()).matches(READABLE_SLUG);
            assertThat(meta.id()).doesNotMatch("[0-9a-f]{32}.*");
            assertThat(meta.id()).isNotBlank();
            assertThat(meta.title()).as("引用里要带中文原标题：%s", meta.id()).isNotBlank();
        }
    }

    /**
     * 判据②：检索命中的 {@code docId} 与 {@code section} 都是**可直接写进引用的形态** ——
     * docId 落在目录里（可回查），section 非空且不含原始 JSON 标点（{@code {}[]}、引号）。
     */
    @Test
    void searchHitsCarryACatalogDocIdAndAPlainSectionPath() throws Exception {
        KbService service = KbTestSupport.samplesService();
        List<String> catalogIds = service.catalog().stream().map(KbDocMeta::id).toList();

        KbSearchResult result = service.search("区域费率", 5);

        assertThat(result.hits()).as("样例语料里必须有命中").isNotEmpty();
        for (KbSearchHit hit : result.hits()) {
            assertThat(catalogIds).as("命中 doc_id 必须能在目录里回查：%s", hit.docId()).contains(hit.docId());
            assertThat(hit.section()).isNotBlank();
            assertThat(hit.section()).doesNotContain("{").doesNotContain("[").doesNotContain("\"");
            // 渲染层契约：不给原始分数字段（K-01 已锁工具输出，这里锁模型可见的命中对象本身）
            assertThat(hit.match()).isIn(KbSearchHit.HIGH, KbSearchHit.MEDIUM, KbSearchHit.LOW);
        }
    }

    /**
     * 判据③（A3-Y2）：提示词里的引用**示例**必须是"真实 doc_id + 真实章节路径 + 中文原标题"。
     *
     * <p>修复前的示例是 {@code [region-tariff §区域费率管理口径 > 计算规则 · 区域费率管理口径]}：
     * {@code region-tariff} 没有"计算规则"章（它在另一篇 {@code region-tariff-long} 里），
     * 末尾还拼了一段多余标题。KB 规则 4 要求"只引用工具实际返回过的内容"，示例却示范了一个
     * 工具永远返回不出来的形状 —— 模型逐字模仿示例，就会产出不存在的章节路径。
     */
    @Test
    void promptCitationExampleUsesARealDocId() throws Exception {
        KbService service = KbTestSupport.samplesService();
        String prompt = new KbPromptSection(service).render();

        List<String> catalogIds = service.catalog().stream().map(KbDocMeta::id).toList();
        Citation example = citationExample(prompt);

        assertThat(catalogIds).as("示例 doc_id 必须是真实文档：%s", example.docId()).contains(example.docId());
        assertThat(example.section()).as("示例必须给完整章节路径（文档标题 > 二级 > …），不是裸小节名")
                .contains(" > ");
    }

    /**
     * 判据④（A3-Y2 的"完整路径可检索"）：示例的 doc_id + 章节路径必须能被
     * {@code kb_read_doc} 按原样解析（OK，且命中的就是这条路径）。
     */
    @Test
    void promptCitationExampleSectionResolvesThroughKbReadDoc() throws Exception {
        KbService service = KbTestSupport.samplesService();
        Citation example = citationExample(new KbPromptSection(service).render());

        KbReadResult read = service.readDoc(example.docId(), example.section(), null, 2000);

        assertThat(read.state()).as("示例章节必须可读：%s", example.section()).isEqualTo(KbReadResult.State.OK);
        assertThat(read.section()).as("解析到的章节路径必须与示例逐字相同（否则模型照抄的是近似路径）")
                .isEqualTo(example.section());
        assertThat(read.text()).as("读到的是该章正文（不是空页）").contains("区域费率");
    }

    /**
     * 判据⑤（A3-Y2）：示例章节必须出现在同一篇文档的首屏目录里 —— 那是模型学习"章节路径长什么样"
     * 的唯一来源；示例与目录不一致，模型就会把两篇文档的字段拼着抄。
     */
    @Test
    void promptCitationExampleSectionAppearsInTheDocumentToc() throws Exception {
        KbService service = KbTestSupport.samplesService();
        Citation example = citationExample(new KbPromptSection(service).render());

        KbReadResult firstScreen = service.readDoc(example.docId(), null, null, 2000);

        assertThat(firstScreen.state()).isEqualTo(KbReadResult.State.OK);
        assertThat(firstScreen.toc()).as("首屏目录里必须有示例里的完整章节路径：%s", example.section())
                .anyMatch(line -> line.contains("[§" + example.section() + "]"));
    }

    /**
     * 判据⑥（A3-Y2）：示例后附的中文原标题必须是目录里的真实标题（不得自造或漏掉后缀）。
     */
    @Test
    void promptCitationExampleCarriesTheRealChineseTitle() throws Exception {
        KbService service = KbTestSupport.samplesService();
        String prompt = new KbPromptSection(service).render();
        Citation example = citationExample(prompt);

        String title = service.catalog().stream().filter(meta -> meta.id().equals(example.docId()))
                .findFirst().orElseThrow().title();

        assertThat(prompt).as("示例必须紧随真实中文标题：%s", title).contains("（" + title + "）");
        assertThat(example.section()).as("章节路径以文档标题开头（与切块口径一致）").startsWith(title + " > ");
    }

    /**
     * 判据⑦（A3-Y2）：修复前那个"跨文档拼接"的示例不得回归。
     */
    @Test
    void promptNoLongerSuggestsANonExistentSectionCombination() throws Exception {
        String prompt = new KbPromptSection(KbTestSupport.samplesService()).render();

        assertThat(prompt).as("旧示例示范的\"计算规则\"章不在 region-tariff 里")
                .doesNotContain("计算规则 · 区域费率管理口径")
                .doesNotContain("§区域费率管理口径 >");
    }

    /**
     * 判据⑧（A3-Y1，用户裁决 4B）：KB 规则 8 不再另立一套语言政策 ——
     * 不再出现与主提示相反的"回答按用户所用语言生成"，改为统一指向系统提示的语言要求。
     */
    @Test
    void promptLanguageRulesDeferToTheSystemLanguageRequirement() throws Exception {
        String prompt = new KbPromptSection(KbTestSupport.samplesService()).render();

        assertThat(prompt).as("反口径必须消失（它与主提示规则 1 直接相反）")
                .doesNotContain("回答按用户所用语言生成");
        assertThat(prompt).as("语言政策只声明一处：KB 规则指向系统提示的本轮语言段")
                .contains("回答语言遵循系统提示")
                .contains("回答语言（本轮）")
                .contains("此处不另行规定");
        assertThat(prompt).as("规则 8 的保留部分：英文检索桥接 + 引用锚定中文标题")
                .contains("aliases_en")
                .contains("引用仍锚定中文 doc_id §章节")
                .contains("附中文原标题");
    }

    /** 从渲染后的提示词里取第一条真实示例（占位形态 {@code [doc_id §章节]} 的下划线不匹配 slug）。 */
    private static Citation citationExample(String prompt) {
        java.util.regex.Matcher matcher = CITATION_EXAMPLE.matcher(prompt);
        assertThat(matcher.find()).as("提示词必须含 [doc_id §章节] 形态的示例").isTrue();
        return new Citation(matcher.group(1), matcher.group(2).strip());
    }

    private record Citation(String docId, String section) {
    }
}
