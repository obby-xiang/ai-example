package com.example.configmgr.task.controller;

import com.example.configmgr.task.repo.TaskItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DC-14 B1 用例：{@code TaskController#setCondition} 的条件必须<b>序列化后合法 JSON</b> 落库。
 *
 * <p>
 * 原实现用 {@code body.get("condition").toString()} —— 前端 PUT 的 {@code condition} 是<b>对象</b>
 * （{@code api/tasks.ts#setCondition} 传契约事件里的 {@code conditions}），
 * {@code Map.toString()} 会落出 {@code {scopeKeys=[XN]}} 这类"看着像 JSON 其实不是"的文本：
 * 本请求 200 成功，而所有读侧（导出作业、{@code QueryCondition}、前端 {@code parseConditionJson}）
 * 在**别的请求**里静默失败。本用例就是把这个隐性失败面钉死。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dc14-task-condition;DB_CLOSE_DELAY=-1"
})
class TaskConditionJsonTest {

    private static final String DEF_CODE = "CURRENCY";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskItemRepository taskItemRepository;

    @Test
    void objectConditionIsStoredAsValidJson() throws Exception {
        long taskId = newTask();

        String body = "{\"condition\":{\"scopeKeys\":[\"XN\"],"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        this.mockMvc.perform(put("/api/tasks/{id}/items/{def}/condition", taskId, DEF_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String stored = storedCondition(taskId);
        // ① 落库文本可被标准 JSON 解析（原实现这里会抛错）
        JsonNode parsed = this.objectMapper.readTree(stored);
        assertThat(parsed.path("scopeKeys").get(0).asText()).isEqualTo("XN");
        assertThat(parsed.path("fields").get(0).path("fieldCode").asText()).isEqualTo("code");
        assertThat(parsed.path("fields").get(0).path("operator").asText()).isEqualTo("EQ");
        assertThat(parsed.path("fields").get(0).path("value").asText()).isEqualTo("CNY");
        // ② 不含 Java toString 的痕迹
        assertThat(stored).doesNotContain("scopeKeys=[");
    }

    @Test
    void jsonStringConditionIsKeptVerbatim() throws Exception {
        long taskId = newTask();

        String json = "{\"fields\":[{\"fieldCode\":\"name\",\"operator\":\"CONTAINS\",\"value\":\"元\"}]}";
        this.mockMvc.perform(put("/api/tasks/{id}/items/{def}/condition", taskId, DEF_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(java.util.Map.of("condition", json))))
                .andExpect(status().isOk());

        assertThat(storedCondition(taskId)).isEqualTo(json);
    }

    @Test
    void invalidJsonStringIsRejectedWith400() throws Exception {
        long taskId = newTask();

        this.mockMvc.perform(put("/api/tasks/{id}/items/{def}/condition", taskId, DEF_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"condition\":\"{scopeKeys=[XN]}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("合法 JSON")));
    }

    @Test
    void missingConditionFallsBackToEmptyObject() throws Exception {
        long taskId = newTask();

        this.mockMvc.perform(put("/api/tasks/{id}/items/{def}/condition", taskId, DEF_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(storedCondition(taskId)).isEqualTo("{}");
    }

    private long newTask() throws Exception {
        String created = this.mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPORT\",\"title\":\"DC14T 条件落库用例\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long taskId = this.objectMapper.readTree(created).path("data").path("id").asLong();
        // 条件只对**已勾选**的配置项有效（表单/服务层同口径）
        this.mockMvc.perform(post("/api/tasks/{id}/select-defs", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defCodes\":[\"" + DEF_CODE + "\"]}"))
                .andExpect(status().isOk());
        return taskId;
    }

    private String storedCondition(long taskId) {
        return this.taskItemRepository.findByTaskIdAndDefCode(taskId, DEF_CODE)
                .map(item -> item.getConditionJson())
                .orElseThrow(() -> new AssertionError("条件未落库：task=" + taskId));
    }

}
