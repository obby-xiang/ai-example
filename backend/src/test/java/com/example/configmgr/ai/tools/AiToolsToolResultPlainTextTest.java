package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.PlainTextToolResultConverter;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R3-01：11 个 BACKEND 业务工具的文本结果曾走官方 {@code DefaultToolCallResultConverter}，
 * 被二次 JSON 编码（模型看到 {@code "\"- CURRENCY…\\n…"}）。KB 三工具已按 L-88 修好，本类把
 * {@code AiTools} 的同一面钉住。
 *
 * <p>证据面与真机一致：走官方 {@link MethodToolCallbackProvider}（生产注册路径），再看
 * {@code role:tool} 消息里的 {@code message} 首字符 —— 它必须是正文，不是引号。
 *
 * <p>边界（本任务口径）：只给 11 个 BACKEND 业务工具挂 {@link PlainTextToolResultConverter}；
 * 23 个 FRONTEND 哨兵工具（{@code @ToolChannel(FRONTEND)}）<b>不机械全挂</b> —— 它们的方法体
 * 根本不面向模型，结果由前端回灌，维持官方默认转换器。
 */
class AiToolsToolResultPlainTextTest {

    /** 11 个 BACKEND 业务工具（34 个 {@code @Tool} − 23 个 FRONTEND 哨兵）。 */
    private static final Set<String> BACKEND_BUSINESS_TOOLS = Set.of(
            "list_config_defs", "get_config_def", "list_tasks", "get_workspace_state", "check_job_status",
            "get_row_count", "create_task", "start_export", "start_precheck", "start_import", "start_publish");

    private static ToolCallback callback(AiTools tools, String name) {
        return java.util.Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build()
                .getToolCallbacks())
                .filter(candidate -> name.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    private static String modelVisibleMessage(String toolName, String rawResult, String argumentsJson) {
        String modelJson = ToolResultEnvelope.of(ToolOutcome.OK, null, rawResult, argumentsJson)
                .toModelJson(new ToolResultLimiter(new AiProperties()), toolName);
        return ToolResultEnvelope.fromJson(modelJson).message();
    }

    private static ConfigDefinition definition(String code, String name, String fieldCode, String label) {
        ConfigDefinition def = new ConfigDefinition();
        def.setCode(code);
        def.setName(name);
        def.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        ConfigField field = new ConfigField();
        field.setDefCode(code);
        field.setCode(fieldCode);
        field.setLabel(label);
        field.setFieldType(ConfigField.FieldType.STRING);
        def.setFields(new ArrayList<>(List.of(field)));
        return def;
    }

    private static Task task() {
        Task task = new Task();
        task.setId(1L);
        task.setType(Task.TaskType.EXPORT);
        task.setTitle("导出任务");
        task.setCurrentStep("SELECT");
        task.setStatus(Task.TaskStatus.ACTIVE);
        return task;
    }

    private static Job job() {
        Job job = new Job();
        job.setId(9L);
        job.setTaskId(1L);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.RUNNING);
        job.setProgress(1);
        job.setTotal(4);
        job.setErrorCount(0);
        return job;
    }

    private static AiTools tools() {
        DefinitionService definitionService = mock(DefinitionService.class);
        ConfigDefinition currency = definition("CURRENCY", "货币字典", "currencyCode", "货币代码");
        when(definitionService.findAll()).thenReturn(List.of(currency));
        when(definitionService.findByLevel(ConfigDefinition.ConfigLevel.GLOBAL)).thenReturn(List.of(currency));
        when(definitionService.findByCode("CURRENCY")).thenReturn(currency);

        TaskService taskService = mock(TaskService.class);
        when(taskService.findAll()).thenReturn(List.of(task()));
        when(taskService.findById(1L)).thenReturn(task());
        when(taskService.createIdempotent(any(), any(), any()))
                .thenReturn(new TaskService.CreateOutcome(task(), false));

        ConfigDataService dataService = mock(ConfigDataService.class);
        when(dataService.count(any(), any(), any())).thenReturn(4L);

        JobService jobService = mock(JobService.class);
        when(jobService.findById(9L)).thenReturn(job());
        when(jobService.createAndStart(any(), any())).thenReturn(job());

        return new AiTools(definitionService, taskService, dataService, jobService);
    }

    @Test
    void backendBusinessToolsReachTheModelAsPlainTextNotJsonString() {
        AiTools tools = tools();

        Map<String, String> calls = new LinkedHashMap<>();
        calls.put("list_config_defs", "{\"level\":\"GLOBAL\"}");
        calls.put("get_config_def", "{\"code\":\"CURRENCY\"}");
        calls.put("list_tasks", "{}");
        calls.put("get_workspace_state", "{\"taskId\":1}");
        calls.put("check_job_status", "{\"jobId\":9}");
        calls.put("get_row_count", "{\"defCode\":\"CURRENCY\"}");
        calls.put("create_task", "{\"type\":\"EXPORT\",\"title\":\"导出任务\"}");
        calls.put("start_export", "{\"taskId\":1}");
        calls.put("start_precheck", "{\"taskId\":1}");
        calls.put("start_import", "{\"taskId\":1}");
        calls.put("start_publish", "{\"taskId\":1}");

        assertThat(calls.keySet()).as("11 个 BACKEND 业务工具名单必须与审计口径一致")
                .containsExactlyInAnyOrderElementsOf(BACKEND_BUSINESS_TOOLS);

        for (Map.Entry<String, String> call : calls.entrySet()) {
            String raw = callback(tools, call.getKey()).call(call.getValue(),
                    new ToolContext(Map.of("runId", "plain-text-test")));

            assertThat(raw).as("%s 的执行结果必须是正文（不是再编码一次的 JSON 字符串）", call.getKey())
                    .doesNotStartWith("\"");

            String message = modelVisibleMessage(call.getKey(), raw, call.getValue());
            assertThat(message).as("%s 信封 message 不得以引号开头（对应 req-006/req-009 的 \" 证据）",
                    call.getKey()).doesNotStartWith("\"");
        }
    }

    @Test
    void onlyTheElevenBackendBusinessToolsDeclareThePlainTextConverter() {
        Set<String> backend = new LinkedHashSet<>();
        Set<String> frontend = new LinkedHashSet<>();
        Map<String, Class<?>> converters = new TreeMap<>();

        for (Method method : AiTools.class.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) continue;
            converters.put(tool.name(), tool.resultConverter());
            ToolChannel channel = method.getAnnotation(ToolChannel.class);
            if (channel != null && channel.value() == ToolMeta.Channel.FRONTEND) {
                frontend.add(tool.name());
            }
            else {
                backend.add(tool.name());
            }
        }

        assertThat(converters).as("工具总数（11 BACKEND + 23 FRONTEND）").hasSize(34);
        assertThat(frontend).as("FRONTEND 哨兵工具数").hasSize(23);
        assertThat(backend).as("BACKEND 业务工具数").hasSize(11);

        Set<String> withPlainText = new LinkedHashSet<>(converters.entrySet().stream()
                .filter(entry -> entry.getValue() == PlainTextToolResultConverter.class)
                .map(Map.Entry::getKey)
                .toList());
        assertThat(withPlainText).as("只有 11 个 BACKEND 业务工具挂 PlainTextToolResultConverter，FRONTEND 不机械全挂")
                .containsExactlyInAnyOrderElementsOf(BACKEND_BUSINESS_TOOLS);

        converters.forEach((name, converter) -> {
            if (frontend.contains(name)) {
                assertThat(converter).as("FRONTEND 哨兵工具 %s 维持官方默认转换器", name)
                        .isEqualTo(DefaultToolCallResultConverter.class);
            }
        });
    }
}
