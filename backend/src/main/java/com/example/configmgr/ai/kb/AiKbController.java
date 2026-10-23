package com.example.configmgr.ai.kb;

import com.example.configmgr.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理端点（定稿 §2 的"手动热重载"；运营面，只读 + 一次显式刷新）。
 *
 * <ul>
 * <li>{@code GET /api/ai/kb/status}：索引状态（是否就绪/文档数/块数/构建时刻/最近错误）；</li>
 * <li>{@code POST /api/ai/kb/reload}：<b>同步</b>重建 —— 在索引目录的 tmp 子目录建新索引，
 * 成功后原子切换 reader，失败保留旧索引（返回 {@code rebuilt=false} + 原因）；</li>
 * <li>{@code GET /api/ai/kb/docs}：manifest 目录（id/title/tags/updated_at/sha 前缀），
 * 排障时确认"线上到底加载了哪些文档"。</li>
 * </ul>
 *
 * <p>不提供知识内容的写接口：知识文档由运维按 {@code docs/知识库维护手册.md} 的流程发布
 * （tmp+rename 原子写入 + sha 校验），应用侧只读。
 *
 * <p><b>暴露口径（L-59，DC-10）</b>：本控制器无认证 —— 任意可达客户端可 {@code POST /api/ai/kb/reload}
 * 强制重建索引（CPU/IO 放大、HTTP 知识源额外回源；不泄露知识内容）。收口方式 = 后端默认只绑回环
 * （{@code server.address=127.0.0.1}）+ 生产网关前置鉴权；<b>残余面</b> = 同机多用户（回环不区分
 * 本机用户/进程），与 L-30 / I-1 同族、同批处理（回归锁见 {@code config/Dc10ExposureSurfaceTest}）。
 */
@RestController
@RequestMapping("/api/ai/kb")
public class AiKbController {

    private final KbService kbService;

    public AiKbController(KbService kbService) {
        this.kbService = kbService;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(toMap(this.kbService.status()));
    }

    @PostMapping("/reload")
    public ApiResponse<Map<String, Object>> reload() {
        boolean rebuilt = this.kbService.refreshNow(true);
        Map<String, Object> data = toMap(this.kbService.status());
        data.put("rebuilt", rebuilt);
        return ApiResponse.ok(data);
    }

    @GetMapping("/docs")
    public ApiResponse<List<Map<String, Object>>> docs() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (KbDocMeta doc : this.kbService.catalog()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.id());
            item.put("title", doc.title());
            item.put("tags", doc.tags());
            item.put("updated_at", doc.updatedAt());
            item.put("sha", doc.sha() == null || doc.sha().length() <= 12
                    ? doc.sha() : doc.sha().substring(0, 12) + "…");
            items.add(item);
        }
        return ApiResponse.ok(items);
    }

    private static Map<String, Object> toMap(KbStatus status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", status.enabled());
        data.put("ready", status.ready());
        data.put("source", status.source());
        data.put("version", status.version());
        data.put("doc_count", status.docCount());
        data.put("chunk_count", status.chunkCount());
        data.put("built_at", status.builtAt() == null ? null : status.builtAt().toString());
        data.put("last_refresh_at", status.lastRefreshAt() == null ? null : status.lastRefreshAt().toString());
        data.put("last_error", status.lastError());
        return data;
    }
}
