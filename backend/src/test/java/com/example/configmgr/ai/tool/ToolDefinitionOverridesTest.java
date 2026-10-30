package com.example.configmgr.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-E：工具定义外置（E1/E2/E3/E4/E8/E9）。
 *
 * <p>
 * 证红证据（施工前）：{@code ToolRegistry} 只能从注解取描述、展示名是"描述前 20 字"、
 * 无任何定义来源抽象 ⇒ 覆盖类用例编译即失败；装饰器只有单参 {@code call} 时 E8 会拿到 null context。
 */
class ToolDefinitionOverridesTest {

    /** 被测工具（模型侧描述 = 注解中文）。 */
    static class SampleTools {
        @Tool(name = "sample_tool", description = "示例工具：读取某物的摘要")
        public String sample(@ToolParam(description = "目标编码", required = true) String code) {
            return "ok:" + code;
        }
    }

    // ---------------------------------------------------------------- E8 装饰器

    @Test
    void decoratorForwardsBothCallOverloadsIncludingToolContext() {
        RecordingCallback delegate = new RecordingCallback();
        ToolDefinition definition = ToolDefinition.builder()
                .name("sample_tool").description("覆盖后的描述").inputSchema("{\"type\":\"object\"}")
                .build();
        OverriddenToolCallback callback = new OverriddenToolCallback(delegate, definition);

        assertEquals("覆盖后的描述", callback.getToolDefinition().description());
        assertEquals("plain", callback.call("{\"code\":\"A\"}"));
        ToolContext context = new ToolContext(java.util.Map.of("runId", "run-9"));
        assertEquals("with-context", callback.call("{\"code\":\"B\"}", context));
        assertEquals("run-9", delegate.seenRunId, "两参重载必须原样委托（否则 ToolContext 静默丢失）");
        assertEquals(2, delegate.calls);
    }

    // ---------------------------------------------------------------- E2 缺省与解析

    @Test
    void yamlSourceToleratesMissingFileAndEmptyEntries() {
        YamlToolDefinitionSource missing = new YamlToolDefinitionSource(
                new ClassPathResource("ai/does-not-exist.yaml"));
        assertTrue(missing.load().isEmpty(), "缺文件 ⇒ 空快照（全部回落注解）");
        assertEquals("none", missing.version());

        YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();
        assertTrue(shipped.toolKeys().contains("list_config_defs"));
        assertNotNull(shipped.load().find("list_config_defs").title());
    }

    @Test
    void overrideOnlyCarriesWhatIsWritten() {
        YamlToolDefinitionSource source = new YamlToolDefinitionSource(new ByteArrayResource("""
                version: test-1
                lang: zh
                tools:
                  - toolKey: sample_tool
                    title: 示例工具
                """.getBytes(StandardCharsets.UTF_8)));
        ToolDefinitionOverride override = source.load().find("sample_tool");
        assertEquals("示例工具", override.title());
        assertEquals("tool.sample_tool.title", override.effectiveTitleKey(), "titleKey 缺省按约定推导");
        assertEquals("sample_tool", override.effectiveModelName());
        assertTrue(!override.hasDescription() && !override.hasSchema(), "未写的字段不得造出默认值");
    }

    @Test
    void duplicateToolKeyFailsFast() {
        ByteArrayResource resource = new ByteArrayResource("""
                version: test-2
                tools:
                  - toolKey: a
                  - toolKey: a
                """.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> new YamlToolDefinitionSource(resource));
    }

    // ---------------------------------------------------------------- E3 schema 对账

    @Test
    void schemaDriftFailsFast() {
        String reflected = "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[\"code\"]}";
        String drifted = "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[]}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ToolSchemaReconciler.reconcile("sample_tool", reflected, drifted));
        assertTrue(error.getMessage().contains("required"), error.getMessage());
    }

    @Test
    void schemaEqualIgnoresKeyOrder() {
        String reflected = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}},\"required\":[\"a\",\"b\"]}";
        String same = "{\"type\":\"object\",\"required\":[\"b\",\"a\"],\"properties\":{\"b\":{\"type\":\"string\"},\"a\":{\"type\":\"string\"}}}";
        ToolSchemaReconciler.reconcile("t", reflected, same);
    }

    // ---------------------------------------------------------------- E1/E4/E9 注册表集成

    @Test
    void registryAppliesOverrideWithoutTouchingExecution() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SampleTools.class);
            context.registerBean(ToolRegistry.class);
            CountingSource source = new CountingSource(new ByteArrayResource("""
                    version: test-3
                    lang: zh
                    tools:
                      - toolKey: sample_tool
                        description: 覆盖后的模型侧描述
                        title: 示例工具
                    """.getBytes(StandardCharsets.UTF_8)));
            context.registerBean(ToolDefinitionSource.class, () -> source);
            context.refresh();

            ToolRegistry registry = context.getBean(ToolRegistry.class);
            ToolCallback callback = registry.getCallback("sample_tool");
            assertInstanceOf(OverriddenToolCallback.class, callback, "有覆盖时必须装饰");
            assertEquals("覆盖后的模型侧描述", callback.getToolDefinition().description());
            assertEquals("\"ok:A\"", callback.call("{\"code\":\"A\"}"), "执行路径原样委托（官方回调对字符串返回值做 JSON 编码）");

            ToolMeta meta = registry.getMeta("sample_tool");
            assertEquals("示例工具", meta.getDisplayName(), "展示名来自 title，不再是描述截断");
            assertEquals("tool.sample_tool.title", meta.getTitleKey());
            assertEquals(1, source.loads, "请求/装配期只加载一次快照（E4 零重复 IO）");
        }
    }

    @Test
    void registryKeepsAnnotationWhenNoOverride() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SampleTools.class);
            context.registerBean(ToolRegistry.class);
            context.registerBean(ToolDefinitionSource.class, () -> new CountingSource(new ByteArrayResource("""
                    version: test-4
                    tools:
                      - toolKey: unrelated_tool
                    """.getBytes(StandardCharsets.UTF_8))));
            context.registerBean("unrelatedTool", UnrelatedTools.class);
            context.refresh();

            ToolRegistry registry = context.getBean(ToolRegistry.class);
            ToolCallback callback = registry.getCallback("sample_tool");
            assertEquals("示例工具：读取某物的摘要", callback.getToolDefinition().description(),
                    "无覆盖 ⇒ 注解描述一字不动");
            assertEquals("sample_tool", registry.getMeta("sample_tool").getDisplayName());
        }
    }

    /** 覆盖指向未注册工具 ⇒ 启动失败（配置错误不静默失效）。 */
    @Test
    void overrideForUnknownToolFailsFast() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SampleTools.class);
            context.registerBean(ToolRegistry.class);
            context.registerBean(ToolDefinitionSource.class, () -> new CountingSource(new ByteArrayResource("""
                    version: test-5
                    tools:
                      - toolKey: ghost_tool
                    """.getBytes(StandardCharsets.UTF_8))));
            Exception error = assertThrows(Exception.class, context::refresh);
            assertTrue(rootMessage(error).contains("ghost_tool"), rootMessage(error));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        StringBuilder text = new StringBuilder();
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                text.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- helpers

    static class UnrelatedTools {
        @Tool(name = "unrelated_tool", description = "无关工具")
        public String unrelated() {
            return "ok";
        }
    }

    /** 计数来源：断言装配期只 load 一次（请求路径零 IO 的可观测面）。 */
    static class CountingSource implements ToolDefinitionSource {
        private final YamlToolDefinitionSource delegate;
        int loads;

        CountingSource(ByteArrayResource resource) {
            this.delegate = new YamlToolDefinitionSource(resource);
        }

        @Override
        public ToolSnapshot load() {
            loads++;
            return delegate.load();
        }

        @Override
        public String version() {
            return delegate.version();
        }
    }

    /** 记录两个重载调用的委托桩（E8）。 */
    static class RecordingCallback implements ToolCallback {
        int calls;
        String seenRunId;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("sample_tool").description("原始描述")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return "plain";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls++;
            Object runId = toolContext == null ? null : toolContext.getContext().get("runId");
            seenRunId = runId == null ? null : String.valueOf(runId);
            return "with-context";
        }
    }

    /** 只为证明 YAML 与注册表一致（本用例的工具集是测试 bean，不依赖 AiTools）。 */
    @Test
    void shippedYamlCoversEveryDeclaredTool() {
        YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();
        List<String> declared = List.of("list_config_defs", "get_config_def", "list_tasks", "get_workspace_state",
                "check_job_status", "get_row_count", "create_task", "start_export", "start_precheck", "start_import",
                "start_publish", "open_export_file_editor", "download_export_files", "navigate_to",
                "select_definitions", "set_condition", "confirm_step", "generative_form", "read_sheet_summary",
                "aggregate_sheet", "inspect_columns", "review_cells", "find_rows", "normalize_column", "replace_values",
                "update_rows", "undo_last_sheet_edit");
        for (String tool : declared) {
            assertNotNull(shipped.load().find(tool), "YAML 缺工具条目：" + tool);
            assertEquals("tool." + tool + ".title", shipped.load().find(tool).effectiveTitleKey());
        }
    }

    /**
     * 多步工作流（2026-10-27）：confirm_step 的模型侧描述改为"推进成功后下一批按最新披露继续、
     * 失败即停"，旧的"推进只对下一轮生效"必须删除；默认不传 step 的口径保留。
     * 描述覆盖存在 YAML 里，改它必须同时升 version。
     */
    @Test
    void shippedConfirmStepOverridePinsDefaultNextStepAndBumpsVersion() {
        YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();
        ToolDefinitionOverride override = shipped.load().find("confirm_step");
        assertNotNull(override);
        assertTrue(override.hasDescription(), "confirm_step 必须保留描述覆盖（否则回落注解）");
        assertTrue(override.description().contains("默认不传 step，只前进一步"), override.description());
        assertTrue(override.description().contains("step 仅在用户点名目标步骤或"), override.description());
        assertTrue(!override.description().contains("推进只对下一轮生效"),
                "旧口径（推进只对下一轮生效）必须删除：" + override.description());
        assertTrue(override.description().contains("下一批按最新披露"), override.description());
        assertTrue(override.description().contains("失败即停"), override.description());
        assertEquals("2026-10-27.15", shipped.version(), "改描述必须发新 version");
    }

    /**
     * 2026-10-28 步骤披露收口：{@code set_condition} 的模型侧描述必须点明它只能在
     * {@code QUERY_COND} 步骤调用，并且当前在 SELECT_DEFS 时要先 {@code confirm_step}
     * （不传 step）推进 —— 旧描述只写"前置：EXPORT 任务且当前在 QUERY_COND 步骤"，
     * 模型在 SELECT_DEFS 被前端拒绝后无从知道下一步该做什么。
     *
     * <p>同时锁住 {@code confirm_step} 的步骤枚举：导出 SELECT_DEFS/QUERY_COND/EXPORT；
     * 导入 UPLOAD/PRECHECK/IMPORT/PUBLISH（**导入没有 SELECT_DEFS 步**）。
     */
    @Test
    void shippedSetConditionOverridePinsQueryCondStepAndConfirmStepGuidance() {
        YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();
        ToolDefinitionOverride setCondition = shipped.load().find("set_condition");
        assertNotNull(setCondition);
        assertTrue(setCondition.hasDescription(), "set_condition 必须保留描述覆盖（否则回落注解）");
        String description = setCondition.description();
        assertTrue(description.contains("QUERY_COND"), description);
        assertTrue(description.contains("先 confirm_step"), description);
        assertTrue(description.contains("推进到 QUERY_COND"), description);
        assertTrue(description.contains("下一批"), description);
        assertTrue(description.contains("SELECT_DEFS"), description);

        ToolDefinitionOverride confirmStep = shipped.load().find("confirm_step");
        assertNotNull(confirmStep);
        String stepEnumeration = confirmStep.description();
        assertTrue(stepEnumeration.contains("SELECT_DEFS/QUERY_COND/EXPORT"), stepEnumeration);
        assertTrue(stepEnumeration.contains("UPLOAD/PRECHECK/IMPORT/PUBLISH"), stepEnumeration);
        assertTrue(!stepEnumeration.contains("导入向导 SELECT_DEFS"),
                "导入向导没有 SELECT_DEFS 步，旧枚举必须删除：" + stepEnumeration);
    }

    /**
     * 2026-10-29 规则冲突统一：<b>任务已完成不等于下载/打开编辑器授权</b>。
     *
     * <p>旧描述把"任务已完成"直接写成改用 {@code download_export_files}（或
     * {@code open_export_file_editor}）的理由 —— 与系统提示词第 25 条"只有用户明确提出下载/获取
     * 文件意图时才可调用"正面冲突，模型据工具描述即可自动下载（副作用）。本用例锁住：三处描述
     * 都必须点名"用户明确意图"，且删除"已完成就改用下载"的旧口径；改描述必须按头部约定升 version。
     */
    @Test
    void shippedDownloadAndEditorRequireExplicitUserIntentAndBumpVersion() {
        YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();

        ToolDefinitionOverride startExport = shipped.load().find("start_export");
        assertNotNull(startExport);
        String startExportDescription = startExport.description();
        assertTrue(!startExportDescription.contains("任务已完成时改用 download_export_files"),
                "旧口径（已完成就改用下载）必须删除：" + startExportDescription);
        assertTrue(startExportDescription.contains("用户明确"), startExportDescription);

        ToolDefinitionOverride download = shipped.load().find("download_export_files");
        assertNotNull(download);
        String downloadDescription = download.description();
        assertTrue(!downloadDescription.contains("任务已完成时用它"),
                "旧口径（已完成为由推荐下载）必须删除：" + downloadDescription);
        assertTrue(downloadDescription.contains("用户明确"), downloadDescription);

        ToolDefinitionOverride editor = shipped.load().find("open_export_file_editor");
        assertNotNull(editor);
        assertTrue(editor.hasDescription(), "open_export_file_editor 必须有描述覆盖（点名用户明确意图）");
        assertTrue(editor.description().contains("用户明确"), editor.description());

        ToolDefinitionOverride confirmStep = shipped.load().find("confirm_step");
        assertNotNull(confirmStep);
        assertTrue(confirmStep.description().contains("未设筛选"), confirmStep.description());
        assertTrue(confirmStep.description().contains("全量"), confirmStep.description());

        assertEquals("2026-10-27.15", shipped.version(), "改描述必须发新 version");
    }

    /**
     * 2026-10-29 审核问题 1/2：<b>新建任务就是新建</b>。旧描述只说"从零创建"，
     * 没挡住模型在已有/已完成任务页面上先追问配置或先 {@code list_config_defs} 摸底，
     * 也没说旧任务的配置/步骤/完成状态不属于新任务 —— 与系统提示词第 30 条冲突。
     *
     * <p>锁两面（YAML 覆盖 + {@code @Tool} 注解回落，YAML 缺描述时模型看到的就是注解那份）。
     */
    @Test
    void shippedCreateTaskDescriptionBansPreCreationQuestionsAndOldTaskInheritance() {
        String yaml = createTaskDescriptionFromYaml();
        String annotation = createTaskDescriptionFromAnnotation();

        for (String description : List.of(yaml, annotation)) {
            assertTrue(description.contains("已完成任务页面"), "建任务不限任务中心：" + description);
            assertTrue(description.contains("不要先追问配置项、筛选条件或数据文件"), description);
            assertTrue(description.contains("list_config_defs 代替创建"), description);
            assertTrue(description.contains("只需要任务类型"), description);
            assertTrue(description.contains("不属于新任务"),
                    "旧任务参数不自动带入新任务的结论必须写明：" + description);
            assertTrue(description.contains("除非用户明确要求沿用"), description);
            assertTrue(description.contains("navigate_to"), description);
        }

        assertEquals("2026-10-27.15", new YamlToolDefinitionSource().version(), "改描述必须发新 version");
    }

    private static String createTaskDescriptionFromYaml() {
        ToolDefinitionOverride createTask = new YamlToolDefinitionSource().load().find("create_task");
        assertNotNull(createTask);
        assertTrue(createTask.hasDescription(), "create_task 必须保留描述覆盖（否则回落注解）");
        return createTask.description();
    }

    private static String createTaskDescriptionFromAnnotation() {
        try {
            java.lang.reflect.Method method = com.example.configmgr.ai.tools.AiTools.class
                .getMethod("createTask", String.class, String.class, String[].class,
                        com.example.configmgr.ai.tools.AiTools.FieldQueryCondition.class, ToolContext.class);
            Tool tool = method.getAnnotation(Tool.class);
            assertNotNull(tool, "AiTools#createTask 必须带 @Tool");
            return tool.description();
        }
        catch (NoSuchMethodException ex) {
            throw new AssertionError("AiTools#createTask(String,String,String[],FieldQueryCondition,ToolContext) 不存在", ex);
        }
    }

    /**
     * 规则冲突统一（2026-10-29 返修）：{@code confirm_step} 的"失败即停"必须保留系统提示词
     * 第 29 条的<b>零副作用一次纠正</b>例外 —— 否则工具描述与提示词给出相反约束（工具描述本身
     * 也进模型上下文）。同参数/同目标仍禁止；YAML 覆盖与 {@code AiTools} 注解回落两面同批。
     */
    @Test
    void shippedConfirmStepKeepsTheRule29ZeroSideEffectCorrectionException() {
        ToolDefinitionOverride confirmStep = new YamlToolDefinitionSource().load().find("confirm_step");
        assertNotNull(confirmStep);
        String description = confirmStep.description();
        assertTrue(description.contains("第 29 条"), description);
        assertTrue(description.contains("零副作用"), description);
        assertTrue(description.contains("一次不同的纠正动作"), description);
        assertTrue(description.contains("同参数"), description);
        assertTrue(description.contains("失败即停"), description);

        String annotation = confirmStepDescriptionFromAnnotation();
        assertTrue(annotation.contains("第 29 条"), annotation);
        assertTrue(annotation.contains("零副作用"), annotation);
    }

    /** {@code @Tool(description=...)} 的回落面（YAML 缺描述时模型看到的那一份）。 */
    private static String confirmStepDescriptionFromAnnotation() {
        try {
            java.lang.reflect.Method method = com.example.configmgr.ai.tools.AiTools.class
                .getMethod("confirmStep", String.class);
            Tool tool = method.getAnnotation(Tool.class);
            assertNotNull(tool, "AiTools#confirmStep 必须带 @Tool");
            return tool.description();
        }
        catch (NoSuchMethodException ex) {
            throw new AssertionError("AiTools#confirmStep(String) 不存在", ex);
        }
    }
}
