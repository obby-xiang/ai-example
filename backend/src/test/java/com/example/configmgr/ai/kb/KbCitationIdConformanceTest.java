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
     * 判据③：提示词里的引用**示例**用的是真实存在的 doc_id（否则模型会照抄一个查不到的 id）。
     */
    @Test
    void promptCitationExampleUsesARealDocId() throws Exception {
        KbService service = KbTestSupport.samplesService();
        String prompt = new KbPromptSection(service).render();

        List<String> catalogIds = service.catalog().stream().map(KbDocMeta::id).toList();
        java.util.regex.Matcher matcher = Pattern.compile("\\[([a-z][a-z0-9-]*) §").matcher(prompt);

        assertThat(matcher.find()).as("提示词必须含 [doc_id §章节] 形态的示例").isTrue();
        String exampleId = matcher.group(1);
        assertThat(catalogIds).as("示例 doc_id 必须是真实文档：%s", exampleId).contains(exampleId);
    }
}
