package com.example.quickstart.service;

import com.example.quickstart.dto.FieldDef;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ref 引用完整性校验的数据源：正式区数据 + 本作业中排在前面的配置项数据。
 * <p>
 * UNION 模式（检查/导入）：前置配置项的行与正式区取并集（前置数据尚未写库）。
 * REPLACE 模式（发布）：已发布配置项的新数据全量替换正式区取值。
 */
public class RefValueProvider {

    public enum Mode { UNION, REPLACE }

    private final ConfigDefService configDefService;
    private final Mode mode;
    private final Map<String, Map<String, Set<String>>> overlay = new HashMap<>();
    private final Set<String> overlaidDefs = new HashSet<>();
    private final Map<String, Set<String>> formalCache = new HashMap<>();

    public RefValueProvider(ConfigDefService configDefService, Mode mode) {
        this.configDefService = configDefService;
        this.mode = mode;
    }

    /** 登记一个配置项的行数据（该配置项处理完成后调用，供后续配置项 ref 校验） */
    public void overlayRows(String defCode, List<FieldDef> fields, List<Map<String, Object>> rows) {
        Map<String, Set<String>> byField = mode == Mode.REPLACE
                ? new HashMap<>()
                : overlay.computeIfAbsent(defCode, k -> new HashMap<>());
        for (FieldDef f : fields) {
            Set<String> values = byField.computeIfAbsent(f.name(), k -> new LinkedHashSet<>());
            for (Map<String, Object> row : rows) {
                Object v = row.get(f.name());
                if (v != null && !String.valueOf(v).isBlank()) {
                    values.add(String.valueOf(v));
                }
            }
        }
        overlay.put(defCode, byField);
        overlaidDefs.add(defCode);
    }

    public boolean contains(String defCode, String field, String value) {
        Map<String, Set<String>> byField = overlay.get(defCode);
        if (mode == Mode.REPLACE && overlaidDefs.contains(defCode)) {
            return byField != null
                    && byField.getOrDefault(field, Set.of()).contains(value);
        }
        if (byField != null && byField.getOrDefault(field, Set.of()).contains(value)) {
            return true;
        }
        return formalValues(defCode, field).contains(value);
    }

    private Set<String> formalValues(String defCode, String field) {
        return formalCache.computeIfAbsent(defCode + "#" + field,
                k -> configDefService.formalFieldValues(defCode, field));
    }
}
