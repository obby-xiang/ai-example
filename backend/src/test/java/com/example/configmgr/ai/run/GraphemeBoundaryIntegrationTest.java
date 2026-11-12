package com.example.configmgr.ai.run;

import com.example.configmgr.ai.kb.KbText;
import com.example.configmgr.common.GraphemeText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphemeBoundaryIntegrationTest {

    private static final String PREFIX = "a";
    private static final String SUFFIX = "Z";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cjkKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("界", "界");
    }

    @Test
    void combiningAcuteKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("e\u0301", "e\u0301");
    }

    @Test
    void familyZwjKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66",
                "\uD83D\uDC68\uD83D\uDC69\uD83D\uDC67\uD83D\uDC66", 2);
    }

    @Test
    void skinToneKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("\uD83D\uDC4B\uD83C\uDFFD", "\uD83D\uDC4B\uD83C\uDFFD");
    }

    @Test
    void flagKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("\uD83C\uDDE8\uD83C\uDDF3", "\uD83C\uDDE8\uD83C\uDDF3");
    }

    @Test
    void keycapKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("1\uFE0F\u20E3", "1\uFE0F\u20E3");
    }

    @Test
    void tagEmojiKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
                "\uD83C\uDFF4");
    }

    @Test
    void crlfKeepsWholeGraphemesAndUtf16Offsets() {
        assertGrapheme("\r\n", "\n");
    }

    @Test
    void isolatedSurrogateKeepsOriginalUnitsAndUtf16Offsets() {
        assertGrapheme("\uD83D", "");
        assertThat(GraphemeText.followingBoundary("\uDC00", 0)).isEqualTo(1);
    }

    @Test
    void longCombiningGraphemeKeepsWholeSequenceAndFailsClosed() {
        String cluster = "e" + "\u0301".repeat(256);
        assertGrapheme(cluster, cluster);

        Map<String, Object> frame = skeleton(cluster + SUFFIX);
        int limit = skeletonBytes() + escaped(cluster) - 1;
        assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(frame, "text", this.mapper, limit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one grapheme")
                .hasMessageContaining("originalChars=" + (cluster.length() + 1));
    }

    private void assertGrapheme(String cluster, String sanitizedCluster) {
        assertGrapheme(cluster, sanitizedCluster, sanitizedCluster.length());
    }

    private void assertGrapheme(String cluster, String sanitizedCluster, int sanitizedGraphemeLength) {
        String original = PREFIX + cluster + SUFFIX;
        int clusterStart = PREFIX.length();
        int probe = cluster.length() == 1 ? clusterStart : clusterStart + 1;
        assertThat(GraphemeText.precedingBoundary(original, 0)).isZero();
        assertThat(GraphemeText.precedingBoundary(original, probe)).isEqualTo(clusterStart);
        assertThat(GraphemeText.followingBoundary(original, 0)).isEqualTo(1);
        assertThat(GraphemeText.followingBoundary(original, probe)).isEqualTo(clusterStart + cluster.length());

        assertFrameChunks(cluster, original);
        assertKbText(cluster, sanitizedCluster, sanitizedGraphemeLength);
    }

    private void assertFrameChunks(String cluster, String original) {
        Map<String, Object> frame = skeleton(original);
        int limit = skeletonBytes() + escaped(PREFIX + cluster) - 1;

        List<String> chunks = FrameSizeLimiter.splitStringValue(frame, "text", this.mapper, limit);

        assertThat(chunks).containsExactly(PREFIX, cluster, SUFFIX);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).isNotEmpty();
            Map<String, Object> candidate = skeleton(chunk);
            assertThat(this.mapper.writeValueAsBytes(candidate).length).isLessThanOrEqualTo(limit);
        });
        assertThat(String.join("", chunks)).isEqualTo(original);
        assertThat(frame.get("text")).isEqualTo(original);
        assertThat(frame.get("seq")).isEqualTo(Long.MAX_VALUE);
    }

    private void assertKbText(String cluster, String sanitizedCluster, int sanitizedGraphemeLength) {
        assertThat(KbText.sanitize(cluster)).isEqualTo(sanitizedCluster);
        if (sanitizedCluster.isEmpty()) {
            assertThat(KbText.snippet(PREFIX + cluster + SUFFIX, List.of(), 2)).isEqualTo("aZ");
            assertThat(KbText.plain(PREFIX + cluster + SUFFIX)).isEqualTo("aZ");
            assertThat(KbText.wrap(PREFIX + cluster + SUFFIX)).contains("aZ");
            return;
        }

        String text = PREFIX + sanitizedCluster + SUFFIX;
        int snippetEnd = sanitizedCluster.length() == 1 ? 2 : 1;
        String expectedWindow = text.substring(0, snippetEnd).strip();
        assertThat(KbText.snippet(text, List.of(), 2)).isEqualTo(expectedWindow + "…");
        assertThat(KbText.snippet(text, List.of("Z"), 2)).isEqualTo("…Z");

        String body = PREFIX + sanitizedCluster.repeat(KbText.MAX_RESULT_CHARS);
        int wrapEnd = repeatedPrefixEnd(KbText.bodyBudget(), sanitizedGraphemeLength);
        String marker = "\n…[知识结果已截断：原 " + body.length() + " 字符，仅前 " + wrapEnd + " 字符]";
        String expectedWrap = KbText.OPEN + "\n" + KbText.NOTICE + "\n"
                + body.substring(0, wrapEnd) + marker + "\n" + KbText.CLOSE;
        assertThat(KbText.wrap(body)).isEqualTo(expectedWrap);
        assertThat(KbText.canFit(body)).isFalse();
        assertThat(KbText.canFit(body.substring(0, wrapEnd))).isTrue();

        int plainEnd = repeatedPrefixEnd(KbText.MAX_RESULT_CHARS - 48, sanitizedGraphemeLength);
        assertThat(KbText.plain(body)).isEqualTo(body.substring(0, plainEnd)
                + "\n…[知识结果已截断：原 " + body.length() + " 字符]");
    }

    private int repeatedPrefixEnd(int budget, int clusterLength) {
        return 1 + (budget - 1) / clusterLength * clusterLength;
    }

    private Map<String, Object> skeleton(String text) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "delta");
        frame.put("atMs", 0L);
        frame.put("messageId", "assistant-grapheme");
        frame.put("text", text);
        frame.put("runId", "run-grapheme");
        frame.put("seq", Long.MAX_VALUE);
        return frame;
    }

    private int skeletonBytes() {
        try {
            return this.mapper.writeValueAsBytes(skeleton("")).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int escaped(String text) {
        try {
            return this.mapper.writeValueAsBytes(text).length - 2;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
