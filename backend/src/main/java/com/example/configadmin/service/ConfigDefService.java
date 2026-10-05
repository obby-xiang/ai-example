package com.example.configadmin.service;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.DefSaveRequest;
import com.example.configadmin.dto.DefView;
import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.Level;
import com.example.configadmin.repository.ConfigDefRepository;
import com.example.configadmin.repository.ConfigRowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/** 配置定义管理 + 依赖拓扑排序。 */
@Service
public class ConfigDefService {

    private static final Logger log = LoggerFactory.getLogger(ConfigDefService.class);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private final ConfigDefRepository defRepo;
    private final ConfigRowRepository rowRepo;
    private final ObjectMapper mapper;

    public ConfigDefService(ConfigDefRepository defRepo, ConfigRowRepository rowRepo, ObjectMapper mapper) {
        this.defRepo = defRepo;
        this.rowRepo = rowRepo;
        this.mapper = mapper;
    }

    public List<ConfigDef> listAll() {
        return defRepo.findAllByOrderBySortOrderAscIdAsc();
    }

    public List<ConfigDef> listEnabled() {
        return defRepo.findByEnabledTrueOrderBySortOrderAscIdAsc();
    }

    public ConfigDef getByCode(String code) {
        return defRepo.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("配置不存在：" + code));
    }

    public List<FieldDef> parseFields(ConfigDef def) {
        return parseJson(def.getFieldsJson(), new TypeReference<List<FieldDef>>() {
        });
    }

    public List<String> parseDependsOn(ConfigDef def) {
        return parseJson(def.getDependsOnJson(), new TypeReference<List<String>>() {
        });
    }

    private <T> T parseJson(String json, TypeReference<T> type) {
        try {
            return json == null || json.isBlank() ? mapper.readValue("[]", type) : mapper.readValue(json, type);
        } catch (Exception e) {
            throw new ApiException(500, "配置 JSON 解析失败：" + e.getMessage());
        }
    }

    @Transactional
    public ConfigDef create(DefSaveRequest req) {
        validateRequest(req, null);
        ConfigDef def = new ConfigDef();
        apply(def, req);
        return defRepo.save(def);
    }

    @Transactional
    public ConfigDef update(String code, DefSaveRequest req) {
        ConfigDef def = getByCode(code);
        validateRequest(req, code);
        apply(def, req);
        return defRepo.save(def);
    }

    @Transactional
    public void delete(String code) {
        ConfigDef def = getByCode(code);
        long rows = rowRepo.countByDefCode(code);
        if (rows > 0) {
            throw ApiException.badRequest("配置 [" + def.getName() + "] 下存在 " + rows + " 行数据，不能删除");
        }
        defRepo.delete(def);
    }

    @Transactional
    public ConfigDef toggle(String code) {
        ConfigDef def = getByCode(code);
        def.setEnabled(!def.isEnabled());
        return defRepo.save(def);
    }

    public DefView toView(ConfigDef def) {
        DefView v = new DefView();
        v.setId(def.getId());
        v.setCode(def.getCode());
        v.setName(def.getName());
        v.setLevel(def.getLevel());
        v.setDescription(def.getDescription());
        v.setFields(parseFields(def));
        v.setDependsOn(parseDependsOn(def));
        v.setSortOrder(def.getSortOrder());
        v.setEnabled(def.isEnabled());
        v.setPublishedRowCount(rowRepo.countByDefCodeAndPublished(def.getCode(), true));
        v.setDraftRowCount(rowRepo.countByDefCodeAndPublished(def.getCode(), false));
        return v;
    }

    private void apply(ConfigDef def, DefSaveRequest req) {
        def.setCode(req.getCode().trim().toUpperCase());
        def.setName(req.getName().trim());
        def.setLevel(req.getLevel());
        def.setDescription(req.getDescription());
        def.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        def.setEnabled(req.getEnabled() == null || req.getEnabled());
        try {
            def.setFieldsJson(mapper.writeValueAsString(req.getFields() == null ? List.of() : req.getFields()));
            def.setDependsOnJson(mapper.writeValueAsString(
                    req.getDependsOn() == null ? List.of() : req.getDependsOn()));
        } catch (Exception e) {
            throw new ApiException(500, "JSON 序列化失败：" + e.getMessage());
        }
    }

    private void validateRequest(DefSaveRequest req, String existingCode) {
        String code = req.getCode() == null ? "" : req.getCode().trim().toUpperCase();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw ApiException.badRequest("配置编码必须以字母开头，仅含字母/数字/下划线（1-64位）");
        }
        if (code.equalsIgnoreCase(existingCode)) {
            // 更新且编码未变
        } else if (defRepo.existsByCode(code)) {
            throw ApiException.badRequest("配置编码已存在：" + code);
        }
        if (req.getLevel() == null) {
            throw ApiException.badRequest("必须选择层级（全局/地区/项目）");
        }
        if (req.getFields() != null) {
            Set<String> codes = new HashSet<>();
            for (FieldDef f : req.getFields()) {
                if (f.getCode() == null || !CODE_PATTERN.matcher(f.getCode().trim()).matches()) {
                    throw ApiException.badRequest("字段编码不合法（字母开头，字母/数字/下划线）：" + f.getCode());
                }
                f.setCode(f.getCode().trim());
                if (!codes.add(f.getCode())) {
                    throw ApiException.badRequest("字段编码重复：" + f.getCode());
                }
                if (f.getLabel() == null || f.getLabel().isBlank()) {
                    throw ApiException.badRequest("字段 " + f.getCode() + " 缺少名称");
                }
                if (f.getType() == null) {
                    throw ApiException.badRequest("字段 " + f.getCode() + " 缺少类型");
                }
                if (f.getType() == FieldDef.FieldType.SELECT
                        && (f.getOptions() == null || f.getOptions().isEmpty())) {
                    throw ApiException.badRequest("下拉字段 " + f.getCode() + " 必须提供选项");
                }
                if (f.getType() == FieldDef.FieldType.REFERENCE) {
                    if (f.getRefDefCode() == null || f.getRefFieldCode() == null) {
                        throw ApiException.badRequest("引用字段 " + f.getCode() + " 必须指定引用配置与引用字段");
                    }
                    if (!defRepo.existsByCode(f.getRefDefCode())) {
                        throw ApiException.badRequest("引用字段 " + f.getCode() + " 的引用配置不存在：" + f.getRefDefCode());
                    }
                }
            }
        }
        if (req.getDependsOn() != null) {
            for (String dep : req.getDependsOn()) {
                if (!defRepo.existsByCode(dep)) {
                    throw ApiException.badRequest("依赖配置不存在：" + dep);
                }
            }
            if (req.getDependsOn().contains(req.getCode())) {
                throw ApiException.badRequest("配置不能依赖自身");
            }
        }
    }

    /**
     * 依赖拓扑排序（Kahn）。被依赖者在前；检测循环依赖。
     */
    public List<String> topoSort(Collection<String> defCodes) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (String code : defCodes) {
            ConfigDef def = getByCode(code);
            deps.put(code, parseDependsOn(def).stream().filter(defCodes::contains).toList());
        }
        Map<String, Integer> indeg = new HashMap<>();
        for (String c : deps.keySet()) {
            indeg.put(c, 0);
        }
        for (var e : deps.entrySet()) {
            for (String d : e.getValue()) {
                indeg.merge(d, 1, Integer::sum);
            }
        }
        Queue<String> queue = new ArrayDeque<>();
        indeg.forEach((c, d) -> {
            if (d == 0) queue.add(c);
        });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String c = queue.poll();
            order.add(c);
            for (var e : deps.entrySet()) {
                if (e.getValue().contains(c)) {
                    indeg.merge(e.getKey(), -1, Integer::sum);
                    if (indeg.get(e.getKey()) == 0) {
                        queue.add(e.getKey());
                    }
                }
            }
        }
        if (order.size() != defCodes.size()) {
            throw ApiException.badRequest("所选配置之间存在循环依赖，无法排序");
        }
        return order;
    }

    /** 供 AI 工具使用的简版描述（含字段） */
    public String describeForAi(ConfigDef def) {
        StringBuilder sb = new StringBuilder();
        sb.append(def.getCode()).append("（").append(def.getName()).append("，层级：")
                .append(def.getLevel()).append("，状态：").append(def.isEnabled() ? "启用" : "停用").append("）");
        List<FieldDef> fields = parseFields(def);
        if (!fields.isEmpty()) {
            sb.append(" 字段：");
            fields.forEach(f -> {
                sb.append(f.getCode()).append("=").append(f.getLabel());
                if (f.getType() == FieldDef.FieldType.SELECT && f.getOptions() != null) {
                    sb.append("[选项:").append(String.join("/", f.getOptions())).append("]");
                }
                if (f.getType() == FieldDef.FieldType.REFERENCE) {
                    sb.append("[引用:").append(f.getRefDefCode()).append(".").append(f.getRefFieldCode()).append("]");
                }
                sb.append(f.isRequired() ? "(必填) " : " ");
            });
        }
        List<String> deps = parseDependsOn(def);
        if (!deps.isEmpty()) {
            sb.append(" 依赖:").append(String.join(",", deps));
        }
        return sb.toString();
    }
}
