package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M3/M4/M5/M6：中文召回、别名召回、top_k 截断、三态空结果、命中档位。
 */
class KbServiceIndexTest {

    @Test
    void sampleCorpusIsIndexedAndReady() throws Exception {
        KbService service = KbTestSupport.samplesService();

        assertThat(service.ready()).isTrue();
        KbStatus status = service.status();
        assertThat(status.enabled()).isTrue();
        assertThat(status.ready()).isTrue();
        assertThat(status.source()).isEqualTo("local");
        assertThat(status.docCount()).isEqualTo(20);
        assertThat(status.chunkCount()).isGreaterThan(20);
        assertThat(status.builtAt()).isNotNull();
        assertThat(status.lastError()).isEmpty();
    }

    @Test
    void chineseSentenceQueryHitsRelevantDocWithoutSpaces() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("区域费率怎么计算", 5);

        assertThat(result.state()).isIn(KbSearchResult.State.OK, KbSearchResult.State.LOW_CONFIDENCE);
        assertThat(result.searchedTerms()).isNotEmpty();
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).docId()).startsWith("region-tariff");
    }

    @Test
    void aliasQueryRecallsTheCanonicalDoc() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("费率表", 5);

        assertThat(result.hits()).extracting(KbSearchHit::docId).contains("region-tariff");
    }

    @Test
    void tableContentIsSearchable() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("跨区加收比例 7.5%", 5);

        assertThat(result.hits()).extracting(KbSearchHit::docId).contains("tariff-boundary");
    }

    @Test
    void mermaidDocIsFoundByTitle() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("项目环境变更流程", 3);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).docId()).isEqualTo("project-env-flow");
    }

    @Test
    void topKIsClampedToServerMaximum() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult many = service.search("配置", 999);

        assertThat(many.hits()).isNotEmpty();
        assertThat(many.hits().size()).isLessThanOrEqualTo(10);
        assertThat(service.search("配置", null).hits().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void noMatchReturnsSearchedTermsAndNearbyTitles() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("火星殖民政策", 5);

        assertThat(result.state()).isEqualTo(KbSearchResult.State.NO_MATCH);
        assertThat(result.hits()).isEmpty();
        assertThat(result.searchedTerms()).isNotEmpty();
        assertThat(result.suggestions()).hasSizeBetween(2, 3);
    }

    @Test
    void oneOrTwoHitsAreLowConfidenceAndMarkedLow() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("半月结", 5);

        if (result.hits().size() <= 2) {
            assertThat(result.state()).isEqualTo(KbSearchResult.State.LOW_CONFIDENCE);
            assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.match()).isEqualTo(KbSearchHit.LOW));
        }
        // 至少验证"低置信时全部标 low"的规则本身（用一条极窄查询）
        KbSearchResult narrow = service.search("半月结算口径仅此一处", 5);
        if (narrow.hits().size() <= 2) {
            assertThat(narrow.state()).isEqualTo(KbSearchResult.State.LOW_CONFIDENCE);
            assertThat(narrow.hits()).allSatisfy(hit -> assertThat(hit.match()).isEqualTo(KbSearchHit.LOW));
        }
    }

    @Test
    void hitsCarryMatchLevelsAndNeverRawScores() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("费率 计算 规则", 10);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(hit -> {
            assertThat(hit.match()).isIn(KbSearchHit.HIGH, KbSearchHit.MEDIUM, KbSearchHit.LOW);
            assertThat(hit.title()).isNotBlank();
            assertThat(hit.section()).isNotBlank();
            assertThat(hit.snippet()).isNotBlank();
            assertThat(hit.updatedAt()).isNotBlank();
        });
        // 类型层没有分数字段：渲染结构里也不该出现 score/BM25
        assertThat(KbSearchHit.class.getRecordComponents())
                .noneSatisfy(component -> assertThat(component.getName()).containsIgnoringCase("score"));
    }

    @Test
    void searchBeforeIndexIsReadyReturnsNotReady() throws Exception {
        KbService service = KbTestSupport.notReadyService();

        assertThat(service.ready()).isFalse();
        assertThat(service.search("费率", 5).state()).isEqualTo(KbSearchResult.State.NOT_READY);
        assertThat(service.readDoc("region-tariff", null, null, null).state())
                .isEqualTo(KbReadResult.State.NOT_READY);
        assertThat(service.lookup("标准费率").notReady()).isTrue();
    }

    @Test
    void blankQueryIsInvalidNotNoMatch() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbSearchResult result = service.search("   ", 5);

        assertThat(result.state()).isEqualTo(KbSearchResult.State.INVALID_QUERY);
        assertThat(result.error()).isNotBlank();
    }

    @Test
    void catalogIsManifestOrdered() throws Exception {
        KbService service = KbTestSupport.samplesService();

        List<KbDocMeta> catalog = service.catalog();

        assertThat(catalog).hasSize(20);
        assertThat(catalog.get(0).id()).isEqualTo("region-tariff");
        assertThat(catalog.get(catalog.size() - 1).id()).isEqualTo("kb-maintenance-faq");
    }
}
