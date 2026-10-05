package com.example.quickstart.service;

import com.example.quickstart.common.BizException;
import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDef;
import com.example.quickstart.entity.ConfigField;
import com.example.quickstart.repository.ConfigDataRepository;
import com.example.quickstart.repository.ConfigDefRepository;
import com.example.quickstart.repository.ConfigFieldRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配置数据服务：查询条件引擎（按数据类型支持不同操作符）+ 数据读写。
 */
@Service
public class ConfigDataService {

    public static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ConfigDefRepository defRepo;
    private final ConfigFieldRepository fieldRepo;
    private final ConfigDataRepository dataRepo;
    private final CatalogService catalogService;

    public ConfigDataService(ConfigDefRepository defRepo, ConfigFieldRepository fieldRepo,
                             ConfigDataRepository dataRepo, CatalogService catalogService) {
        this.defRepo = defRepo;
        this.fieldRepo = fieldRepo;
        this.dataRepo = dataRepo;
        this.catalogService = catalogService;
    }

    @Transactional(readOnly = true)
    public ConfigDef requireDef(String code) {
        return defRepo.findByCode(code)
                .orElseThrow(() -> new BizException("配置项不存在：" + code));
    }

    @Transactional(readOnly = true)
    public List<ConfigField> requireFields(Long defId) {
        List<ConfigField> fields = fieldRepo.findByDefIdOrderBySortNoAscIdAsc(defId);
        if (fields.isEmpty()) {
            throw new BizException("配置项字段定义为空，defId=" + defId);
        }
        return fields;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> queryPublished(String configCode, List<ConditionDTO.Condition> conditions) {
        ConfigDef def = requireDef(configCode);
        List<ConfigField> fields = requireFields(def.getId());
        Map<String, ConfigField> fieldByCode = byCode(fields);
        validateConditions(conditions, fieldByCode);
        List<ConfigData> all = dataRepo.findByDefIdAndStatusOrderByIdAsc(def.getId(), "PUBLISHED");
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConfigData d : all) {
            Map<String, Object> row = rowOf(d, fields);
            if (match(row, conditions, fieldByCode)) {
                result.add(row);
            }
        }
        return result;
    }

    private Map<String, ConfigField> byCode(List<ConfigField> fields) {
        Map<String, ConfigField> m = new LinkedHashMap<>();
        for (ConfigField f : fields) {
            m.put(f.getFieldCode(), f);
        }
        return m;
    }

    public Map<String, Object> rowOf(ConfigData d, List<ConfigField> fields) {
        Map<String, Object> raw = JsonUtil.read(d.getFieldValues(),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        Map<String, Object> row = new LinkedHashMap<>();
        for (ConfigField f : fields) {
            Object v = raw.get(f.getFieldCode());
            row.put(f.getFieldCode(), v == null ? "" : v);
        }
        row.put("__scope", d.getScopeValue());
        row.put("__id", d.getId());
        return row;
    }

    private void validateConditions(List<ConditionDTO.Condition> conditions, Map<String, ConfigField> fieldByCode) {
        if (conditions == null) {
            return;
        }
        for (ConditionDTO.Condition c : conditions) {
            ConfigField f = fieldByCode.get(c.getField());
            if (f == null) {
                throw new BizException("查询条件字段不存在：" + c.getField());
            }
            if (!operatorsFor(f.getDataType()).contains(c.getOp())) {
                throw new BizException("字段 " + f.getFieldCode() + "（" + f.getDataType()
                        + "）不支持操作符 " + c.getOp());
            }
        }
    }

    public static List<String> operatorsFor(String dataType) {
        return switch (dataType) {
            case "TEXT" -> List.of("EQ", "NE", "CONTAINS", "IN");
            case "INT", "DECIMAL", "DATE" -> List.of("EQ", "NE", "GT", "LT", "BETWEEN");
            case "BOOL", "ENUM", "SCOPE" -> List.of("EQ", "NE");
            default -> List.of("EQ", "NE", "CONTAINS");
        };
    }

    private boolean match(Map<String, Object> row, List<ConditionDTO.Condition> conditions,
                          Map<String, ConfigField> fieldByCode) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (ConditionDTO.Condition c : conditions) {
            ConfigField f = fieldByCode.get(c.getField());
            Object cell = row.get(c.getField());
            if (!matchOne(cell, c, f)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchOne(Object cell, ConditionDTO.Condition c, ConfigField f) {
        String type = f.getDataType();
        String op = c.getOp();
        String value = c.getValue() == null ? "" : String.valueOf(c.getValue());
        String text = cell == null ? "" : String.valueOf(cell);
        try {
            switch (type) {
                case "INT", "DECIMAL" -> {
                    BigDecimal cellNum = new BigDecimal(text);
                    List<BigDecimal> vals = parseNums(c);
                    return switch (op) {
                        case "EQ" -> cellNum.compareTo(vals.get(0)) == 0;
                        case "NE" -> cellNum.compareTo(vals.get(0)) != 0;
                        case "GT" -> cellNum.compareTo(vals.get(0)) > 0;
                        case "LT" -> cellNum.compareTo(vals.get(0)) < 0;
                        case "BETWEEN" -> cellNum.compareTo(vals.get(0)) >= 0
                                && cellNum.compareTo(vals.get(1)) <= 0;
                        default -> false;
                    };
                }
                case "DATE" -> {
                    LocalDate cellDate = LocalDate.parse(text, DTF);
                    LocalDate v0 = LocalDate.parse(vals(c).get(0), DTF);
                    LocalDate v1 = op.equals("BETWEEN") ? LocalDate.parse(vals(c).get(1), DTF) : null;
                    return switch (op) {
                        case "EQ" -> cellDate.isEqual(v0);
                        case "NE" -> !cellDate.isEqual(v0);
                        case "GT" -> cellDate.isAfter(v0);
                        case "LT" -> cellDate.isBefore(v0);
                        case "BETWEEN" -> !cellDate.isBefore(v0) && !cellDate.isAfter(v1);
                        default -> false;
                    };
                }
                case "BOOL" -> {
                    boolean cellB = Boolean.parseBoolean(text);
                    return switch (op) {
                        case "EQ" -> cellB == Boolean.parseBoolean(value);
                        case "NE" -> cellB != Boolean.parseBoolean(value);
                        default -> false;
                    };
                }
                case "ENUM", "SCOPE" -> {
                    return switch (op) {
                        case "EQ" -> text.equals(value);
                        case "NE" -> !text.equals(value);
                        default -> false;
                    };
                }
                default -> {
                    return switch (op) {
                        case "EQ" -> text.equals(value);
                        case "NE" -> !text.equals(value);
                        case "CONTAINS" -> text.contains(value);
                        case "IN" -> {
                            List<String> opts = c.getValues() == null ? List.of()
                                    : c.getValues().stream().map(String::valueOf).toList();
                            yield opts.contains(text);
                        }
                        default -> false;
                    };
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private List<BigDecimal> parseNums(ConditionDTO.Condition c) {
        List<String> vs = vals(c);
        if (vs.isEmpty()) {
            throw new BizException("数值条件缺少比较值");
        }
        List<BigDecimal> nums = new ArrayList<>();
        try {
            nums.add(new BigDecimal(vs.get(0)));
            if ("BETWEEN".equals(c.getOp())) {
                nums.add(new BigDecimal(vs.get(1)));
            }
        } catch (NumberFormatException e) {
            throw new BizException("数值条件的值格式不正确：" + vs);
        }
        return nums;
    }

    private List<String> vals(ConditionDTO.Condition c) {
        List<String> vs = new ArrayList<>();
        if (c.getValue() != null) {
            vs.add(String.valueOf(c.getValue()));
        }
        if (c.getValues() != null) {
            c.getValues().forEach(v -> vs.add(String.valueOf(v)));
        }
        if (vs.isEmpty()) {
            throw new BizException("查询条件缺少比较值：" + c.getField());
        }
        return vs;
    }

    /** 供导入链路使用：某配置项已发布数据的业务键集合 */
    @Transactional(readOnly = true)
    public Set<String> publishedKeySet(String configCode) {
        ConfigDef def = requireDef(configCode);
        List<ConfigField> fields = requireFields(def.getId());
        List<String> keyFields = fields.stream().filter(f -> Boolean.TRUE.equals(f.getIsKey()))
                .map(ConfigField::getFieldCode).toList();
        Set<String> keys = new HashSet<>();
        for (ConfigData d : dataRepo.findByDefIdAndStatusOrderByIdAsc(def.getId(), "PUBLISHED")) {
            Map<String, Object> raw = JsonUtil.read(d.getFieldValues(),
                    new TypeReference<Map<String, Object>>() {
                    });
            keys.add(joinKeys(raw, keyFields));
        }
        return keys;
    }

    public static String joinKeys(Map<String, Object> raw, List<String> keyFields) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyFields) {
            Object v = raw.get(k);
            sb.append(v == null ? "" : String.valueOf(v).trim()).append(" ");
        }
        return sb.toString().trim();
    }

    @Transactional
    public void replaceStagedForScope(Long defId, String taskId, String scopeValue,
                                      List<Map<String, Object>> rows, List<ConfigField> fields) {
        if (scopeValue == null) {
            dataRepo.deleteByDefIdAndStatusAndTaskId(defId, "STAGED", taskId);
        } else {
            dataRepo.deleteByDefIdAndStatusAndScopeValue(defId, "STAGED", scopeValue);
        }
        saveRows(defId, taskId, rows, fields);
    }

    @Transactional
    public void saveRows(Long defId, String taskId, List<Map<String, Object>> rows, List<ConfigField> fields) {
        ConfigDef def = defRepo.findById(defId).orElseThrow();
        List<String> fieldOrder = fields.stream().map(ConfigField::getFieldCode).toList();
        ConfigField scopeField = fields.stream()
                .filter(f -> "SCOPE".equals(f.getDataType())).findFirst().orElse(null);
        List<ConfigData> batch = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ConfigData d = new ConfigData();
            d.setDefId(defId);
            if (scopeField != null) {
                Object sv = row.get(scopeField.getFieldCode());
                d.setScopeLevel(def.getLevel());
                d.setScopeValue(sv == null ? null : String.valueOf(sv));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (String fc : fieldOrder) {
                Object v = row.get(fc);
                values.put(fc, v == null ? "" : v);
            }
            d.setFieldValues(JsonUtil.write(values));
            d.setStatus("STAGED");
            d.setTaskId(taskId);
            d.setCreatedAt(java.time.LocalDateTime.now());
            d.setUpdatedAt(java.time.LocalDateTime.now());
            batch.add(d);
        }
        dataRepo.saveAll(batch);
    }

    @Transactional(readOnly = true)
    public List<ConfigData> stagedRows(String taskId, Long defId) {
        return dataRepo.findByDefIdAndStatusOrderByIdAsc(defId, "STAGED").stream()
                .filter(d -> Objects.equals(d.getTaskId(), taskId))
                .collect(Collectors.toList());
    }

    /** 发布（事务内）：按配置项+适用范围替换 PUBLISHED，STAGED 行提升为 PUBLISHED */
    @Transactional
    public void publishStaged(Long defId, List<ConfigData> staged) {
        Map<String, List<ConfigData>> byScope = new LinkedHashMap<>();
        for (ConfigData d : staged) {
            byScope.computeIfAbsent(d.getScopeValue() == null ? "" : d.getScopeValue(),
                    k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<ConfigData>> e : byScope.entrySet()) {
            String scope = e.getKey().isEmpty() ? null : e.getKey();
            dataRepo.deleteByDefIdAndStatusAndScopeValue(defId, "PUBLISHED", scope);
            for (ConfigData d : e.getValue()) {
                d.setStatus("PUBLISHED");
                d.setTaskId(null);
                d.setUpdatedAt(java.time.LocalDateTime.now());
                dataRepo.save(d);
            }
        }
    }
}
