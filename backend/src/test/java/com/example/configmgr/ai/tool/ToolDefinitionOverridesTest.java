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
        assertEquals("2026-10-27.4", shipped.version(), "改描述必须发新 version");
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
}
