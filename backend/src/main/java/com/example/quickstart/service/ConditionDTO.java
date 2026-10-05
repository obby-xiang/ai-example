package com.example.quickstart.service;

import lombok.Data;

import java.util.List;

public class ConditionDTO {

    @Data
    public static class Condition {
        private String field;
        private String op;
        private Object value;
        private List<Object> values;
    }

    /** 每配置项条件集合 */
    @Data
    public static class PerConfig {
        private String configCode;
        private List<Condition> conditions;
    }
}
