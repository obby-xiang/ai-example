package com.example.configadmin.service;

import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.dto.Issue;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.Level;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 校验规则引擎：类型/必填/选项/数值范围/引用存在性/范围必填。
 * 导出查询条件、导入检查、发布检查共用同一套规则（单一事实来源）。
 */
@Component
public class ValidationEngine {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 校验并归一化一行数据。
     * @param def         配置定义（fields 需已解析）
     * @param row         原始行数据 Map<字段编码, Object>
     * @param knownRefs   引用值集合 Map<引用配置编码, Set<引用字段取值>>
     * @param rowIndex    Excel 行号（表头=1，数据从 2 开始；用于报错定位）
     * @return 归一化后的行（NUMBER->Double，BOOLEAN->Boolean，DATE->"yyyy-MM-dd"）
     */
    public RowValidation validateAndNormalize(ConfigDef def, List<FieldDef> fields, Map<String, Object> row,
                                              Map<String, Set<Object>> knownRefs, int rowIndex) {
        List<Issue> issues = new ArrayList<>();
        Map<String, Object> normalized = new LinkedHashMap<>();

        // 范围列校验（REGION/PROJECT 必填）
        if (def.getLevel() != Level.GLOBAL) {
            Object scope = row.get("__scope__");
            String scopeStr = scope == null ? null : String.valueOf(scope).trim();
            if (scopeStr == null || scopeStr.isEmpty()) {
                issues.add(new Issue("ERROR", rowIndex, "范围", "地区/项目层级的配置必须填写“范围”列", ""));
            } else {
                normalized.put("__scope__", scopeStr);
            }
        }

        for (FieldDef f : fields) {
            Object raw = row.get(f.getCode());
            String rawStr = raw == null ? null : String.valueOf(raw).trim();
            boolean blank = rawStr == null || rawStr.isEmpty();

            if (blank) {
                if (f.isRequired()) {
                    issues.add(new Issue("ERROR", rowIndex, f.getCode(), "必填字段“" + f.getLabel() + "”不能为空", ""));
                } else if (f.getDefaultValue() != null && !f.getDefaultValue().isEmpty()) {
                    normalized.put(f.getCode(), f.getDefaultValue());
                }
                continue;
            }

            try {
                switch (f.getType()) {
                    case TEXT, TEXTAREA, SELECT -> {
                        if (f.getType() == FieldDef.FieldType.SELECT && !f.getOptions().contains(rawStr)) {
                            issues.add(new Issue("ERROR", rowIndex, f.getCode(),
                                    "字段“" + f.getLabel() + "”取值[" + rawStr + "]不在可选范围内：" + String.join("/", f.getOptions()), rawStr));
                        }
                        normalized.put(f.getCode(), rawStr);
                    }
                    case NUMBER -> {
                        double d = Double.parseDouble(rawStr.replace(",", ""));
                        if (f.getMin() != null && d < f.getMin()) {
                            issues.add(new Issue("ERROR", rowIndex, f.getCode(), "字段“" + f.getLabel() + "”不能小于 " + f.getMin(), rawStr));
                        }
                        if (f.getMax() != null && d > f.getMax()) {
                            issues.add(new Issue("ERROR", rowIndex, f.getCode(), "字段“" + f.getLabel() + "”不能大于 " + f.getMax(), rawStr));
                        }
                        normalized.put(f.getCode(), d);
                    }
                    case BOOLEAN -> {
                        normalized.put(f.getCode(), parseBoolean(rawStr));
                    }
                    case DATE -> {
                        LocalDate date = parseDate(rawStr);
                        normalized.put(f.getCode(), date.format(DATE_FMT));
                    }
                    case REFERENCE -> {
                        if (!knownRefs.containsKey(f.getRefDefCode())) {
                            issues.add(new Issue("ERROR", rowIndex, f.getCode(),
                                    "引用配置 " + f.getRefDefCode() + " 不存在或无生效数据，无法校验引用", rawStr));
                        } else if (!knownRefs.get(f.getRefDefCode()).contains(rawStr)) {
                            issues.add(new Issue("ERROR", rowIndex, f.getCode(),
                                    "字段“" + f.getLabel() + "”取值[" + rawStr + "]在引用配置 "
                                            + f.getRefDefCode() + "." + f.getRefFieldCode() + " 中不存在", rawStr));
                        }
                        normalized.put(f.getCode(), rawStr);
                    }
                }
            } catch (NumberFormatException e) {
                issues.add(new Issue("ERROR", rowIndex, f.getCode(), "字段“" + f.getLabel() + "”必须是数字", rawStr));
            } catch (Exception e) {
                issues.add(new Issue("ERROR", rowIndex, f.getCode(),
                        "字段“" + f.getLabel() + "”格式错误（" + e.getMessage() + "）", rawStr));
            }
        }
        return new RowValidation(normalized, issues);
    }

    public boolean parseBoolean(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "是", "yes", "y", "真" -> true;
            case "false", "0", "否", "no", "n", "假" -> false;
            default -> throw new IllegalArgumentException("无法识别的布尔值：" + s);
        };
    }

    public LocalDate parseDate(String s) {
        String t = s.trim();
        for (DateTimeFormatter f : List.of(DATE_FMT, DATE_TIME_FMT)) {
            try {
                if (f == DATE_TIME_FMT) {
                    return LocalDateTime.parse(t, f).toLocalDate();
                }
                return LocalDate.parse(t, f);
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
    }

    /** 行校验结果。 */
    public record RowValidation(Map<String, Object> normalized, List<Issue> issues) {
        public boolean hasErrors() {
            return issues.stream().anyMatch(i -> "ERROR".equals(i.getLevel()));
        }
    }
}
