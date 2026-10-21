package com.example.configmgr.ai.kb;

import java.util.List;

/**
 * 一条检索命中（已去重到文档级：同一文档多块命中只保留最高分的那块）。
 *
 * <p><b>没有分数字段是刻意的</b>：定稿要求"原始 BM25 分不给模型"，只有后端配置阈值派生的
 * {@code match}（high/medium/low）。类型层不给分数，渲染层也就无从泄漏。
 *
 * @param docId     文档 id
 * @param title     标题
 * @param section   命中块所属章节路径
 * @param snippet   摘要（窗口围绕命中词）
 * @param updatedAt 更新时间
 * @param match     命中档 high/medium/low
 */
public record KbSearchHit(String docId, String title, String section, String snippet,
        String updatedAt, String match) {

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";
}
