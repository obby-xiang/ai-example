package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
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

    /** 工具定义覆盖源（YAML 阶段；缺失 ⇒ 空快照 ⇒ 全部回落注解）。 */
    @Autowired(required = false)
    private ToolDefinitionSource toolDefinitionSource;

    /**
     * 表格工具披露开关（AI 表格工具治理批）。缺省装配 = 读开/写关（与 {@link AiProperties.Sheet}
     * 的字段初值同一口径），Spring 在容器内注入真实实例覆盖它 —— 于是"未注入"与"默认四键"
     * 行为一致，不会因为装配缺失而把功能静默全关。
     */
    @Autowired(required = false)
    private SheetSwitches sheetSwitches = SheetSwitches.defaults();

    // name -> (meta, callback)
    private final Map<String, ToolMeta> metaMap = new LinkedHashMap<>();
    private final Map<String, ToolCallback> callbackMap = new LinkedHashMap<>();

    /** 已应用的快照版本（诊断面 / 日志用；空快照 = "annotations"）。 */
    private String snapshotVersion = "annotations";

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

            boolean hasToolAnnotation = false;
            for (Method method : cls.getDeclaredMethods()) {
                Tool toolAnn = method.getAnnotation(Tool.class);
                if (toolAnn == null) continue;
                hasToolAnnotation = true;

                String name = toolAnn.name().isBlank() ? method.getName() : toolAnn.name();
                ToolScope scopeAnn = method.getAnnotation(ToolScope.class);
                ToolRisk riskAnn = method.getAnnotation(ToolRisk.class);
                ToolChannel channelAnn = method.getAnnotation(ToolChannel.class);
                ToolCategory categoryAnn = method.getAnnotation(ToolCategory.class);

                ToolMeta meta = new ToolMeta();
                meta.setName(name);
                // E9：展示名不再取 description 前 20 字（改描述会连带改 UI 文案）。
                // 缺省 = 工具名；YAML 有 title 时由 applyOverrides 覆盖。
                meta.setDisplayName(name);
                meta.setTitleKey("tool." + name + ".title");
                meta.setLang("zh");
                meta.setScopePatterns(scopeAnn != null ? scopeAnn.value() : new String[]{"*"});
                meta.setRiskLevel(riskAnn != null ? riskAnn.value() : ToolMeta.RiskLevel.READ);
                meta.setChannel(channelAnn != null ? channelAnn.value() : ToolMeta.Channel.BACKEND);
                meta.setCategory(categoryAnn != null ? categoryAnn.value() : null);

                metaMap.put(name, meta);
                log.debug("Registered tool: {} scopes={}", name, Arrays.toString(meta.getScopePatterns()));
            }

            // Build ToolCallbacks from all @Tool methods on this bean
            try {
                var provider = MethodToolCallbackProvider.builder().toolObjects(bean).build();
                for (ToolCallback tc : provider.getToolCallbacks()) {
                    callbackMap.put(tc.getToolDefinition().name(), normalizeSchema(tc));
                }
            } catch (Exception e) {
                // 非工具 bean：MethodToolCallbackProvider 对"没有 @Tool 方法"的 bean 会直接抛错，
                // 这是扫描期的正常噪音，继续。
                if (!hasToolAnnotation) {
                    continue;
                }
                // 真工具 bean 的注册失败绝不能吞：schema 归一化/构建出问题而静默掉工具，
                // 会让应用"看起来启动成功"却少了一批工具（比启动失败更难查）。
                throw new IllegalStateException("工具 bean " + beanName + " 的回调注册失败", e);
            }
        }
        applyOverrides();
        log.info("ToolRegistry: registered {} tools（定义来源={}）", callbackMap.size(), snapshotVersion);
    }

    /**
     * 注册期 schema 归一化（2026-10-22 回归修复）：{@code MethodToolCallbackProvider} 之后、
     * 入 {@code callbackMap} 之前，把参数级 {@code $defs} 提升到文档根，保证
     * {@code #/$defs/X} 形态的 {@code $ref} 可解析。
     *
     * <p>
     * 不归一化时，victools 把"被多次引用的类型"提级到<b>参数子 schema</b> 的 {@code $defs}，
     * 而 {@code $ref} 从文档根解析 ⇒ 上游对整轮请求 400（见
     * {@code ToolSchemaRefIntegrityTest}）。执行路径不动：只是换 definition，委托沿用
     * {@link OverriddenToolCallback}（两个 {@code call} 重载都正确转发 ToolContext）。
     *
     * <p>
     * schema 未被改动时<b>原样返回同一个回调</b> —— 其余工具的定义字节不变，prompt 缓存不受影响。
     */
    private ToolCallback normalizeSchema(ToolCallback callback) {
        ToolDefinition definition = callback.getToolDefinition();
        String normalized = ToolSchemaNormalizer.normalize(definition.name(), definition.inputSchema());
        if (normalized == null || normalized.equals(definition.inputSchema())) {
            return callback;
        }
        ToolDefinition rebuilt = DefaultToolDefinition.builder()
                .name(definition.name())
                .description(definition.description())
                .inputSchema(normalized)
                .build();
        log.info("工具 schema 归一化：{}（参数级 $defs 提升到文档根）", definition.name());
        return new OverriddenToolCallback(callback, rebuilt);
    }

    /**
     * 应用外置定义（YAML 阶段 A）：只换送模型的 definition 与 UI 标题，**执行路径不动**。
     *
     * <p>
     * 三条校验（只对"写了覆盖"的条目生效）：
     * ① toolKey 必须能对上已注册的模型侧 name（否则覆盖永远不生效，属配置错误）；
     * ② 提供 inputSchema 时与反射生成的对账（property 名/类型 + required 集合），不一致**启动失败**；
     * ③ status 非 published 的条目忽略。
     */
    private void applyOverrides() {
        if (toolDefinitionSource == null) {
            return;
        }
        ToolSnapshot snapshot = toolDefinitionSource.load();
        this.snapshotVersion = snapshot.isEmpty() ? "annotations" : snapshot.version();
        for (ToolDefinitionOverride override : snapshot.byToolKey().values()) {
            String modelName = override.effectiveModelName();
            ToolCallback original = callbackMap.get(modelName);
            if (original == null) {
                throw new IllegalStateException("工具定义覆盖指向未注册的工具：" + override.toolKey()
                        + "（modelName=" + modelName + "）");
            }
            if (!override.published()) {
                log.info("工具定义覆盖：{} status={}，不进快照", override.toolKey(), override.status());
                continue;
            }
            ToolMeta meta = metaMap.get(modelName);
            if (meta != null) {
                meta.setTitleKey(override.effectiveTitleKey());
                meta.setTitle(override.title());
                meta.setLang(override.lang() == null ? "zh" : override.lang());
                if (override.title() != null && !override.title().isBlank()) {
                    meta.setDisplayName(override.title());
                }
            }
            ToolDefinition reflected = original.getToolDefinition();
            String schema = reflected.inputSchema();
            if (override.hasSchema()) {
                ToolSchemaReconciler.reconcile(override.toolKey(), reflected.inputSchema(), override.inputSchema());
                // 覆盖 schema 同样过归一化：外置定义也可能带参数级 $defs（同一把 $ref 口径）。
                schema = ToolSchemaNormalizer.normalize(override.toolKey(), override.inputSchema());
            }
            if (!override.hasDescription() && !override.hasSchema()) {
                continue;
            }
            ToolDefinition overridden = DefaultToolDefinition.builder()
                    .name(reflected.name())
                    .description(override.hasDescription() ? override.description() : reflected.description())
                    .inputSchema(schema)
                    .build();
            callbackMap.put(modelName, new OverriddenToolCallback(original, overridden));
            log.info("工具定义覆盖生效：{}（description={}，schema={}）", modelName,
                    override.hasDescription() ? "overridden" : "annotation",
                    override.hasSchema() ? "overridden" : "reflection");
        }
    }

    /** 快照版本（诊断面 `GET /api/ai/tools` 可观测；改描述会击穿 prompt 缓存）。 */
    public String snapshotVersion() {
        return snapshotVersion;
    }

    /**
     * Returns the list of ToolCallbacks visible in the given context.
     *
     * <p>
     * AI 表格工具治理批（2026-10-22 §2.3）：披露判定 = {@code scopeMatches AND sheetAllows} ——
     * 表格开关并入<b>同一谓词</b>，而不是新增第四道防线。执行前的 {@link #matchesContext} 复用
     * 完全相同的两项，故"披露面"与"执行面"在结构上无法分叉（S-02）。
     */
    public List<ToolCallback> forContext(AiContext ctx) {
        Set<String> tags = buildTags(ctx);
        return callbackMap.entrySet().stream()
                .filter(e -> {
                    ToolMeta meta = metaMap.get(e.getKey());
                    if (meta == null) return true; // unscoped: always include
                    return matchesAny(meta.getScopePatterns(), tags) && this.sheetSwitches.allows(e.getKey());
                })
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    public ToolCallback getCallback(String name) {
        return callbackMap.get(name);
    }

    /**
     * 全部已登记回调（只读快照，顺序 = 注册顺序）。
     *
     * <p>
     * 披露面 {@link #forContext} 只给"当前上下文该看的子集"，无法用来遍历全量工具；
     * 而 schema 级不变量（如 {@code $ref} 必须在文档根可解析，见
     * {@code ToolSchemaRefIntegrityTest}）必须对<b>每一个</b>工具成立，与页面/任务无关。
     * 故单独开一个只读入口，避免测试用反射摸私有表。
     */
    public List<ToolCallback> allCallbacks() {
        return List.copyOf(callbackMap.values());
    }

    /** 已登记工具总数（只读诊断面 {@code GET /api/ai/tools} 的对照值）。 */
    public int registeredCount() {
        return callbackMap.size();
    }

    /**
     * 渐进披露<b>第三道防线</b>（FR-5.2 的三重防线，DC-14 T1 补齐）：
     * 执行前用<b>同一份</b> scope 元数据复查该工具在当前上下文里是否本就该被披露。
     *
     * <p>
     * 前两道防线是"请求侧"的：防线① {@link #forContext} 按标签裁剪工具子集（不披露即不在
     * 请求的 toolCallbacks 里）、防线② 系统提示词声明"只能使用当前披露的工具"。两者都是
     * <b>上游模型侧</b>的约束 —— 而模型发出的 {@code tool_calls} 是外部输入，
     * 一旦上游返回了子集外的工具名（幻觉、注入、上游缓存了旧工具列表），前两道全部失效。
     * 因此执行前必须再复查一次：<b>不匹配就绝不产生副作用</b>（判据与防线①同源，
     * 不存在"两套口径"的漂移空间）。
     *
     * <p>
     * 未登记元数据的工具名一律放行（与 {@link #forContext} 的"unscoped: always include"
     * 同一口径）：元数据缺失不是"越权"的证据，执行失败由官方循环自己回填。
     *
     * @param name 工具名（模型给的原文）
     * @param ctx  本轮冻结的工作区上下文（与披露时用的是同一份，见 {@code RunSnapshot#getContext()}）
     * @return true = 该工具在当前上下文内本就被披露（可执行）；false = 越 scope（必须拦截）
     */
    public boolean matchesContext(String name, AiContext ctx) {
        ToolMeta meta = metaMap.get(name);
        boolean scopeOk = meta == null || meta.getScopePatterns() == null || meta.getScopePatterns().length == 0
                || matchesAny(meta.getScopePatterns(), buildTags(ctx == null ? AiContext.empty() : ctx));
        return scopeOk && !sheetDisabled(name);
    }

    /**
     * 表格开关是否把该工具挡在披露面之外（AI 表格工具治理批）。
     *
     * <p>
     * 与 {@link #matchesContext} 的关系是<b>互补</b>而非并列：{@code matchesContext = scopeOk AND !sheetDisabled}。
     * 执行兜底据此把"同一种未披露"分成两个机器码 ——
     * {@code SHEET_DISABLED}（管理员关闸，切页面无用，只能改配置重启）与
     * {@code SCOPE_NOT_DISCLOSED}（页面/步骤不对，切过去就能用）。
     * 两者都<b>零副作用</b>，区别只在于把"该怎么办"如实告诉模型与用户（方案 §2.4）。
     */
    public boolean sheetDisabled(String name) {
        return !this.sheetSwitches.allows(name);
    }

    /** 未披露的原因码（{@code SHEET_DISABLED} / {@code SCOPE_NOT_DISCLOSED}）。 */
    public String undisclosedCode(String name) {
        return sheetDisabled(name) ? ErrorCodes.SHEET_DISABLED : ErrorCodes.SCOPE_NOT_DISCLOSED;
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

    /**
     * 执行类别（P1-10 批策略判定用）。未登记/未声明类别一律返回 null ——
     * 停走只对<b>显式声明</b>的 W/J/P 生效，缺元数据不会让普通工具把整批停掉。
     */
    public ToolMeta.Category categoryOf(String name) {
        ToolMeta meta = metaMap.get(name);
        return meta == null ? null : meta.getCategory();
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
        if (uiRegionsPresent(ctx)) tags.add("ui:on");
        return tags;
    }

    /**
     * 批 A（D8-①）：{@code ui:on} 标签的唯一判据 —— 本页是否有已注册的 AI 区域。
     *
     * <p>
     * 语义：{@code extra.uiRegionCount} <b>缺失即视为存在</b>（fail-open；旧客户端 / curl /
     * E2E 桩不带该键时工具不得静默消失），<b>显式 0 才不产标签</b>。非数字/非法值同样按
     * "缺失"处理（宁可多披露，不可误关）。
     *
     * <p>
     * 该标签只用于<b>收窄</b>披露面，永远不能用于授权：伪造 {@code uiRegionCount} 最多让工具
     * 消失，换不来任何未披露的工具（执行兜底 {@link #matchesContext} 用同一份判定）。
     */
    private static boolean uiRegionsPresent(AiContext ctx) {
        if (ctx == null || ctx.getExtra() == null) {
            return true;
        }
        Object raw = ctx.getExtra().get("uiRegionCount");
        if (raw == null) {
            return true;
        }
        try {
            double value = raw instanceof Number number ? number.doubleValue()
                    : Double.parseDouble(String.valueOf(raw).trim());
            return value != 0d;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private boolean matchesAny(String[] patterns, Set<String> tags) {
        for (String p : patterns) {
            if (tags.contains(p)) return true;
        }
        return false;
    }
}
