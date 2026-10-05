package com.example.configadmin.service;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.Cond;
import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.dto.Issue;
import com.example.configadmin.dto.RowDraft;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.ConfigRow;
import com.example.configadmin.repository.ConfigRowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 配置数据服务：查询（含通用条件引擎）、手工增删改、草稿/生效数据管理。
 * 说明：条件过滤在内存中进行（数据行以 JSON 存储），适用于中小规模；
 * 大数据量场景可平滑演进为列式存储或检索引擎（见 docs/03-实现方案.md）。
 */
@Service
public class ConfigDataService {

    private final ConfigRowRepository rowRepo;
    private final ConfigDefService defService;
    private final ValidationEngine engine;
    private final ObjectMapper mapper;

    public ConfigDataService(ConfigRowRepository rowRepo, ConfigDefService defService,
                             ValidationEngine engine, ObjectMapper mapper) {
        this.rowRepo = rowRepo;
        this.defService = defService;
        this.engine = engine;
        this.mapper = mapper;
    }

    /** 生效数据查询。conditions: Map<字段编码, Cond>。 */
    public List<Map<String, Object>> queryRows(String defCode, Map<String, Cond> conditions,
                                               String scope, boolean published, String batchId) {
        ConfigDef def = defService.getByCode(defCode);
        List<FieldDef> fields = defService.parseFields(def);
        List<ConfigRow> rows;
        if (!published && batchId != null) {
            // 批次草稿：仅返回未发布行（发布后批次行已转生效，不再属于草稿预览）
            rows = rowRepo.findByDefCodeAndBatchId(defCode, batchId).stream()
                    .filter(r -> !r.isPublished())
                    .toList();
        } else {
            rows = rowRepo.findByDefCodeAndPublished(defCode, published);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConfigRow r : rows) {
            Map<String, Object> data = parseData(r.getDataJson());
            if (scope != null && !scope.isBlank() && !scope.equals(r.getScope())) {
                continue;
            }
            if (conditions != null && !matchAll(data, conditions, fields)) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", r.getId());
            out.put("defCode", r.getDefCode());
            out.put("scope", r.getScope());
            out.put("batchId", r.getBatchId());
            out.put("published", r.isPublished());
            out.put("data", data);
            result.add(out);
        }
        return result;
    }

    /** 分页包装（前端表格用）。 */
    public Map<String, Object> queryRowsPaged(String defCode, Map<String, Cond> conditions, String scope,
                                              boolean published, String batchId, int page, int size) {
        List<Map<String, Object>> all = queryRows(defCode, conditions, scope, published, batchId);
        int total = all.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", total);
        res.put("page", page);
        res.put("size", size);
        res.put("rows", all.subList(from, to));
        return res;
    }

    private boolean matchAll(Map<String, Object> data, Map<String, Cond> conditions, List<FieldDef> fields) {
        for (Map.Entry<String, Cond> e : conditions.entrySet()) {
            Cond c = e.getValue();
            if (c == null || c.op() == null || c.op().isBlank()) {
                continue;
            }
            Object val = data.get(e.getKey());
            FieldDef fd = fields.stream().filter(f -> f.getCode().equals(e.getKey())).findFirst().orElse(null);
            if (!matchOne(val, c, fd)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchOne(Object val, Cond c, FieldDef fd) {
        String op = c.op();
        if (val == null) {
            return false;
        }
        String s = String.valueOf(val).trim();
        switch (op) {
            case "eq" -> {
                if (fd != null && fd.getType() == FieldDef.FieldType.NUMBER) {
                    return numberEq(val, c.value());
                }
                if (fd != null && fd.getType() == FieldDef.FieldType.BOOLEAN) {
                    return Boolean.parseBoolean(s) == Boolean.parseBoolean(String.valueOf(c.value()));
                }
                return s.equals(String.valueOf(c.value()).trim());
            }
            case "contains" -> {
                return s.contains(String.valueOf(c.value()));
            }
            case "gt" -> {
                return compare(val, c.value()) > 0;
            }
            case "lt" -> {
                return compare(val, c.value()) < 0;
            }
            case "between" -> {
                return compare(val, c.value()) >= 0 && compare(val, c.value2()) <= 0;
            }
            case "in" -> {
                List<String> list = new ArrayList<>();
                if (c.value() instanceof List<?> l) {
                    l.forEach(x -> list.add(String.valueOf(x)));
                } else {
                    list.add(String.valueOf(c.value()));
                }
                return list.contains(s);
            }
            default -> throw ApiException.badRequest("不支持的条件操作符：" + op);
        }
    }

    private boolean numberEq(Object val, Object expected) {
        try {
            return Double.parseDouble(String.valueOf(val)) == Double.parseDouble(String.valueOf(expected));
        } catch (NumberFormatException e) {
            return String.valueOf(val).equals(String.valueOf(expected));
        }
    }

    private double compare(Object a, Object b) {
        String as = String.valueOf(a), bs = String.valueOf(b);
        try {
            return Double.compare(Double.parseDouble(as), Double.parseDouble(bs));
        } catch (NumberFormatException e1) {
            try {
                return engine.parseDate(as).compareTo(engine.parseDate(bs));
            } catch (Exception e2) {
                return as.compareTo(bs);
            }
        }
    }

    /**
     * 收集引用校验所需的取值集合。
     * @param extraByDefCode 批次内已通过行的取值补充：Map<defCode, List<row数据Map>>
     * @return Map<defCode, Set<Object>>：每个配置中所有字段的生效取值（含批次补充）
     */
    public Map<String, Set<Object>> buildRefValueSets(Map<String, List<Map<String, Object>>> extraByDefCode) {
        Map<String, Set<Object>> sets = new HashMap<>();
        for (ConfigDef def : defService.listAll()) {
            Set<Object> set = new HashSet<>();
            for (ConfigRow r : rowRepo.findByDefCodeAndPublished(def.getCode(), true)) {
                parseData(r.getDataJson()).values().forEach(v -> {
                    if (v != null) set.add(v);
                });
            }
            List<Map<String, Object>> extra = extraByDefCode == null ? null : extraByDefCode.get(def.getCode());
            if (extra != null) {
                extra.forEach(row -> row.values().forEach(v -> {
                    if (v != null) set.add(v);
                }));
            }
            sets.put(def.getCode(), set);
        }
        return sets;
    }

    /** 手工新增一行（直接生效或草稿）。 */
    @Transactional
    public ConfigRow saveRow(String defCode, RowDraft draft) {
        ConfigDef def = defService.getByCode(defCode);
        List<FieldDef> fields = defService.parseFields(def);
        Map<String, Object> input = new LinkedHashMap<>(draft.getData());
        if (def.getLevel() != com.example.configadmin.entity.Level.GLOBAL) {
            input.put("__scope__", draft.getScope());
        }
        ValidationEngine.RowValidation rv = engine.validateAndNormalize(def, fields, input,
                buildRefValueSets(null), 1);
        if (rv.hasErrors()) {
            throw ApiException.badRequest("数据校验失败：" + rv.issues().get(0).getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>(rv.normalized());
        data.remove("__scope__");
        ConfigRow row = new ConfigRow();
        row.setDefCode(defCode);
        row.setScope(draft.getScope());
        row.setDataJson(writeJson(data));
        row.setPublished(draft.isPublished());
        row.setBatchId(draft.getBatchId());
        return rowRepo.save(row);
    }

    /** 手工更新一行（仅草稿行允许编辑）。 */
    @Transactional
    public ConfigRow updateRow(String defCode, Long rowId, RowDraft draft) {
        ConfigRow row = rowRepo.findById(rowId)
                .orElseThrow(() -> ApiException.notFound("数据行不存在：" + rowId));
        if (!row.getDefCode().equals(defCode)) {
            throw ApiException.badRequest("数据行与配置不匹配");
        }
        ConfigDef def = defService.getByCode(defCode);
        List<FieldDef> fields = defService.parseFields(def);
        Map<String, Object> input = new LinkedHashMap<>(draft.getData());
        if (def.getLevel() != com.example.configadmin.entity.Level.GLOBAL) {
            input.put("__scope__", draft.getScope());
        }
        ValidationEngine.RowValidation rv = engine.validateAndNormalize(def, fields, input,
                buildRefValueSets(null), 1);
        if (rv.hasErrors()) {
            throw ApiException.badRequest("数据校验失败：" + rv.issues().get(0).getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>(rv.normalized());
        data.remove("__scope__");
        row.setScope(draft.getScope());
        row.setDataJson(writeJson(data));
        return rowRepo.save(row);
    }

    @Transactional
    public void deleteRow(Long rowId) {
        rowRepo.deleteById(rowId);
    }

    public Map<String, Object> parseData(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new ApiException(500, "行数据 JSON 解析失败：" + e.getMessage());
        }
    }

    public String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ApiException(500, "JSON 序列化失败：" + e.getMessage());
        }
    }

    public List<Issue> issuesFrom(Object o) {
        try {
            String json = mapper.writeValueAsString(o);
            return mapper.readValue(json, new TypeReference<List<Issue>>() {
            });
        } catch (Exception e) {
            throw new ApiException(500, "校验结果解析失败：" + e.getMessage());
        }
    }
}
