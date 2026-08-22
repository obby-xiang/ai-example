package com.example.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 工具动态发现服务 —— 业界 MCP 模式核心实现（响应经验 100029435）。
 *
 * 设计原则：
 *   1. 工具声明集中在 tools.yaml / scenarios.yaml（声明式，不写代码）
 *   2. 按场景+步骤过滤工具集（progressive disclosure）：AI 永远只看到当前步骤的工具
 *   3. 返回原生 OpenAI 协议格式（List<Map>），可直接作为请求体 tools 字段
 *
 * 业界对比：
 *   - MCP 协议的 tools/list RPC：上下文敏感的工具发现
 *   - LangGraph 状态图：每个节点是 context 边界，节点内只看自己的工具
 *   - Anthropic Skills：progressive disclosure，按需加载
 */
@Service
@Slf4j
public class ToolDiscoveryService {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 所有工具定义（按 tools.yaml 加载） */
    private List<ToolDefinition> allTools = new ArrayList<>();

    /** 工具名 → 定义映射，方便 O(1) 查找 */
    private Map<String, ToolDefinition> toolByName = new HashMap<>();

    /** 场景定义（按 scenarios.yaml 加载） */
    private Map<String, ScenarioDefinition> scenarios = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        loadToolsYaml();
        loadScenariosYaml();
        log.info("ToolDiscoveryService 初始化完成: 工具 {} 个, 场景 {} 个", allTools.size(), scenarios.size());
    }

    // ================================ 加载 YAML ================================

    @SuppressWarnings("unchecked")
    private void loadToolsYaml() {
        try (InputStream is = new ClassPathResource("tools.yaml").getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(is, Map.class);
            List<Map<String, Object>> toolsList = (List<Map<String, Object>>) root.get("tools");
            if (toolsList == null) {
                log.warn("tools.yaml 中未找到 tools 字段");
                return;
            }
            allTools = new ArrayList<>();
            toolByName = new HashMap<>();
            for (Map<String, Object> t : toolsList) {
                ToolDefinition def = parseToolDefinition(t);
                allTools.add(def);
                toolByName.put(def.getName(), def);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 tools.yaml 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadScenariosYaml() {
        try (InputStream is = new ClassPathResource("scenarios.yaml").getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(is, Map.class);
            Map<String, Object> scenariosMap = (Map<String, Object>) root.get("scenarios");
            if (scenariosMap == null) {
                log.warn("scenarios.yaml 中未找到 scenarios 字段");
                return;
            }
            scenarios = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : scenariosMap.entrySet()) {
                Map<String, Object> sData = (Map<String, Object>) e.getValue();
                ScenarioDefinition sDef = parseScenarioDefinition(e.getKey(), sData);
                scenarios.put(e.getKey(), sDef);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 scenarios.yaml 失败", e);
        }
    }

    private ToolDefinition parseToolDefinition(Map<String, Object> t) {
        ToolDefinition def = new ToolDefinition();
        def.setName((String) t.get("name"));
        def.setDescription((String) t.get("description"));
        def.setKind((String) t.getOrDefault("kind", "FRONTEND"));
        def.setAutoExec(Boolean.TRUE.equals(t.get("autoExec")));
        def.setNeedConfirm(Boolean.TRUE.equals(t.get("needConfirm")));
        def.setRequireDoubleConfirm(Boolean.TRUE.equals(t.get("requireDoubleConfirm")));
        def.setScenarios(toStringList(t.get("scenarios")));
        def.setSteps(toStringList(t.get("steps")));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) t.get("parameters");
        def.setParameters(params == null ? new LinkedHashMap<>() : params);
        return def;
    }

    @SuppressWarnings("unchecked")
    private ScenarioDefinition parseScenarioDefinition(String id, Map<String, Object> data) {
        ScenarioDefinition sDef = new ScenarioDefinition();
        sDef.setId(id);
        sDef.setName((String) data.getOrDefault("name", id));
        sDef.setDescription((String) data.getOrDefault("description", ""));
        List<Map<String, Object>> stepsList = (List<Map<String, Object>>) data.get("steps");
        List<ScenarioDefinition.StepDefinition> steps = new ArrayList<>();
        if (stepsList != null) {
            for (Map<String, Object> s : stepsList) {
                ScenarioDefinition.StepDefinition step = new ScenarioDefinition.StepDefinition();
                step.setId((String) s.get("id"));
                step.setNext((String) s.get("next"));
                step.setDescription((String) s.getOrDefault("description", ""));
                step.setTools(toStringList(s.get("tools")));
                steps.add(step);
            }
        }
        sDef.setSteps(steps);
        return sDef;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object o) {
        if (o instanceof List l) {
            List<String> result = new ArrayList<>();
            for (Object item : l) result.add(String.valueOf(item));
            return result;
        }
        return Collections.emptyList();
    }

    // ================================ 对外 API ================================

    /**
     * 按场景+步骤过滤工具集，返回原生 OpenAI 协议格式。
     *
     * 业界 MCP tools/list 的等价实现：
     *   - 上下文敏感（按 scenario+step 过滤）
     *   - 返回原生协议格式（直接作为请求体 tools 字段）
     */
    public List<Map<String, Object>> listAvailableTools(String scenario, String step) {
        List<String> allowedToolNames = getAllowedToolNames(scenario, step);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : allowedToolNames) {
            ToolDefinition def = toolByName.get(name);
            if (def == null) {
                log.warn("scenarios.yaml 引用了未定义的工具: {}", name);
                continue;
            }
            result.add(buildRawToolMap(def));
        }
        return result;
    }

    /**
     * 按场景+步骤过滤工具集，返回 ToolDefinition 列表（含 autoExec/needConfirm 等元数据）
     */
    public List<ToolDefinition> listAvailableToolDefs(String scenario, String step) {
        List<String> allowedToolNames = getAllowedToolNames(scenario, step);
        List<ToolDefinition> result = new ArrayList<>();
        for (String name : allowedToolNames) {
            ToolDefinition def = toolByName.get(name);
            if (def != null) result.add(def);
        }
        return result;
    }

    /** 获取场景某步骤允许的工具名列表（来自 scenarios.yaml 声明） */
    private List<String> getAllowedToolNames(String scenario, String step) {
        if (scenario == null || step == null) {
            // 未指定场景/步骤，返回所有工具（兜底）
            return allTools.stream().map(ToolDefinition::getName).toList();
        }
        ScenarioDefinition sDef = scenarios.get(scenario);
        if (sDef == null) {
            log.warn("未知场景: {}, 返回所有工具", scenario);
            return allTools.stream().map(ToolDefinition::getName).toList();
        }
        for (ScenarioDefinition.StepDefinition stepDef : sDef.getSteps()) {
            if (step.equals(stepDef.getId())) {
                return stepDef.getTools();
            }
        }
        log.warn("场景 {} 中未找到步骤 {}, 返回所有工具", scenario, step);
        return allTools.stream().map(ToolDefinition::getName).toList();
    }

    /** 获取单个工具定义 */
    public ToolDefinition getTool(String name) {
        return toolByName.get(name);
    }

    /** 获取所有工具（不过滤，调试用） */
    public List<ToolDefinition> listAll() {
        return Collections.unmodifiableList(allTools);
    }

    /** 获取所有场景定义 */
    public Map<String, ScenarioDefinition> getScenarios() {
        return Collections.unmodifiableMap(scenarios);
    }

    /** 获取某场景定义 */
    public ScenarioDefinition getScenario(String scenarioId) {
        return scenarios.get(scenarioId);
    }

    /** 工具是否需要确认（兼容 FrontendTools.defaultNeedConfirm） */
    public boolean defaultNeedConfirm(String toolName) {
        ToolDefinition def = toolByName.get(toolName);
        return def != null && def.isNeedConfirm();
    }

    /** 工具是否自动执行（无需用户点击） */
    public boolean isAutoExec(String toolName) {
        ToolDefinition def = toolByName.get(toolName);
        return def != null && def.isAutoExec();
    }

    /** 工具是否需要二次确认 */
    public boolean isRequireDoubleConfirm(String toolName) {
        ToolDefinition def = toolByName.get(toolName);
        return def != null && def.isRequireDoubleConfirm();
    }

    /** 工具分类 FRONTEND/BACKEND */
    public String kindOf(String toolName) {
        ToolDefinition def = toolByName.get(toolName);
        return def == null ? "FRONTEND" : def.getKind();
    }

    // ================================ 原生协议格式构造 ================================

    /**
     * 构造原生 OpenAI 协议格式 tool map：
     *   { "type":"function", "function": { "name", "description", "parameters" } }
     */
    public static Map<String, Object> buildRawToolMap(ToolDefinition def) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", def.getName());
        fn.put("description", def.getDescription());
        fn.put("parameters", def.getParameters());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }
}
