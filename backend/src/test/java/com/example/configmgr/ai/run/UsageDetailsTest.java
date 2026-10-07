package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-14 T8 用例：{@code done} 帧 usage 的可选维度（{@code cachedTokens} / {@code reasoningTokens}）。
 *
 * <p>
 * 官方 {@code Usage} 接口只保证三维度，缓存/推理维度在<b>各厂商的 native usage</b> 里，
 * 且命名不一致（OpenAI 兼容是嵌套 record，DeepSeek 顶层另有 {@code prompt_cache_hit_tokens}）。
 * 因此用三组形态各异的替身锁住"多路兜底 + 无则省略"两条契约。
 */
class UsageDetailsTest {

    /** OpenAI 兼容形态：{@code OpenAiApi.Usage}（record 访问器 + 嵌套 details）。 */
    record PromptDetails(Integer cachedTokens) {
    }

    record CompletionTokenDetails(Integer reasoningTokens) {
    }

    record OpenAiLikeUsage(PromptDetails promptTokensDetails, CompletionTokenDetails completionTokenDetails) {
    }

    /** 另一种命名（completionTokensDetails 带 s）：兜底路径必须也能命中。 */
    record AltCompletionDetails(Integer reasoningTokens) {
    }

    record AltUsage(PromptDetails promptTokensDetails, AltCompletionDetails completionTokensDetails) {
    }

    @Test
    void extractsCachedAndReasoningFromOpenAiLikeNativeUsage() {
        Map<String, Object> usage = usage();

        UsageDetails.enrich(usage, new OpenAiLikeUsage(new PromptDetails(896), new CompletionTokenDetails(128)));

        assertThat(usage).containsEntry("cachedTokens", 896).containsEntry("reasoningTokens", 128);
    }

    @Test
    void extractsFromAlternateNamingAndMapShape() {
        Map<String, Object> alt = usage();
        UsageDetails.enrich(alt, new AltUsage(new PromptDetails(12), new AltCompletionDetails(34)));
        assertThat(alt).containsEntry("cachedTokens", 12).containsEntry("reasoningTokens", 34);

        // native usage 本身是 Map（下划线命名）时同样可取
        Map<String, Object> asMap = usage();
        UsageDetails.enrich(asMap, Map.of(
                "prompt_tokens_details", Map.of("cached_tokens", 5),
                "completion_tokens_details", Map.of("reasoning_tokens", 6)));
        assertThat(asMap).containsEntry("cachedTokens", 5).containsEntry("reasoningTokens", 6);
    }

    @Test
    void omitsWhenAbsentAndNeverThrows() {
        Map<String, Object> missing = usage();
        UsageDetails.enrich(missing, new OpenAiLikeUsage(null, null));
        // 无则省略：键不存在（而不是写 null —— 展示层不该纠结"是 0 还是未知"）
        assertThat(missing).doesNotContainKeys("cachedTokens", "reasoningTokens");

        Map<String, Object> nullNative = usage();
        UsageDetails.enrich(nullNative, null);
        assertThat(nullNative).doesNotContainKeys("cachedTokens", "reasoningTokens");

        // 形态完全不符（字符串/异常 getter）也不外抛：用量维度是观测，不能影响轮次
        Map<String, Object> garbage = usage();
        UsageDetails.enrich(garbage, "not-a-usage");
        assertThat(garbage).containsOnlyKeys("promptTokens", "completionTokens", "totalTokens");
    }

    private static Map<String, Object> usage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", 1000);
        usage.put("completionTokens", 200);
        usage.put("totalTokens", 1200);
        return usage;
    }

}
