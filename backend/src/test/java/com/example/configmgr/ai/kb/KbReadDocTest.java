package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M8/M9/M10：read_doc 首屏、分页连续性、section 读取、候选、上限。
 */
class KbReadDocTest {

    private static final String LONG_DOC = "region-tariff-long";

    @Test
    void firstScreenReturnsTocAndAtMost1200Chars() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbReadResult result = service.readDoc(LONG_DOC, null, null, null);

        assertThat(result.state()).isEqualTo(KbReadResult.State.OK);
        assertThat(result.toc()).isNotEmpty();
        assertThat(result.toc().get(0)).contains("§").contains("offset=");
        assertThat(result.text().length()).isLessThanOrEqualTo(1200);
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextOffset()).isGreaterThanOrEqualTo(result.text().length() - 1);
    }

    @Test
    void paginationByNextOffsetReconstructsWholeDocument() throws Exception {
        KbService service = KbTestSupport.samplesService();
        String original = MarkdownChunker.normalize(
                Files.readString(KbTestSupport.samples().resolve("03-region-tariff-long.md"), StandardCharsets.UTF_8));

        StringBuilder assembled = new StringBuilder();
        int offset = 0;
        int pages = 0;
        while (true) {
            KbReadResult page = service.readDoc(LONG_DOC, null, offset, null);
            assertThat(page.state()).isEqualTo(KbReadResult.State.OK);
            assembled.append(page.text());
            pages++;
            assertThat(pages).isLessThan(20);
            if (!page.truncated()) {
                break;
            }
            assertThat(page.nextOffset()).isGreaterThan(offset);
            offset = page.nextOffset();
        }

        assertThat(pages).isGreaterThanOrEqualTo(3);
        assertThat(assembled.toString()).isEqualTo(original);
    }

    @Test
    void maxCharsIsCappedAt8000() throws Exception {
        String longBody = "# 上限文档\n## 正文\n" + "用于验证分页上限的知识正文。".repeat(600) + "\n";
        Path dir = KbTestSupport.corpus("cap", KbTestSupport.mapOf("cap", longBody));
        KbService service = KbTestSupport.service(dir, KbTestSupport.tempDir("kb-cap-idx-"));

        KbReadResult result = service.readDoc("cap", null, 100, 99999);

        assertThat(result.state()).isEqualTo(KbReadResult.State.OK);
        assertThat(result.text().length()).isLessThanOrEqualTo(8000);
        assertThat(result.truncated()).isTrue();
        assertThat(result.nextOffset()).isEqualTo(100 + result.text().length());
    }

    @Test
    void sectionReadStartsAtSectionBody() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbReadResult result = service.readDoc("region-tariff", "区域费率管理口径（样例） > 1 定义", null, null);

        assertThat(result.state()).isEqualTo(KbReadResult.State.OK);
        assertThat(result.section()).contains("1 定义");
        assertThat(result.text()).contains("区域费率（REGION_TARIFF）");
    }

    @Test
    void unknownDocReturnsCandidatesInsteadOfThrowing() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbReadResult result = service.readDoc("region-tarif", null, null, null);

        assertThat(result.state()).isEqualTo(KbReadResult.State.DOC_NOT_FOUND);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().get(0)).contains("region-tariff");
    }

    @Test
    void unknownSectionReturnsSectionCandidates() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbReadResult result = service.readDoc("region-tariff", "不存在的章节", null, null);

        assertThat(result.state()).isEqualTo(KbReadResult.State.SECTION_NOT_FOUND);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().get(0)).contains("区域费率管理口径（样例）");
    }

    @Test
    void offsetBeyondEndReportsEmptyPageWithoutError() throws Exception {
        KbService service = KbTestSupport.samplesService();

        KbReadResult result = service.readDoc("currency-scope", null, 999999, 1000);

        assertThat(result.state()).isEqualTo(KbReadResult.State.OK);
        assertThat(result.text()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.nextOffset()).isEqualTo(-1);
    }
}
