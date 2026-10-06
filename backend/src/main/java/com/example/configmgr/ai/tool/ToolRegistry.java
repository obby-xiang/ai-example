package com.example.configmgr.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry of all AI tools. Scans @Tool methods across all beans,
 * enriches with @ToolScope for progressive disclosure.
 */
@Slf4j
@Component
public class ToolRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    // name -> (meta, callback)
    private final Map<String, ToolMeta> metaMap = new LinkedHashMap<>();
    private final Map<String, ToolCallback> callbackMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // Scan all beans for @Tool annotated methods
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try { bean = applicationContext.getBean(beanName); } catch (Exception e) { continue; }
            Class<?> cls = bean.getClass();
            // Walk through proxy to find real class
            while (cls.isSynthetic() || cls.getName().contains("$$")) {
                cls = cls.getSuperclass();
                if (cls == null || cls == Object.class) { cls = bean.getClass(); break; }
            }

            for (Method method : cls.getDeclaredMethods()) {
                Tool toolAnn = method.getAnnotation(Tool.class);
                if (toolAnn == null) continue;

                String name = toolAnn.name().isBlank() ? method.getName() : toolAnn.name();
                ToolScope scopeAnn = method.getAnnotation(ToolScope.class);
                ToolRisk riskAnn = method.getAnnotation(ToolRisk.class);
                ToolChannel channelAnn = method.getAnnotation(ToolChannel.class);

                ToolMeta meta = new ToolMeta();
                meta.setName(name);
                meta.setDisplayName(toolAnn.description().length() > 20
                        ? toolAnn.description().substring(0, 20) : toolAnn.description());
                meta.setScopePatterns(scopeAnn != null ? scopeAnn.value() : new String[]{"*"});
                meta.setRiskLevel(riskAnn != null ? riskAnn.value() : ToolMeta.RiskLevel.READ);
                meta.setChannel(channelAnn != null ? channelAnn.value() : ToolMeta.Channel.BACKEND);

                metaMap.put(name, meta);
                log.debug("Registered tool: {} scopes={}", name, Arrays.toString(meta.getScopePatterns()));
            }

            // Build ToolCallbacks from all @Tool methods on this bean
            try {
                var provider = MethodToolCallbackProvider.builder().toolObjects(bean).build();
                for (ToolCallback tc : provider.getToolCallbacks()) {
                    callbackMap.put(tc.getToolDefinition().name(), tc);
                }
            } catch (Exception e) {
                // Not a tool bean or no @Tool methods
            }
        }
        log.info("ToolRegistry: registered {} tools", callbackMap.size());
    }

    /**
     * Returns the list of ToolCallbacks visible in the given context.
     */
    public List<ToolCallback> forContext(AiContext ctx) {
        Set<String> tags = buildTags(ctx);
        return callbackMap.entrySet().stream()
                .filter(e -> {
                    ToolMeta meta = metaMap.get(e.getKey());
                    if (meta == null) return true; // unscoped: always include
                    return matchesAny(meta.getScopePatterns(), tags);
                })
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    public ToolCallback getCallback(String name) {
        return callbackMap.get(name);
    }

    public ToolMeta getMeta(String name) {
        return metaMap.get(name);
    }

    /**
     * 风险等级（HITL 判定用）。未注册的工具按 READ 处理——与
     * {@code @ToolRisk} 的默认值一致，不因缺元数据而把工具变成"需要人审"。
     */
    public ToolMeta.RiskLevel riskOf(String name) {
        ToolMeta meta = metaMap.get(name);
        return meta == null || meta.getRiskLevel() == null ? ToolMeta.RiskLevel.READ : meta.getRiskLevel();
    }

    /**
     * 执行通道（挂起判定用）。未注册的工具按 BACKEND 处理，即回落官方默认执行——
     * 挂起只对<b>已显式声明</b>的通道生效，避免元数据缺失导致工具"卡住不执行"。
     */
    public ToolMeta.Channel channelOf(String name) {
        ToolMeta meta = metaMap.get(name);
        return meta == null || meta.getChannel() == null ? ToolMeta.Channel.BACKEND : meta.getChannel();
    }

    private Set<String> buildTags(AiContext ctx) {
        Set<String> tags = new HashSet<>();
        tags.add("*");
        if (ctx.getPage() != null && !ctx.getPage().isBlank()) tags.add("page:" + ctx.getPage());
        if (ctx.getTaskType() != null) {
            tags.add("task:*");
            tags.add("task:" + ctx.getTaskType());
            if (ctx.getStep() != null) tags.add("task:" + ctx.getTaskType() + "/" + ctx.getStep());
        }
        return tags;
    }

    private boolean matchesAny(String[] patterns, Set<String> tags) {
        for (String p : patterns) {
            if (tags.contains(p)) return true;
        }
        return false;
    }
}
