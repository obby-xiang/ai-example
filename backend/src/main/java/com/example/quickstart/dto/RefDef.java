package com.example.quickstart.dto;

/** 跨配置项引用：取值必须存在于 def 配置项的 field 字段中 */
public record RefDef(String def, String field) {
}
