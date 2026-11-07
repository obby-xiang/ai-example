package com.example.configmgr.ai.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NonNull;

import java.util.List;

/**
 * {@code GET /api/ai/providers} 与批② 写端点的单行视图（模型配置专项 §3.5）。
 *
 * <h2>拒绝面（硬约束）</h2>
 * 这里<b>只有</b> {@code keyConfigured}/{@code keyLast4}/{@code keyRef}/{@code keyStatus} 四件套：
 * 无 {@code apiKey}、无 {@code keyCipher}、无 {@code keyFingerprint} —— 明文与密文都不出现在
 * 任何响应体里（§3.2 泄漏面第 2 条；B4 与 M7 的用例按"精确键集"断言，加回 cipher 字段即红）。
 *
 * @param keyConfigured 是否声明了 key（{@code key_ref != NONE}）；<b>不</b>等于"本进程能解开"
 * @param keyLast4 只展示末 4 位（{@code null} = 未配置）
 * @param keyRef DB / ENV / NONE
 * @param keyStatus 运行时结论：OK / MISSING / UNREADABLE / DISABLED（{@code KEY_UNREADABLE} 的展示位）
 * @param source ENV_SEED / DB（设置页"来源"标签）
 */
public record ProviderView(@JsonProperty("id") @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        @JsonProperty("code") String code, @JsonProperty("displayName") String displayName,
        @JsonProperty("protocol") String protocol, @JsonIgnore String baseUrl,
        @JsonProperty("completionsPath") String completionsPath, @JsonProperty("modelsPath") String modelsPath,
        @JsonProperty("keyConfigured") boolean keyConfigured, @JsonProperty("keyLast4") String keyLast4,
        @JsonProperty("keyRef") String keyRef, @JsonProperty("keyStatus") String keyStatus,
        @JsonProperty("source") String source, @JsonProperty("enabled") boolean enabled,
        @JsonProperty("models") @NonNull List<ModelView> models) {

    public ProviderView {
        models = models == null ? List.of() : List.copyOf(models);
    }

    @Override
    public String toString() {
        return "ProviderView[id=" + this.id + ", code=" + this.code + ", displayName=" + this.displayName
                + ", protocol=" + this.protocol + ", keyConfigured=" + this.keyConfigured + ", keyRef=" + this.keyRef
                + ", keyStatus=" + this.keyStatus + ", source=" + this.source + ", enabled=" + this.enabled + "]";
    }
}
