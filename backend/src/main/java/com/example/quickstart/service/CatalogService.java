package com.example.quickstart.service;

import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDef;
import com.example.quickstart.entity.ConfigField;
import com.example.quickstart.entity.ScopeDict;
import com.example.quickstart.repository.ConfigDataRepository;
import com.example.quickstart.repository.ConfigDefRepository;
import com.example.quickstart.repository.ConfigFieldRepository;
import com.example.quickstart.repository.ScopeDictRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final ConfigDefRepository defRepo;
    private final ConfigFieldRepository fieldRepo;
    private final ConfigDataRepository dataRepo;
    private final ScopeDictRepository scopeRepo;

    @Transactional(readOnly = true)
    public List<CatalogDTO.ConfigItem> listConfigs() {
        List<ConfigDef> defs = defRepo.findByOrderByLevelAscCodeAsc();
        if (defs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ConfigField>> fieldsByDef = fieldRepo.findAll().stream()
                .collect(Collectors.groupingBy(ConfigField::getDefId));
        Map<Long, Long> counts = dataRepo.findAll().stream()
                .filter(d -> "PUBLISHED".equals(d.getStatus()))
                .collect(Collectors.groupingBy(ConfigData::getDefId, Collectors.counting()));
        Map<String, ConfigDef> byCode = defs.stream()
                .collect(Collectors.toMap(ConfigDef::getCode, Function.identity()));
        return defs.stream().map(def -> toItem(def, fieldsByDef.getOrDefault(def.getId(), List.of()),
                counts.getOrDefault(def.getId(), 0L), byCode)).toList();
    }

    private CatalogDTO.ConfigItem toItem(ConfigDef def, List<ConfigField> fields, long rowCount,
                                         Map<String, ConfigDef> byCode) {
        CatalogDTO.ConfigItem item = new CatalogDTO.ConfigItem();
        item.setCode(def.getCode());
        item.setName(def.getName());
        item.setLevel(def.getLevel());
        item.setDescription(def.getDescription());
        item.setRowCount(rowCount);
        item.setFields(fields.stream().map(f -> {
            CatalogDTO.FieldMeta fm = new CatalogDTO.FieldMeta();
            fm.setCode(f.getFieldCode());
            fm.setName(f.getFieldName());
            fm.setDataType(f.getDataType());
            fm.setRequired(Boolean.TRUE.equals(f.getRequired()));
            fm.setKey(Boolean.TRUE.equals(f.getIsKey()));
            fm.setSampleValue(f.getSampleValue());
            if ("ENUM".equals(f.getDataType()) && f.getOptions() != null) {
                fm.setOptions(JsonUtil.read(f.getOptions(), new TypeReference<List<String>>() {
                }));
            }
            if ("SCOPE".equals(f.getDataType())) {
                fm.setOptions(listScopeValues("PROJECT".equals(def.getLevel()) ? "PROJECT" : "REGION"));
            }
            return fm;
        }).toList());
        item.setDependsOn(parseDepends(def.getDependsOn()));
        return item;
    }

    public static List<CatalogDTO.Dependency> parseDepends(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JsonUtil.read(json, new TypeReference<List<CatalogDTO.Dependency>>() {
        });
    }

    public List<String> listScopeValues(String type) {
        return scopeRepo.findByScopeTypeOrderByCodeAsc(type).stream().map(s ->
                s.getCode() + "|" + s.getName()).toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogDTO.ScopeItem> listScopes(String type) {
        List<ScopeDict> list = (type == null || type.isBlank())
                ? scopeRepo.findAll()
                : scopeRepo.findByScopeTypeOrderByCodeAsc(type);
        return list.stream().map(s -> new CatalogDTO.ScopeItem(s.getScopeType(), s.getCode(), s.getName()))
                .collect(Collectors.toList());
    }
}
