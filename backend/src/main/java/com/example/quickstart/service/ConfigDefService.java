package com.example.quickstart.service;

import com.example.quickstart.dto.Condition;
import com.example.quickstart.dto.ConfigDefDto;
import com.example.quickstart.dto.FieldDef;
import com.example.quickstart.dto.QueryRequest;
import com.example.quickstart.dto.QueryResponse;
import com.example.quickstart.dto.RowDto;
import com.example.quickstart.dto.TemplateInfoDto;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDefinition;
import com.example.quickstart.repository.ConfigDataRepository;
import com.example.quickstart.repository.ConfigDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置项元数据与正式区数据查询 */
@Service
@RequiredArgsConstructor
public class ConfigDefService {

    private static final TypeReference<List<FieldDef>> FIELD_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ConfigDefinitionRepository defRepository;
    private final ConfigDataRepository dataRepository;
    private final ObjectMapper om;

    public List<ConfigDefDto> listDefs() {
        return defRepository.findAll().stream()
                .map(d -> toDto(d, dataRepository.countByDefCode(d.getCode())))
                .toList();
    }

    public ConfigDefinition getDefOrThrow(String code) {
        return defRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("配置项不存在: " + code));
    }

    public ConfigDefDto getDef(String code) {
        ConfigDefinition def = getDefOrThrow(code);
        return toDto(def, dataRepository.countByDefCode(code));
    }

    public TemplateInfoDto templateInfo(String code) {
        ConfigDefinition def = getDefOrThrow(code);
        return new TemplateInfoDto(def.getCode(), def.getCode() + ".xlsx", parseFields(def));
    }

    public QueryResponse query(QueryRequest req) {
        ConfigDefinition def = getDefOrThrow(req.code());
        List<RowDto> filtered = dataRepository.findByDefCodeOrderByRowNoAsc(def.getCode()).stream()
                .map(this::toRowDto)
                .filter(r -> ConditionFilter.matches(r.data(), req.conditions()))
                .toList();
        int page = req.page() == null ? 0 : Math.max(0, req.page());
        int size = req.size() == null ? 50 : Math.min(500, Math.max(1, req.size()));
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new QueryResponse(filtered.size(), filtered.subList(from, to));
    }

    /** 按条件查询正式区全部命中行（导出用，不分页），返回纯数据 Map 列表 */
    public List<Map<String, Object>> queryRows(String defCode, List<Condition> conditions) {
        return dataRepository.findByDefCodeOrderByRowNoAsc(defCode).stream()
                .map(this::toRowDto)
                .filter(r -> ConditionFilter.matches(r.data(), conditions))
                .map(RowDto::data)
                .toList();
    }

    public long countRows(String defCode, List<Condition> conditions) {
        return dataRepository.findByDefCodeOrderByRowNoAsc(defCode).stream()
                .map(this::toRowDto)
                .filter(r -> ConditionFilter.matches(r.data(), conditions))
                .count();
    }

    /** 正式区某配置项某字段的全部取值（ref 引用完整性校验数据源） */
    public Set<String> formalFieldValues(String defCode, String field) {
        Set<String> values = new LinkedHashSet<>();
        for (ConfigData row : dataRepository.findByDefCodeOrderByRowNoAsc(defCode)) {
            Object v = parseData(row.getDataJson()).get(field);
            if (v != null) {
                values.add(String.valueOf(v));
            }
        }
        return values;
    }

    public ConfigDefDto toDto(ConfigDefinition def, long rowCount) {
        return new ConfigDefDto(def.getCode(), def.getName(), def.getLevel(), def.getDescription(),
                parseFields(def), parseDependsOn(def), rowCount);
    }

    public RowDto toRowDto(ConfigData row) {
        return new RowDto(row.getRowNo(), parseData(row.getDataJson()));
    }

    public List<FieldDef> parseFields(ConfigDefinition def) {
        try {
            return om.readValue(def.getFieldsJson(), FIELD_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("配置项字段定义解析失败: " + def.getCode(), e);
        }
    }

    public List<String> parseDependsOn(ConfigDefinition def) {
        if (def.getDependsOnJson() == null || def.getDependsOnJson().isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(def.getDependsOnJson(), STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("配置项依赖定义解析失败: " + def.getCode(), e);
        }
    }

    public Map<String, Object> parseData(String dataJson) {
        try {
            return om.readValue(dataJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("数据行解析失败", e);
        }
    }

    public String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
