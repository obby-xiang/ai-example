package com.example.configmgr.ai.kb;

import java.time.Instant;

/**
 * 知识库状态（管理端点 {@code GET /api/ai/kb/status} 与测试共用）。
 *
 * @param enabled      总开关
 * @param ready        索引是否就绪（启动异步建索引；未就绪 = 工具 fail-open 回"加载中"）
 * @param source       知识源类型 local/http
 * @param version      manifest 版本
 * @param docCount     已入索引文档数
 * @param chunkCount   已入索引块数
 * @param builtAt      本份索引构建时刻
 * @param lastRefreshAt 最近一次刷新尝试时刻
 * @param lastError    最近一次刷新失败原因（成功即清空；不含鉴权/堆栈）
 */
public record KbStatus(boolean enabled, boolean ready, String source, String version,
        int docCount, int chunkCount, Instant builtAt, Instant lastRefreshAt, String lastError) {

    public static KbStatus disabled() {
        return new KbStatus(false, false, "local", "", 0, 0, null, null, "");
    }
}
