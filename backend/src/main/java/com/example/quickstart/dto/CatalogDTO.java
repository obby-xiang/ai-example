package com.example.quickstart.dto;

import lombok.Data;

import java.util.List;

public class CatalogDTO {

    @Data
    public static class ConfigItem {
        private String code;
        private String name;
        private String level;
        private String description;
        private long rowCount;
        private List<FieldMeta> fields;
        private List<Dependency> dependsOn;
    }

    @Data
    public static class FieldMeta {
        private String code;
        private String name;
        private String dataType;
        private boolean required;
        private boolean key;
        private String sampleValue;
        private List<String> options;
    }

    public record Dependency(String def, String field, String refField, String label) {
    }

    public record ScopeItem(String scopeType, String code, String name) {
    }
}
