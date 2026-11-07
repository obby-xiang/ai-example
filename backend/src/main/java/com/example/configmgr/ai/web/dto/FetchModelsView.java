package com.example.configmgr.ai.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NonNull;

import java.util.List;

/**
 * {@code POST /api/ai/providers/{id}/models/fetch} 的结果（设计稿 §3.5）。
 *
 * <p>
 * {@code models} 是上游<b>现在</b>报告的 id 列表（不落库、只给勾选）；{@code cached=true} 表示本次
 * 没打上游而是用了 Redis 缓存（TTL 10min）。失败时同样 200 + {@code ok=false} + 稳定 {@code code}。
 */
public record FetchModelsView(@JsonProperty("ok") boolean ok, @JsonProperty("models") @NonNull List<String> models,
        @JsonProperty("cached") boolean cached, @JsonProperty("code") String code,
        @JsonProperty("message") String message) {

    public FetchModelsView {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
