package com.example.configmgr.ai.tool;

import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DC-14 T9 用例：{@code extra} 注入收敛（白名单 + 序列化截断）。
 *
 * <p>
 * {@code extra} 是请求体里唯一的自由字段，此前整张 Map 直接进系统消息 —— 一个几万字符的 extra
 * 会静默吃掉整轮上下文。本用例锁两条：非白名单键<b>不进</b>系统消息；超长值被截断，
 * 且"被省略"这件事对模型可见（不静默消失）。
 */
class ContextBuilderExtraTest {

    private final ContextBuilder builder = new ContextBuilder(mock(TaskService.class), new ObjectMapper());

    @Test
    void onlyWhitelistedKeysAreInjected() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("pageId", "export");
        extra.put("selectedDefs", List.of("CURRENCY", "TAX_RATE"));
        extra.put("unknownKey", "客户端临时塞的键");
        extra.put("hugeBlob", "x".repeat(50_000));

        String rendered = this.builder.renderExtra(extra);

        assertThat(rendered).contains("pageId=export").contains("selectedDefs=[\"CURRENCY\",\"TAX_RATE\"]");
        assertThat(rendered).doesNotContain("unknownKey").doesNotContain("客户端临时塞的键");
        assertThat(rendered).doesNotContain("hugeBlob");
    }

    @Test
    void longValuesAreTruncatedWithVisibleMarker() {
        Map<String, Object> extra = Map.of("pageId", "export", "recentActions", List.of("y".repeat(2_000)));

        String rendered = this.builder.renderExtra(extra);

        assertThat(rendered).contains("…");
        assertThat(rendered.length()).isLessThan(ContextBuilder.EXTRA_TOTAL_MAX_CHARS + 200);
    }

    @Test
    void totalBudgetDropsRemainingKeysAndSaysSo() {
        Map<String, Object> extra = new LinkedHashMap<>();
        for (String key : ContextBuilder.ALLOWED_EXTRA_KEYS) {
            extra.put(key, "z".repeat(ContextBuilder.EXTRA_VALUE_MAX_CHARS));
        }

        String rendered = this.builder.renderExtra(extra);

        assertThat(rendered).contains("被省略");
        // 第一个键（pageId）一定在；被丢掉的键不再出现
        assertThat(rendered).contains("pageId=");
        assertThat(rendered).doesNotContain("recentActions=");
    }

    @Test
    void emptyExtraAddsNoSection() {
        assertThat(this.builder.renderExtra(null)).isEmpty();
        assertThat(this.builder.renderExtra(new LinkedHashMap<>())).isEmpty();
        assertThat(this.builder.renderExtra(Map.of("unknownKey", "v"))).isEmpty();

        String message = this.builder.buildContextMessage(AiContext.of("tasks", null, null, null, Map.of()));
        assertThat(message).contains("页面：任务中心（tasks）").doesNotContain("额外上下文");
    }

    @Test
    void pageLabelMirrorsFrontendLabels() {
        // 与 frontend/src/stores/workspace.ts#PAGE_LABELS 逐项镜像：中文名 + 括号里的裸 id
        assertThat(ContextBuilder.pageLabel("tasks")).isEqualTo("任务中心（tasks）");
        assertThat(ContextBuilder.pageLabel("export")).isEqualTo("导出向导（export）");
        assertThat(ContextBuilder.pageLabel("import")).isEqualTo("导入向导（import）");
        assertThat(ContextBuilder.pageLabel("definitions")).isEqualTo("配置定义（definitions）");
        assertThat(ContextBuilder.pageLabel("data")).isEqualTo("数据浏览（data）");
        // 空/未上报 → 既有回落文案（不带 id）；未知 id → 原样回显
        assertThat(ContextBuilder.pageLabel(null)).isEqualTo("任务中心");
        assertThat(ContextBuilder.pageLabel("  ")).isEqualTo("任务中心");
        assertThat(ContextBuilder.pageLabel("reports")).isEqualTo("reports");
    }

    @Test
    void whitelistMirrorsFrontendContract() {
        // 与 frontend/src/stores/workspace.ts#buildContext 的 extra 键**逐键对应**（多/少都算契约漂移）
        assertThat(new ArrayList<>(ContextBuilder.ALLOWED_EXTRA_KEYS))
                .containsExactly("pageId", "selectedDefs", "importMode", "dataVersion", "contractVersion",
                        "recentActions");
    }

}
