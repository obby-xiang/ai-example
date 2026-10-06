package com.example.configmgr.definition.service;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

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
        if (defCodes == null || defCodes.isEmpty()) return Collections.emptyList();

        // 构建局部依赖图（仅在 defCodes 范围内的依赖）
        Map<String, Set<String>> deps = new HashMap<>();
        for (String code : defCodes) {
            deps.put(code, new HashSet<>());
        }

        // 加载所有定义的字段，找出 REFERENCE 类型字段指向 defCodes 内其他定义的情况
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

        // Kahn 算法拓扑排序
        Map<String, Integer> inDegree = new HashMap<>();
        for (String code : defCodes) inDegree.put(code, 0);
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            for (String dep : e.getValue()) {
                inDegree.merge(dep, 0, Integer::sum); // ensure key exists
            }
            // e.key depends on e.value → e.key has in-degree from e.value
        }
        // Rebuild: for a → b (a depends on b), b should come before a
        // in-degree for each node = number of nodes that depend ON it
        Map<String, Integer> inDeg = new HashMap<>();
        for (String code : defCodes) inDeg.put(code, 0);
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            // e.key depends on e.value set
            // so in the output graph, we need all e.value before e.key
            // meaning e.key has in-degree = e.value.size() in sorted order
        }

        // Rebuild properly with reverse dep map
        Map<String, Set<String>> revDeps = new HashMap<>();
        for (String code : defCodes) revDeps.put(code, new HashSet<>());
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            for (String dep : e.getValue()) {
                // dep must come before code → dep -> code in topo
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
            throw new IllegalStateException("配置定义间存在循环依赖: " + remaining);
        }
        return sorted;
    }
}
