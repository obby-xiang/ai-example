package com.example.configmgr.ai.web;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 收尾守卫⑤（S5.b §6.6 建议 + 裁决"采纳：补只读 /api/ai/tools 端点"）的回归用例。
 *
 * <p>端点回答"当前上下文会向模型披露哪些工具"，供 CI 与排障替代"读后端 DEBUG 日志行"的取证手法
 * （TC15 已改用本端点）。本类断言：披露子集与 {@link ToolRegistry#forContext} <b>同源</b>、
 * 关键分隔（{@code page:tasks} 无 {@code start_publish}；{@code task:IMPORT/PUBLISH} 有 {@code start_publish}
 * 且无 {@code start_export}）、无参时给出全部登记工具、以及"调用它不产生任何副作用"（只读/无状态）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5f-ai-tools-endpoint;DB_CLOSE_DELAY=-1"
})
class AiToolsDisclosureEndpointTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskRepository taskRepository;

    @Test
    void pageTasksDisclosesWorkspaceToolsButNotPublish() throws Exception {
        mockMvc.perform(get("/api/ai/tools").param("page", "tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.context").value("page:tasks"))
                .andExpect(jsonPath("$.data.toolNames").value(hasItem("list_config_defs")))
                .andExpect(jsonPath("$.data.toolNames").value(hasItem("start_export")))
                .andExpect(jsonPath("$.data.toolNames").value(not(hasItem("start_publish"))));
    }

    @Test
    void importPublishStepDisclosesStartPublishWithoutStartExport() throws Exception {
        mockMvc.perform(get("/api/ai/tools").param("taskType", "IMPORT").param("step", "PUBLISH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.context").value("task:IMPORT/PUBLISH"))
                .andExpect(jsonPath("$.data.toolNames").value(hasItem("start_publish")))
                .andExpect(jsonPath("$.data.toolNames").value(not(hasItem("start_export"))));
    }

    @Test
    void disclosureCarriesRiskAndChannelMetadata() throws Exception {
        JsonNode tools = fetchData(null, "IMPORT", "PUBLISH").path("tools");
        assertThat(tools.isArray()).isTrue();

        JsonNode publish = null;
        for (JsonNode tool : tools) {
            if ("start_publish".equals(tool.path("name").asText())) {
                publish = tool;
            }
        }
        assertThat(publish).as("task:IMPORT/PUBLISH 应披露 start_publish").isNotNull();
        assertThat(publish.path("riskLevel").asText()).isEqualTo("DANGER");
        assertThat(publish.path("channel").asText()).isEqualTo("BACKEND");
        assertThat(publish.path("scopePatterns").isArray()).isTrue();
    }

    /**
     * 无参 = 上下文 {@code *}（无页面/无任务），披露的是"任何上下文都可见"的工具子集；
     * 带 scope 的工具（如 {@code start_publish}）不在其中 —— 与
     * {@link ToolRegistry#forContext} 对空上下文的裁剪完全一致。
     */
    @Test
    void withoutParamsDisclosesUnscopedSubsetOfRegisteredTools() throws Exception {
        JsonNode data = fetchData(null, null, null);
        assertThat(data.path("context").asText()).isEqualTo("*");

        int disclosed = data.path("count").asInt();
        int registered = toolRegistry.registeredCount();
        assertThat(disclosed).isGreaterThan(0);
        assertThat(disclosed).isLessThanOrEqualTo(registered);
        assertThat(toolNames(data)).containsExactlyElementsOf(
                toolRegistry.forContext(AiContext.empty()).stream()
                        .map(callback -> callback.getToolDefinition().name())
                        .toList());
        assertThat(toolNames(data)).as("带 scope 的工具不在空上下文的披露集里")
                .doesNotContain("start_publish");
    }

    @Test
    void disclosureIsSameSourceAsToolRegistry() throws Exception {
        for (Object[] ctx : List.of(
                new Object[] { "tasks", null, null },
                new Object[] { null, "IMPORT", "PUBLISH" },
                new Object[] { null, "EXPORT", null })) {
            String page = (String) ctx[0];
            String taskType = (String) ctx[1];
            String step = (String) ctx[2];

            List<String> endpoint = toolNames(fetchData(page, taskType, step));
            List<String> registry = toolRegistry
                    .forContext(AiContext.of(page, taskType, step, null, null)).stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();

            assertThat(endpoint).as("端点和 ToolRegistry.forContext 必须同源（page=%s taskType=%s step=%s）",
                    page, taskType, step).containsExactlyElementsOf(registry);
        }
    }

    @Test
    void endpointIsReadOnlyAndStateless() throws Exception {
        long jobsBefore = jobRepository.count();
        long tasksBefore = taskRepository.count();
        long registeredBefore = toolRegistry.registeredCount();

        fetchData("tasks", null, null);
        fetchData("import", "IMPORT", "PUBLISH");
        fetchData(null, null, null);

        assertThat(jobRepository.count()).as("不得新建作业").isEqualTo(jobsBefore);
        assertThat(taskRepository.count()).as("不得新建任务").isEqualTo(tasksBefore);
        assertThat(toolRegistry.registeredCount()).as("登记表不被端点改动").isEqualTo(registeredBefore);
    }

    // ───────────────────────── helpers ─────────────────────────

    private JsonNode fetchData(String page, String taskType, String step) throws Exception {
        var request = get("/api/ai/tools");
        if (page != null) {
            request = request.param("page", page);
        }
        if (taskType != null) {
            request = request.param("taskType", taskType);
        }
        if (step != null) {
            request = request.param("step", step);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private List<String> toolNames(JsonNode data) {
        List<String> names = new ArrayList<>();
        data.path("toolNames").forEach(node -> names.add(node.asText()));
        return names;
    }
}
