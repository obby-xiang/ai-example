package com.example.configmgr.data.service;

import lombok.Data;

import java.util.List;

/**
 * 导出/查询条件。scopeKeys 用于地区/项目级范围过滤，fields 用于字段级过滤。
 */
@Data
public class QueryCondition {

    /** 范围过滤：地区或项目编码列表（GLOBAL 级配置忽略） */
    private List<String> scopeKeys;

    /** 字段级过滤条件（AND 关系） */
    private List<FieldCondition> fields;

    @Data
    public static class FieldCondition {
        private String fieldCode;
        /** EQ / NE / CONTAINS（=LIKE）/ STARTS_WITH / IN / EMPTY / NOT_EMPTY / GT / GTE / LT / LTE */
        private String operator;
        /** 单个值；IN 时为字符串列表 */
        private Object value;
    }
}
