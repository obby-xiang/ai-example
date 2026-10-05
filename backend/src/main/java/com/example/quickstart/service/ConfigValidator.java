package com.example.quickstart.service;

import com.example.quickstart.dto.FieldDef;
import com.example.quickstart.dto.RowError;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置数据校验器：必填、NUMBER 可解析、DATE 格式 yyyy-MM-dd、ENUM 在 options 内、
 * BOOLEAN 为 true/false、maxLength、未知字段警告、ref 引用完整性。
 */
@Service
public class ConfigValidator {

    /** 作业明细逐行错误截断条数 */
    public static final int MAX_DETAIL = 100;

    private static final String WARNING_PREFIX = "警告：";
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * 逐行校验，返回错误/警告列表（≤{@link #MAX_DETAIL} 条）。
     * 警告以 "警告：" 前缀标识，不计入 errorRows。
     */
    public List<RowError> validate(List<FieldDef> fields,
                                   List<Map<String, Object>> rows,
                                   RefValueProvider refProvider) {
        List<RowError> errors = new ArrayList<>();
        Set<String> knownFields = new HashSet<>();
        for (FieldDef f : fields) {
            knownFields.add(f.name());
        }

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 1;
            Map<String, Object> row = rows.get(i) == null ? Map.of() : rows.get(i);

            // 未知字段警告（不计错误）
            for (String key : row.keySet()) {
                if (!knownFields.contains(key)) {
                    add(errors, new RowError(rowNo, key, WARNING_PREFIX + "未知字段，将被忽略"));
                }
            }

            for (FieldDef f : fields) {
                Object value = row.get(f.name());
                boolean present = value != null && !(value instanceof String s && s.isBlank());

                if (f.required() && !present) {
                    add(errors, new RowError(rowNo, f.name(), "必填字段不能为空"));
                    continue;
                }
                if (!present) {
                    continue;
                }
                String str = String.valueOf(value);
                String type = f.type() == null ? "STRING" : f.type().toUpperCase();
                switch (type) {
                    case "NUMBER" -> {
                        if (toNumber(value) == null) {
                            add(errors, new RowError(rowNo, f.name(), "应为数字: " + str));
                        }
                    }
                    case "DATE" -> {
                        try {
                            LocalDate.parse(str, STRICT_DATE);
                        } catch (Exception e) {
                            add(errors, new RowError(rowNo, f.name(), "日期格式应为 yyyy-MM-dd: " + str));
                        }
                    }
                    case "ENUM" -> {
                        if (f.options() != null && !f.options().isEmpty() && !f.options().contains(str)) {
                            add(errors, new RowError(rowNo, f.name(),
                                    "取值不在枚举选项 " + f.options() + " 内: " + str));
                        }
                    }
                    case "BOOLEAN" -> {
                        if (!(value instanceof Boolean)
                                && !"true".equalsIgnoreCase(str) && !"false".equalsIgnoreCase(str)) {
                            add(errors, new RowError(rowNo, f.name(), "应为 true/false: " + str));
                        }
                    }
                    default -> {
                        // STRING：无额外类型校验
                    }
                }
                if (f.maxLength() != null && str.length() > f.maxLength()) {
                    add(errors, new RowError(rowNo, f.name(),
                            "长度 " + str.length() + " 超过最大限制 " + f.maxLength()));
                }
                if (f.ref() != null && refProvider != null
                        && !refProvider.contains(f.ref().def(), f.ref().field(), str)) {
                    add(errors, new RowError(rowNo, f.name(),
                            "引用的值在 " + f.ref().def() + "." + f.ref().field() + " 中不存在: " + str));
                }
            }
        }
        return errors;
    }

    public boolean isWarning(RowError error) {
        return error.message() != null && error.message().startsWith(WARNING_PREFIX);
    }

    private void add(List<RowError> errors, RowError error) {
        if (errors.size() < MAX_DETAIL) {
            errors.add(error);
        }
    }

    private BigDecimal toNumber(Object o) {
        if (o instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
