package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M11/M12：术语精确/别名命中、归一化、未命中不模糊兜底。
 */
class KbLookupTermTest {

    @Test
    void exactTermReturnsAnswerWithSource() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbLookupResult result = service.lookup("标准费率");

        assertThat(result.found()).isTrue();
        assertThat(result.answer()).contains("base_rate");
        assertThat(result.docId()).isEqualTo("region-tariff");
        assertThat(result.section()).contains("1 定义");
        assertThat(result.updatedAt()).isEqualTo("2026-09-01");
    }

    @Test
    void aliasTermHitsTheSameAnswer() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbLookupResult result = service.lookup("基准费率");

        assertThat(result.found()).isTrue();
        assertThat(result.docId()).isEqualTo("region-tariff");
        assertThat(result.answer()).contains("base_rate");
    }

    @Test
    void normalizationIgnoresWhitespaceCaseAndFullWidth() throws Exception {
        KbService service = KbTestSupport.samplesService();

        assertThat(service.lookup("  基准费率 ").found()).isTrue();
        assertThat(service.lookup("ＧＬＭ 种子域").found()).isTrue();
        KbLookupResult result = service.lookup("ｇｌｍ　种子域");
        assertThat(result.found()).isTrue();
        assertThat(result.normalized()).isEqualTo("glm 种子域");
    }

    @Test
    void nearTermIsNotFuzzyMatched() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbLookupResult prefix = service.lookup("标准费");
        KbLookupResult homophone = service.lookup("标准费用");

        assertThat(prefix.found()).isFalse();
        assertThat(homophone.found()).isFalse();
        assertThat(prefix.normalized()).isEqualTo("标准费");
    }

    @Test
    void missSuggestsSearchAsNextStep() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbLookupResult miss = service.lookup("完全不存在的外部术语");

        assertThat(miss.found()).isFalse();
        assertThat(miss.answer()).isEmpty();
    }

    @Test
    void documentAliasResolvesToDocLevelAnswer() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbLookupResult result = service.lookup("费率表");

        assertThat(result.found()).isTrue();
        assertThat(result.docId()).isEqualTo("region-tariff");
        assertThat(result.answer()).isNotBlank();
    }
}
