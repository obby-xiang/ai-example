package com.example.configmgr.data.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigDataService {

    private final ConfigDataRowRepository dataRowRepository;
    private final DefinitionService definitionService;
    private final ObjectMapper objectMapper;

    public List<ConfigDataRow> findAll(String defCode, String scopeType, String scopeKey) {
        if (scopeKey != null && !scopeKey.isBlank()) {
            return dataRowRepository.findByDefCodeAndScopeTypeAndScopeKeyOrderByRowKey(
                    defCode, scopeType != null ? scopeType : "GLOBAL", scopeKey);
        }
        return dataRowRepository.findByDefCodeOrderByRowKey(defCode);
    }

    public Page<ConfigDataRow> findPaged(String defCode, String scopeType, String scopeKey, int page, int size) {
        return dataRowRepository.findByDefCodeAndScopeTypeAndScopeKey(
                defCode,
                scopeType != null ? scopeType : "GLOBAL",
                scopeKey,
                PageRequest.of(page, size));
    }

    public long count(String defCode, String scopeType, String scopeKey) {
        return dataRowRepository.countByScope(defCode,
                scopeType != null ? scopeType : "GLOBAL", scopeKey);
    }

    @Transactional
    public ConfigDataRow save(String defCode, String scopeType, String scopeKey,
                               String rowKey, Map<String, Object> data) throws Exception {
        String dataJson = objectMapper.writeValueAsString(data);
        Optional<ConfigDataRow> existing = dataRowRepository
                .findByDefCodeAndScopeTypeAndScopeKeyAndRowKey(defCode, scopeType, scopeKey, rowKey);

        ConfigDataRow row = existing.orElseGet(ConfigDataRow::new);
        row.setDefCode(defCode);
        row.setScopeType(scopeType != null ? scopeType : "GLOBAL");
        row.setScopeKey(scopeKey);
        row.setRowKey(rowKey);
        row.setDataJson(dataJson);
        return dataRowRepository.save(row);
    }

    @Transactional
    public int batchSave(String defCode, String scopeType, String scopeKey,
                          List<Map<String, Object>> rows, List<String> keyFields) throws Exception {
        int count = 0;
        for (Map<String, Object> data : rows) {
            String rowKey = buildRowKey(data, keyFields);
            save(defCode, scopeType, scopeKey, rowKey, data);
            count++;
        }
        return count;
    }

    public Map<String, Object> parseRow(ConfigDataRow row) throws Exception {
        return objectMapper.readValue(row.getDataJson(), new TypeReference<>() {});
    }

    public static String buildRowKey(Map<String, Object> data, List<String> keyFields) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyFields) {
            if (sb.length() > 0) sb.append("|");
            Object v = data.get(k);
            sb.append(v != null ? v.toString() : "");
        }
        return sb.toString();
    }
}
