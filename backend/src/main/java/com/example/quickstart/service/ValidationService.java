package com.example.quickstart.service;

import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.dto.CatalogDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验引擎：检查/导入/发布共用的规则 V1-V8。
 * 输入：任务内各配置项的上传数据（内存行）+ 目录。
 */
@Service
@RequiredArgsConstructor
public class ValidationService {

    private final CatalogService catalogService;
    private final ConfigDataService dataService;

    public record Issue(String level, String row, String field, String message) {
    }

    public record ConfigCheck(String configCode, String configName, boolean hasData, int rowCount,
                              List<Issue> issues, int errorCount, int warnCount) {
    }

    public record CheckResult(List<ConfigCheck> configs, int totalErrors, int totalWarnings,
                              boolean hasError, List<String> messages) {
    }

    @SuppressWarnings("unchecked")
    public CheckResult validate(Map<String, Object> uploads) {
        List<CatalogDTO.ConfigItem> catalog = catalogService.listConfigs();
        Map<String, CatalogDTO.ConfigItem> byCode = new LinkedHashMap<>();
        for (CatalogDTO.ConfigItem c : catalog) {
            byCode.put(c.getCode(), c);
        }
        List<ConfigCheck> checks = new ArrayList<>();
        int totalErrors = 0;
        int totalWarnings = 0;
        List<String> messages = new ArrayList<>();

        for (Map.Entry<String, Object> e : uploads.entrySet()) {
            String code = e.getKey();
            CatalogDTO.ConfigItem item = byCode.get(code);
            if (item == null) {
                continue;
            }
            UploadData data = toUploadData(e.getValue());
            List<Issue> issues = new ArrayList<>();
            int rowCount = data.rows.size();
            boolean hasData = rowCount > 0;

            // V1-V5：逐行结构校验
            Map<String, Integer> keyCounter = new LinkedHashMap<>();
            for (int i = 0; i < data.rows.size(); i++) {
                Map<String, Object> row = data.rows.get(i);
                String rowNo = String.valueOf(i + 2);
                for (CatalogDTO.FieldMeta f : item.getFields()) {
                    Object v = row.get(f.getCode());
                    String text = v == null ? "" : String.valueOf(v).trim();
                    if (f.isRequired() && text.isEmpty()) {
                        issues.add(new Issue("ERROR", rowNo, f.getName(), "必填字段为空"));
                        continue;
                    }
                    if (text.isEmpty()) {
                        continue;
                    }
                    switch (f.getDataType()) {
                        case "INT" -> {
                            if (!text.matches("-?\\d+")) {
                                issues.add(new Issue("ERROR", rowNo, f.getName(), "应为整数，实际：" + text));
                            }
                        }
                        case "DECIMAL" -> {
                            try {
                                new java.math.BigDecimal(text);
                            } catch (NumberFormatException ex) {
                                issues.add(new Issue("ERROR", rowNo, f.getName(), "应为数值，实际：" + text));
                            }
                        }
                        case "DATE" -> {
                            try {
                                LocalDate.parse(text, ConfigDataService.DTF);
                            } catch (Exception ex) {
                                issues.add(new Issue("ERROR", rowNo, f.getName(), "日期格式应为 yyyy-MM-dd，实际：" + text));
                            }
                        }
                        case "BOOL" -> {
                            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                                issues.add(new Issue("ERROR", rowNo, f.getName(), "应为 true/false，实际：" + text));
                            }
                        }
                        case "ENUM", "SCOPE" -> {
                            List<String> opts = f.getOptions() == null ? List.of() : f.getOptions();
                            String normalized = normalizeScope(text);
                            boolean hit = opts.stream().anyMatch(o -> normalizeScope(o).equals(normalized));
                            if (!hit) {
                                issues.add(new Issue("ERROR", rowNo, f.getName(),
                                        "取值不在可选范围：" + text + "（可选：" + String.join("、", opts) + "）"));
                            }
                        }
                        default -> {
                        }
                    }
                }
                List<String> keyFields = item.getFields().stream()
                        .filter(CatalogDTO.FieldMeta::isKey).map(CatalogDTO.FieldMeta::getCode).toList();
                if (!keyFields.isEmpty()) {
                    String joined = joinKey(row, keyFields);
                    keyCounter.merge(joined, 1, Integer::sum);
                }
            }

            // V4：业务键重复
            for (Map.Entry<String, Integer> kc : keyCounter.entrySet()) {
                if (kc.getValue() > 1) {
                    issues.add(new Issue("ERROR", "-", "业务键", "业务键重复 " + kc.getValue() + " 次：" + kc.getKey()));
                }
            }

            // V7：空数据
            if (!hasData) {
                issues.add(new Issue("WARNING", "-", "-", "未上传数据（该配置项将被清空或跳过）"));
            }

            int errCount = (int) issues.stream().filter(i -> "ERROR".equals(i.level())).count();
            int warnCount = (int) issues.stream().filter(i -> "WARNING".equals(i.level())).count();
            totalErrors += errCount;
            totalWarnings += warnCount;
            checks.add(new ConfigCheck(code, item.getName(), hasData, rowCount, issues, errCount, warnCount));
        }

        // V6：跨配置依赖（在全部配置项结构校验后统一做）
        for (Map.Entry<String, Object> e : uploads.entrySet()) {
            CatalogDTO.ConfigItem item = byCode.get(e.getKey());
            if (item == null || item.getDependsOn() == null || item.getDependsOn().isEmpty()) {
                continue;
            }
            UploadData data = toUploadData(e.getValue());
            ConfigCheck basic = checks.stream().filter(c -> c.configCode().equals(e.getKey())).findFirst().orElse(null);
            if (basic == null || !basic.hasData()) {
                continue;
            }
            for (CatalogDTO.Dependency dep : item.getDependsOn()) {
                Set<String> refKeys = new HashSet<>();
                Object refUpload = uploads.get(dep.def());
                if (refUpload != null) {
                    UploadData refData = toUploadData(refUpload);
                    for (Map<String, Object> r : refData.rows) {
                        Object v = r.get(dep.refField());
                        if (v != null && !String.valueOf(v).trim().isEmpty()) {
                            refKeys.add(String.valueOf(v).trim());
                        }
                    }
                }
                if (refKeys.isEmpty()) {
                    refKeys.addAll(dataService.publishedKeySet(dep.def()));
                }
                for (int i = 0; i < data.rows.size(); i++) {
                    Object v = data.rows.get(i).get(dep.field());
                    String text = v == null ? "" : String.valueOf(v).trim();
                    if (!text.isEmpty() && !refKeys.contains(text)) {
                        String rowNo = String.valueOf(i + 2);
                        String fieldName = item.getFields().stream()
                                .filter(f -> f.getCode().equals(dep.field()))
                                .map(CatalogDTO.FieldMeta::getName).findFirst().orElse(dep.field());
                        addIssue(checks, e.getKey(), new Issue("ERROR", rowNo, fieldName,
                                "依赖校验失败：" + text + " 在配置项 " + dep.def() + "（" + dep.label()
                                        + "）的上传数据与已发布数据中均不存在，请先在依赖配置项中补充"));
                        totalErrors++;
                    }
                }
            }
        }

        if (totalErrors == 0 && totalWarnings == 0 && checks.stream().noneMatch(ConfigCheck::hasData)) {
            messages.add("所有配置项均无数据");
        }
        if (totalErrors > 0) {
            messages.add("存在 " + totalErrors + " 个错误，必须修复后才能导入");
        }
        if (totalWarnings > 0) {
            messages.add("存在 " + totalWarnings + " 个警告（不影响导入）");
        }
        return new CheckResult(checks, totalErrors, totalWarnings, totalErrors > 0, messages);
    }

    private void addIssue(List<ConfigCheck> checks, String code, Issue issue) {
        for (int i = 0; i < checks.size(); i++) {
            ConfigCheck c = checks.get(i);
            if (c.configCode().equals(code)) {
                List<Issue> issues = new ArrayList<>(c.issues());
                issues.add(issue);
                checks.set(i, new ConfigCheck(c.configCode(), c.configName(), c.hasData(), c.rowCount(),
                        issues, c.errorCount() + 1, c.warnCount()));
                return;
            }
        }
    }

    private String joinKey(Map<String, Object> row, List<String> keyFields) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyFields) {
            Object v = row.get(k);
            sb.append(v == null ? "" : String.valueOf(v).trim()).append(" ");
        }
        return sb.toString().trim();
    }

    /** 范围值归一化：允许 "CODE|名称" 或纯 CODE */
    private String normalizeScope(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf('|');
        return idx > 0 ? s.substring(0, idx) : s.trim();
    }

    public static UploadData toUploadData(Object value) {
        if (value instanceof UploadData u) {
            return u;
        }
        if (value instanceof Map<?, ?> m) {
            String fileName = m.get("fileName") == null ? "" : String.valueOf(m.get("fileName"));
            Object rowsObj = m.get("rows");
            List<Map<String, Object>> rows = rowsObj == null ? List.of()
                    : JsonUtil.mapper().convertValue(rowsObj,
                            new TypeReference<List<Map<String, Object>>>() {
                            });
            return new UploadData(fileName, rows);
        }
        return new UploadData("", List.of());
    }

    public record UploadData(String fileName, List<Map<String, Object>> rows) {
    }
}
