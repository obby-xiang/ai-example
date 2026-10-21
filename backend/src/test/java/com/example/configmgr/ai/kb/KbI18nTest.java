package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追加 4 矩阵：aliases_en 桥接（英文查询命中中文语料）、提示词规则、样例覆盖。
 */
class KbI18nTest {

    @Test
    void englishQueryHitsChineseDocumentsThroughAliasesEn() throws Exception {
        KbService service = KbTestSupport.samplesService();

        assertThat(service.search("alarm threshold", 5).hits())
                .extracting(KbSearchHit::docId).contains("alarm-threshold");
        assertThat(service.search("project member", 5).hits())
                .extracting(KbSearchHit::docId).contains("project-member");
        assertThat(service.search("reimbursement rate", 5).hits())
                .extracting(KbSearchHit::docId).contains("region-tariff");
        assertThat(service.search("glm seed domains", 5).hits())
                .extracting(KbSearchHit::docId).contains("glm-seed");
    }

    @Test
    void atLeastThreeSamplesDeclareAliasesEn() throws Exception {
        KbSource.ManifestFetch fetch = new LocalDirKbSource(KbTestSupport.samples(), KbTestSupport.mapper())
                .fetchManifest(null);

        long withEnglish = fetch.manifest().docs().stream()
                .filter(doc -> !doc.aliasesEn().isEmpty()).count();
        assertThat(withEnglish).isGreaterThanOrEqualTo(3);
        assertThat(fetch.manifest().docs()).anySatisfy(doc -> {
            if ("alarm-threshold".equals(doc.id())) {
                assertThat(doc.aliasesEn()).contains("alarm threshold");
            }
        });
    }

    @Test
    void promptCarriesEnglishBridgeRule() throws Exception {
        String prompt = new KbPromptSection(KbTestSupport.samplesService()).render();

        assertThat(prompt).contains("用户用英文提问");
        assertThat(prompt).contains("aliases_en");
        assertThat(prompt).contains("中文 doc_id §章节");
        assertThat(prompt).contains("附中文原标题");
    }

    @Test
    void englishAliasesDoNotLeakIntoChineseAliasField() throws Exception {
        KbDocMeta doc = new LocalDirKbSource(KbTestSupport.samples(), KbTestSupport.mapper())
                .fetchManifest(null).manifest().docs().stream()
                .filter(item -> "region-tariff".equals(item.id())).findFirst().orElseThrow();

        assertThat(doc.aliases()).contains("费率表").doesNotContain("regional tariff");
        assertThat(doc.aliasesEn()).contains("regional tariff");
    }

    @Test
    void manifestJsonUsesAliasesEnKey() throws Exception {
        String json = Files.readString(Path.of(KbTestSupport.samples().toString(), "manifest.json"));
        assertThat(json).contains("\"aliases_en\"");
    }
}
