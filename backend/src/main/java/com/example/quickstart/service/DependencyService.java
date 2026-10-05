package com.example.quickstart.service;

import com.example.quickstart.entity.ConfigDefinition;
import com.example.quickstart.repository.ConfigDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖拓扑排序（Kahn）。未选中的依赖项视为以正式区数据为准（不参与排序）；
 * 循环依赖直接报错。
 */
@Service
@RequiredArgsConstructor
public class DependencyService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ConfigDefinitionRepository defRepository;
    private final ObjectMapper om;

    /**
     * 对选中的配置项编码做拓扑排序，返回处理顺序（依赖在前）。
     * 输出顺序稳定：同层按配置项定义顺序。
     */
    public List<String> topoSort(Collection<String> selectedCodes) {
        Map<String, ConfigDefinition> all = new LinkedHashMap<>();
        for (ConfigDefinition def : defRepository.findAll()) {
            all.put(def.getCode(), def);
        }
        for (String code : selectedCodes) {
            if (!all.containsKey(code)) {
                throw new IllegalArgumentException("配置项不存在: " + code);
            }
        }

        // 按定义顺序遍历，保证稳定输出
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String code : all.keySet()) {
            if (!selectedCodes.contains(code)) {
                continue;
            }
            indegree.putIfAbsent(code, 0);
            for (String dep : parseDependsOn(all.get(code))) {
                if (!selectedCodes.contains(dep)) {
                    continue;
                }
                indegree.merge(code, 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(code);
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String code = queue.poll();
            sorted.add(code);
            for (String dependent : dependents.getOrDefault(code, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }
        if (sorted.size() != indegree.size()) {
            List<String> remaining = new ArrayList<>(indegree.keySet());
            remaining.removeAll(sorted);
            throw new IllegalArgumentException("配置项存在循环依赖: " + remaining);
        }
        return sorted;
    }

    private List<String> parseDependsOn(ConfigDefinition def) {
        if (def.getDependsOnJson() == null || def.getDependsOnJson().isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(def.getDependsOnJson(), STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("配置项依赖定义解析失败: " + def.getCode(), e);
        }
    }
}
