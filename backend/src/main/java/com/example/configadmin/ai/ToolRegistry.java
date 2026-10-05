package com.example.configadmin.ai;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：按页面过滤（渐进式披露——不同页面暴露不同工具，
 * 系统提示词只披露当前页工具清单，工具权限也被页面隔离）。
 */
@Component
public class ToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> toolBeans) {
        for (AiTool t : toolBeans) {
            if (tools.putIfAbsent(t.def().name(), t) != null) {
                throw new IllegalStateException("工具名重复：" + t.def().name());
            }
        }
    }

    public Optional<AiTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDef> forPage(String page) {
        return tools.values().stream()
                .map(AiTool::def)
                .filter(d -> d.visibleOn(page))
                .toList();
    }

    public List<ToolDef> all() {
        return tools.values().stream().map(AiTool::def).toList();
    }
}
