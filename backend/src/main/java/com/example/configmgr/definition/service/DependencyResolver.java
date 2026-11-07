package com.example.configmgr.definition.service;

import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 解析配置定义间的依赖关系，对给定的 defCode 列表做拓扑排序（依赖先处理）。
 */
@Service
@RequiredArgsConstructor
public class DependencyResolver {

    private final ConfigDefinitionRepository definitionRepository;

    /**
     * 对 defCodes 做拓扑排序，返回按依赖顺序排好的列表（被依赖的在前）。
     * 循环依赖时抛出 IllegalStateException。
     */
    public List<String> sort(List<String> defCodes) {
        if (CollectionUtils.isEmpty(defCodes)) return Collections.emptyList();
        return topologicalSort(defCodes, buildDependencies(defCodes));
    }

    private Map<String, Set<String>> buildDependencies(List<String> defCodes) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (String code : defCodes) {
            deps.put(code, new HashSet<>());
        }

        List<ConfigDefinition> defs = definitionRepository.findAllOrdered();
        Map<String, ConfigDefinition> defMap = new HashMap<>();
        for (ConfigDefinition d : defs) {
            defMap.put(d.getCode(), d);
        }

        for (String code : defCodes) {
            ConfigDefinition def = defMap.get(code);
            if (def == null || def.getFields() == null) continue;
            def.getFields().forEach(f -> {
                if (f.getRefDefCode() != null && defCodes.contains(f.getRefDefCode())) {
                    deps.get(code).add(f.getRefDefCode());
                }
            });
        }
        return deps;
    }

    private List<String> topologicalSort(List<String> defCodes, Map<String, Set<String>> deps) {
        Map<String, Set<String>> revDeps = new HashMap<>();
        for (String code : defCodes) revDeps.put(code, new HashSet<>());
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            for (String dep : e.getValue()) {
                revDeps.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());
            }
        }
        Map<String, Integer> degree = new HashMap<>();
        for (String code : defCodes) degree.put(code, deps.get(code).size());

        Queue<String> queue = new LinkedList<>();
        for (String code : defCodes) {
            if (degree.get(code) == 0) queue.add(code);
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            Set<String> successors = revDeps.getOrDefault(current, Collections.emptySet());
            for (String s : successors) {
                int d = degree.merge(s, -1, Integer::sum);
                if (d == 0) queue.add(s);
            }
        }

        if (sorted.size() != defCodes.size()) {
            Set<String> remaining = new HashSet<>(defCodes);
            remaining.removeAll(sorted);
            throw new CodedIllegalStateException(ErrorCodes.DEFINITION_DEPENDENCY_CYCLE,
                    "Circular dependency between configuration definitions: " + remaining);
        }
        return sorted;
    }
}
