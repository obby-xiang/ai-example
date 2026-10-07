package com.example.configmgr.definition.service;

import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import com.example.configmgr.definition.repo.ConfigFieldRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.4b 遗留 8（P5）用例：{@code DELETE /api/definitions/{code}} 的被引用检查与级联策略
 * （FR-1.4 / ADR-11b：禁止删除的三种引用形态 + 放行时的级联清理与操作流水）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s44f-def-delete;DB_CLOSE_DELAY=-1"
})
class DefinitionDeleteTest {

    private static final String TARGET = "S44F_DEL_TARGET";
    private static final String HOLDER = "S44F_DEL_REFS";
    private static final Long TASK_ID_HOLDER = -1L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ConfigDefinitionRepository definitionRepository;
    @Autowired
    private ConfigFieldRepository fieldRepository;
    @Autowired
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private ConfigStagingRowRepository stagingRowRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdTaskId;

    /**
     * 自产数据清理（S44F 前缀）。用 JDBC 直删而非派生删除方法：{@code @Modifying} 查询要求事务，
     * 而 {@code @AfterEach} 不在事务里（踩过一次 TransactionRequiredException → 残留定义让后续用例 500）。
     */
    @AfterEach
    void cleanup() {
        if (createdTaskId != null) {
            taskService.delete(createdTaskId);
            createdTaskId = null;
        }
        jdbcTemplate.update("DELETE FROM task_items WHERE def_code IN (?, ?)", TARGET, HOLDER);
        jdbcTemplate.update("DELETE FROM config_staging_rows WHERE def_code = ?", TARGET);
        jdbcTemplate.update("DELETE FROM config_data_rows WHERE def_code = ?", TARGET);
        jdbcTemplate.update("DELETE FROM config_fields WHERE def_code IN (?, ?)", TARGET, HOLDER);
        jdbcTemplate.update("DELETE FROM config_definitions WHERE code IN (?, ?)", TARGET, HOLDER);
    }

    @Test
    void deleteUnreferencedDefinitionRemovesItWithItsFieldsAndStagingRows() throws Exception {
        createTarget();
        stagingRowRepository.save(stagingRow("S44F-ROW-1"));
        stagingRowRepository.save(stagingRow("S44F-ROW-2"));

        mockMvc.perform(delete("/api/definitions/{code}", TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.cascadedFields").value(3))
                .andExpect(jsonPath("$.data.cascadedStagingRows").value(2));

        assertThat(definitionRepository.findByCode(TARGET)).isEmpty();
        assertThat(fieldRepository.findByDefCodeOrderBySortOrder(TARGET))
                .as("字段随定义级联删除").isEmpty();
        assertThat(stagingRowRepository.countByDefCode(TARGET))
                .as("未发布暂存行随定义级联清理").isZero();
    }

    @Test
    void deleteIsRejectedWhenDefinitionHasPublishedData() throws Exception {
        createTarget();
        ConfigDataRow row = new ConfigDataRow();
        row.setDefCode(TARGET);
        row.setScopeType("GLOBAL");
        row.setRowKey("S44F-K1");
        row.setDataJson("{\"code\":\"S44F-K1\"}");
        dataRowRepository.save(row);

        mockMvc.perform(delete("/api/definitions/{code}", TARGET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFINITION_HAS_DATA"))
                .andExpect(jsonPath("$.success").value(false));

        assertThat(definitionRepository.findByCode(TARGET)).as("被拒后定义与数据都还在").isPresent();
        assertThat(dataRowRepository.countByDefCode(TARGET)).isEqualTo(1);
    }

    @Test
    void deleteIsRejectedWhenReferencedByAnotherDefinition() throws Exception {
        createTarget();
        createHolderReferencingTarget();

        mockMvc.perform(delete("/api/definitions/{code}", TARGET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFINITION_REFERENCED"));

        assertThat(definitionRepository.findByCode(TARGET)).isPresent();
        assertThat(fieldRepository.findByRefDefCode(TARGET))
                .as("引用关系原样保留（未被级联破坏）").isNotEmpty();
    }

    @Test
    void deleteIsRejectedWhenSelectedByActiveTask() throws Exception {
        createTarget();
        Task task = taskService.create(Task.TaskType.IMPORT, "S44F-def-delete-active");
        createdTaskId = task.getId();
        taskService.selectDefs(createdTaskId, List.of(TARGET));

        mockMvc.perform(delete("/api/definitions/{code}", TARGET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFINITION_IN_ACTIVE_TASK"));

        assertThat(definitionRepository.findByCode(TARGET)).isPresent();
    }

    @Test
    void deleteOfUnknownDefinitionReturns404() throws Exception {
        mockMvc.perform(delete("/api/definitions/{code}", "S44F_NOT_EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deleteIsAllowedOnceTaskReachedTerminalState() throws Exception {
        createTarget();
        Task task = taskService.create(Task.TaskType.IMPORT, "S44F-def-delete-terminal");
        createdTaskId = task.getId();
        taskService.selectDefs(createdTaskId, List.of(TARGET));
        taskService.updateStatus(createdTaskId, Task.TaskStatus.COMPLETED);

        mockMvc.perform(delete("/api/definitions/{code}", TARGET))
                .andExpect(status().isOk());

        assertThat(definitionRepository.findByCode(TARGET)).isEmpty();
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(createdTaskId))
                .as("历史任务的任务条目保留（不篡改已结束任务的台账）").hasSize(1);
    }

    private void createTarget() throws Exception {
        String body = """
                {"code":"%s","name":"S44F 删除目标","level":"GLOBAL",
                 "fields":[
                   {"code":"code","label":"编码","fieldType":"STRING","required":true,"key":true},
                   {"code":"name","label":"名称","fieldType":"STRING","required":true,"key":false},
                   {"code":"amount","label":"金额","fieldType":"NUMBER","required":false,"key":false}
                 ]}
                """.formatted(TARGET);
        mockMvc.perform(post("/api/definitions").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void createHolderReferencingTarget() throws Exception {
        String body = """
                {"code":"%s","name":"S44F 引用方","level":"GLOBAL",
                 "fields":[
                   {"code":"code","label":"编码","fieldType":"STRING","required":true,"key":true},
                   {"code":"currencyCode","label":"币种","fieldType":"REFERENCE","required":false,"key":false,
                    "refDefCode":"%s","refFieldCode":"code"}
                 ]}
                """.formatted(HOLDER, TARGET);
        mockMvc.perform(post("/api/definitions").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private ConfigStagingRow stagingRow(String rowKey) {
        ConfigStagingRow row = new ConfigStagingRow();
        row.setTaskId(TASK_ID_HOLDER);
        row.setDefCode(TARGET);
        row.setRowKey(rowKey);
        row.setScopeType("GLOBAL");
        row.setDataJson("{\"code\":\"" + rowKey + "\"}");
        row.setStatus("STAGED");
        return row;
    }
}
