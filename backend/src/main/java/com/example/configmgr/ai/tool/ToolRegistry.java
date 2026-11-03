package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry of all AI tools. Scans @Tool methods across all beans,
 * enriches with @ToolScope for progressive disclosure.
 *
 * <p>
 * <b>启动失败 vs. 少工具（L-94）</b>：扫描期<b>工具 bean 创建失败必须启动失败</b>（收集全部失败
 * bean 名后一次性抛出，消息点名到 bean）。理由 = 本类自述的"少工具比启动失败更难查"：构造期依赖环
 * 让整批工具静默消失时，应用看起来"正常启动"，症状只剩模型少了一组工具。非工具 bean 的创建失败
 * 仍按扫描噪音跳过（Spring 在真正用到它时照旧失败）。
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

    /** 任务终态：披露面据此收窄"已完成导出任务"的再导出入口。 */
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";

    /** 被终态收窄的工具（只收这一支：COMPLETED 不改变导入/发布链的披露）。 */
    private static final String TOOL_START_EXPORT = AiToolNames.START_EXPORT;

    @PostConstruct
    public void init() {
        // 扫描期创建失败的 bean 名 + 原始异常：工具 bean 的失败必须在扫描结束后整批抛出，
        // 非工具 bean 的失败仍按"扫描噪音"跳过（MethodToolCallbackProvider 对无 @Tool 的 bean 本身就抛错）。
        List<String> failedToolBeans = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();

        // Scan all beans for @Tool annotated methods
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try { bean = applicationContext.getBean(beanName); }
            catch (RuntimeException e) {
                if (isDeclaredToolBean(beanName)) {
                    failedToolBeans.add(beanName);
                    failures.add(e);
                    log.error("工具 bean 创建失败：{}（扫描结束后将中止启动）", beanName, e);
                }
                else {
                    log.debug("扫描期跳过创建失败的 bean（非工具 bean）：{}：{}", beanName, e.toString());
                }
                continue;
            }
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
        if (!failedToolBeans.isEmpty()) {
            // L-94：工具 bean 创建失败绝不能吞（2026-10-23 实证：构造期环让 KB 三工具整批消失，
            // 注册数 37→34，而应用"正常启动"）。少工具比启动失败更难查 ⇒ 收集全部失败后带清单抛。
            throw new IllegalStateException("工具 bean 创建失败（" + failedToolBeans.size() + " 个："
                    + String.join("、", failedToolBeans) + "）—— 启动中止：少一批工具比启动失败更难查",
                    failures.get(0));
        }
        applyOverrides();
        assertDisclosureConsistency();
        log.info("ToolRegistry: registered {} tools（定义来源={}）", callbackMap.size(), snapshotVersion);
    }

    /**
     * 披露不变量（启动期 fail-fast）：<b>回调表 ⊆ 元数据表</b>，且每个已注册工具都有非空 scope。
     *
     * <p>为什么必须启动失败而不是运行期兜底：没有元数据的回调会被
     * {@link #forContext} 当成"unscoped: always include"、被 {@link #matchesContext} 直接放行 ——
     * 于是"漏写 {@code @ToolScope}"变成"该工具在所有页面都披露且都能执行"，是静默扩大授权面。
     * 空 scope 数组同理：披露面按"不匹配任何标签"不披露，执行面旧实现却按 unscoped 放行（分叉）。
     * 本断言把两种分叉在启动期一次性钉死；运行期仍保留同一判据（见两处方法）。
     */
    private void assertDisclosureConsistency() {
        List<String> missingMeta = callbackMap.keySet().stream()
                .filter(name -> !metaMap.containsKey(name))
                .sorted()
                .toList();
        if (!missingMeta.isEmpty()) {
            throw new IllegalStateException("工具回调缺少元数据（回调表必须 ⊆ 元数据表）：" + missingMeta
                    + " —— 启动中止：缺元数据会让该工具在所有上下文披露并放行执行");
        }
        List<String> emptyScope = metaMap.values().stream()
                .filter(meta -> meta.getScopePatterns() == null || meta.getScopePatterns().length == 0)
                .map(ToolMeta::getName)
                .sorted()
                .toList();
        if (!emptyScope.isEmpty()) {
            throw new IllegalStateException("工具 scope 为空（@ToolScope 必须至少声明一个标签）：" + emptyScope
                    + " —— 启动中止：空 scope 会让披露面与执行面得出相反结论");
        }
        assertRiskChannelInvariant();
    }

    /**
     * <b>风险 / 通道不变量（启动期 fail-fast）</b>：
     * <ul>
     * <li>{@code DANGER ⇒ BACKEND}：需要人工确认的高危工具只能是后端可执行通道。
     *     {@code DANGER + FRONTEND} 会让后端按 {@code kind=CONFIRM} 挂起等确认，前端却按通道把同一次
     *     调用当 {@code FRONTEND} 等回灌 —— 两条路径互相等，直接死卡；</li>
     * <li>{@code BACKEND ⇒ READ 或 DANGER}：后端直执行只承载"纯读"与"确认门之后才跑"的工具。
     *     {@code WRITE + BACKEND} 会被 {@code kindOf} 判成 {@code BACKEND} 直接执行 —— 一次可逆写
     *     静默绕过人工确认，与"写操作要确认"的口径相反（可逆写一律走前端通道）。</li>
     * </ul>
     *
     * <p>为什么必须启动失败：运行期只能二选一（要么死卡、要么绕过确认），没有安全的默认。
     * 启动时点名到工具，比上线后在确认卡上发现"点了没反应"或"没点就写了"容易查得多。
     */
    private void assertRiskChannelInvariant() {
        List<String> dangerousFrontend = new ArrayList<>();
        List<String> backendWrite = new ArrayList<>();
        for (ToolMeta meta : metaMap.values()) {
            ToolMeta.RiskLevel risk = meta.getRiskLevel();
            ToolMeta.Channel channel = meta.getChannel();
            if (risk == ToolMeta.RiskLevel.DANGER && channel != ToolMeta.Channel.BACKEND) {
                dangerousFrontend.add(meta.getName());
            }
            if (channel == ToolMeta.Channel.BACKEND && risk != ToolMeta.RiskLevel.READ
                    && risk != ToolMeta.RiskLevel.DANGER) {
                backendWrite.add(meta.getName());
            }
        }
        if (dangerousFrontend.isEmpty() && backendWrite.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("工具风险/通道不变量被破坏（DANGER ⇒ BACKEND；BACKEND ⇒ READ/DANGER）");
        if (!dangerousFrontend.isEmpty()) {
            message.append("：DANGER+FRONTEND（高危动作会死卡）")
                .append(dangerousFrontend.stream().sorted().toList());
        }
        if (!backendWrite.isEmpty()) {
            message.append("：后端直执行的写类工具（会绕过确认门）")
                .append(backendWrite.stream().sorted().toList());
        }
        throw new IllegalStateException(message + " —— 启动中止：运行期只能死卡或绕过确认，没有安全默认");
    }

    /**
     * <b>L-94</b>：该 bean 的<b>声明</b>类型里有没有 {@code @Tool} 方法 —— 只看 BeanDefinition，
     * 绝不实例化（要判定的恰恰是"创建不出来"的那些 bean）。
     *
     * <p>
     * 类型来源两条：{@code beanClassName}（{@code @Component} 等）或 {@code @Bean} 工厂方法的
     * <b>返回类型</b>（工厂方法本身此刻不能调用 —— 它就是要失败的那一个）。两者都解析不出来时
     * 返回 false：无法证明它是工具 bean 就不改变既有语义（宁可漏判，不可误伤）。
     */
    private boolean isDeclaredToolBean(String beanName) {
        try {
            ConfigurableListableBeanFactory beanFactory =
                    (ConfigurableListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
            return hasToolMethod(declaredType(beanFactory.getBeanDefinition(beanName), beanFactory));
        }
        catch (RuntimeException e) {
            // 手工注册的单例没有 BeanDefinition 等：无法判定 ⇒ 按非工具 bean 处理
            log.debug("无法解析 bean 的声明类型：{}：{}", beanName, e.toString());
            return false;
        }
    }

    /** BeanDefinition → 声明类型（{@code null} = 解析不出，调用方按"非工具 bean"处理）。 */
    private static Class<?> declaredType(BeanDefinition definition, ConfigurableListableBeanFactory beanFactory) {
        if (definition.getBeanClassName() != null) {
            return resolveClass(definition.getBeanClassName());
        }
        String factoryBeanName = definition.getFactoryBeanName();
        String factoryMethodName = definition.getFactoryMethodName();
        if (factoryBeanName == null || factoryMethodName == null) {
            return null;
        }
        BeanDefinition factoryDefinition = beanFactory.getBeanDefinition(factoryBeanName);
        Class<?> factoryType = factoryDefinition.getBeanClassName() == null
                ? null : resolveClass(factoryDefinition.getBeanClassName());
        if (factoryType == null) {
            return null;
        }
        // 工厂方法可能是包级私有（测试替身常见），故走 getDeclaredMethods 并沿继承链找
        for (Class<?> current = ClassUtils.getUserClass(factoryType); current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethodName)) {
                    return method.getReturnType();
                }
            }
        }
        return null;
    }

    private static Class<?> resolveClass(String className) {
        try {
            return ClassUtils.forName(className, ToolRegistry.class.getClassLoader());
        }
        catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** 沿继承链找 {@code @Tool}（{@link ClassUtils#getUserClass} 先剥掉 CGLIB 代理子类）。 */
    private static boolean hasToolMethod(Class<?> type) {
        if (type == null) {
            return false;
        }
        for (Class<?> current = ClassUtils.getUserClass(type); current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getAnnotation(Tool.class) != null) {
                    return true;
                }
            }
        }
        return false;
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
                    return scopeAllows(meta, tags) && stateAllows(e.getKey(), ctx)
                            && this.sheetSwitches.allows(e.getKey());
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
        AiContext effective = ctx == null ? AiContext.empty() : ctx;
        ToolMeta meta = metaMap.get(name);
        boolean scopeOk = meta == null || scopeAllows(meta, buildTags(effective));
        return scopeOk && stateAllows(name, effective) && !sheetDisabled(name);
    }

    /**
     * scope 披露判据（披露面 {@link #forContext} 与执行面 {@link #matchesContext} 共用）。
     *
     * <p>空数组 = <b>谁都不匹配</b>（与 {@code matchesAny([], tags)} 同结论）：两侧同判、
     * 不存在"披露不出去却在执行时放行"的分叉；启动期另有 {@link #assertDisclosureConsistency}
     * 把空 scope 直接判成启动失败。
     */
    private static boolean scopeAllows(ToolMeta meta, Set<String> tags) {
        String[] patterns = meta.getScopePatterns();
        return patterns != null && patterns.length > 0 && matchesAny(patterns, tags);
    }

    /**
     * 任务终态收窄（q4 的"COMPLETED 仍披露 start_export"）：已完成任务不再披露/放行再导出，
     * 引导改走 {@code download_export_files} / {@code open_export_file_editor}。
     *
     * <p>只收窄 {@code start_export} 一支：导入任务的 COMPLETED 可能只是"预检查通过"，
     * 后续导入/发布仍应可用 —— 把整组 {@code start_*} 一起收窄会误杀主流程。
     * 该判据只是"让模型看不见假入口"，执行面仍有预检与域层终态守卫兜底。
     */
    private static boolean stateAllows(String name, AiContext ctx) {
        if (ctx == null || !TOOL_START_EXPORT.equals(name)) {
            return true;
        }
        return !TASK_STATUS_COMPLETED.equalsIgnoreCase(ctx.getTaskStatus());
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

    /**
     * 该工具被哪一扇闸门关掉（{@code read-enabled} / {@code write-enabled}；未关 ⇒ {@code null}）。
     *
     * <p>回执文案据此点名"该去看哪个键"—— 双档工具关读闸时不得报 write-enabled（见
     * {@link SheetSwitches#disabledGate(String)}）。
     */
    public String sheetDisabledGate(String name) {
        return this.sheetSwitches.disabledGate(name);
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

    private static boolean matchesAny(String[] patterns, Set<String> tags) {
        for (String p : patterns) {
            if (tags.contains(p)) return true;
        }
        return false;
    }
}
