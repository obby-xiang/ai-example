package com.example.configmgr.data.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.service.ConditionEvaluator;
import com.example.configmgr.data.service.QueryCondition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final ConfigDataRowRepository dataRowRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/{defCode}")
    public ApiResponse<Page<ConfigDataRow>> list(@PathVariable String defCode,
                                                   @RequestParam(required = false) String scopeType,
                                                   @RequestParam(required = false) String scopeKey,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(dataRowRepository.findByDefCodeAndScopeTypeAndScopeKey(
                defCode, scopeType != null ? scopeType : "GLOBAL", scopeKey,
                org.springframework.data.domain.PageRequest.of(page, size)));
    }

    /**
     * 预估行数：支持带查询条件（conditions 为 QueryCondition 的 JSON），
     * 与导出作业的过滤口径完全一致。
     */
    @GetMapping("/{defCode}/count")
    public ApiResponse<Map<String, Long>> count(@PathVariable String defCode,
                                                  @RequestParam(required = false) String scopeType,
                                                  @RequestParam(required = false) String scopeKey,
                                                  @RequestParam(required = false) String conditions) {
        QueryCondition cond = null;
        if (conditions != null && !conditions.isBlank()) {
            try {
                cond = objectMapper.readValue(conditions, QueryCondition.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("条件 JSON 解析失败: " + e.getMessage());
            }
        }

        long count;
        if (cond != null) {
            // 有条件时逐行过滤（字段条件逐个匹配）
            final QueryCondition effectiveCond = cond;
            count = dataRowRepository.findByDefCodeOrderByRowKey(defCode).stream()
                    .filter(r -> scopeKey == null || scopeKey.isBlank() || scopeKey.equals(r.getScopeKey()))
                    .filter(r -> {
                        try {
                            Map<String, Object> data =
                                    objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                            return ConditionEvaluator.matches(data, effectiveCond);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
        } else {
            count = dataRowRepository.countByScope(defCode,
                    scopeType != null ? scopeType : "GLOBAL", scopeKey);
        }
        return ApiResponse.ok(Map.of("count", count));
    }
}
