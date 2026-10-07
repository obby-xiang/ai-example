package com.example.configmgr.definition.service;

import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S5.a 用例（移植自 glm-5.3 {@code CatalogServiceTest}）：配置目录（种子并集）与任务创建/列表。
 *
 * <p>glm 侧的被测面是 {@code CatalogService#listConfigs()} + {@code TaskService}；
 * 本仓库的同一职责由 {@link DefinitionService}（定义目录）、{@code ConfigDataRowRepository}
 * （种子落库行数）与 {@link TaskService}（任务创建/检索）承担。
 *
 * <p><b>语义改写点</b>：
 * <ul>
 * <li>glm 的 8 个配置项在并集仓库里是"基座 7 + glm 8 = 15"，
 *     故此处断言 glm 的 8 个编码（含层级分布）而非总数 8，并额外锁定并集总数 15；</li>
 * <li>glm 的 {@code dependsOn}（定义级 JSON）在本仓库以 REFERENCE 字段表达 →
 *     断言 {@code ALARM_THRESHOLD.metricCode} 的 refDefCode 指向 {@code METRIC_DICT}；</li>
 * <li>glm 的 {@code DECIMAL} 字段类型在本仓库是 {@code NUMBER}；</li>
 * <li>glm 的"未选配置不能设条件"由 {@code TaskService#setCondition} 的落库守卫承担
 *     （见 {@code ExportFlowJobTest}），本类只覆盖创建/列表；
 *     任务参数包（{@code params.uploads}）在本仓库没有对应物（上传走 task_files）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5a-catalog;DB_CLOSE_DELAY=-1"
})
class GlmSeedCatalogTest {

    /** glm 并集移植的 8 个定义编码（{@code GlmSeedService#specs}）。 */
    private static final List<String> GLM_CODES = List.of(
            "SYS_PARAM", "METRIC_DICT", "ALARM_THRESHOLD", "ROLE_DICT",
            "REGION_NETWORK", "REGION_TARIFF", "PROJECT_MEMBER", "PROJECT_ENV");

    @Autowired
    private DefinitionService definitionService;

    @Autowired
    private ConfigDataRowRepository dataRowRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private MockMvc mockMvc;

    // ── 用例 1：种子并集提供 glm 的 8 个配置项（4 GLOBAL / 2 REGION / 2 PROJECT）──

    @Test
    void seedProvidesGlmConfigsAcrossThreeLevels() {
        assertThat(definitionService.count())
                .as("基座 7 个定义 + glm 种子并集 8 个定义")
                .isEqualTo(15);

        List<ConfigDefinition> glmDefs = GLM_CODES.stream().map(definitionService::findByCode).toList();
        Map<ConfigDefinition.ConfigLevel, Long> byLevel = glmDefs.stream()
                .collect(Collectors.groupingBy(ConfigDefinition::getLevel, Collectors.counting()));
        assertThat(byLevel)
                .containsEntry(ConfigDefinition.ConfigLevel.GLOBAL, 4L)
                .containsEntry(ConfigDefinition.ConfigLevel.REGION, 2L)
                .containsEntry(ConfigDefinition.ConfigLevel.PROJECT, 2L);

        // 大表（验证导出进度）
        assertThat(dataRowRepository.countByDefCode("ALARM_THRESHOLD")).isEqualTo(120);

        // 依赖声明：glm 的 dependsOn=[METRIC_DICT] 在本仓库落为 REFERENCE 字段
        ConfigDefinition alarm = definitionService.findByCode("ALARM_THRESHOLD");
        ConfigField metricCode = fieldOf(alarm, "metricCode");
        assertThat(metricCode.getFieldType()).isEqualTo(ConfigField.FieldType.REFERENCE);
        assertThat(metricCode.getRefDefCode()).isEqualTo("METRIC_DICT");
        assertThat(metricCode.getRefFieldCode()).isEqualTo("metricCode");

        // 字段动态结构：阈值是 NUMBER（glm 的 DECIMAL）且必填
        ConfigField warnThreshold = fieldOf(alarm, "warnThreshold");
        assertThat(warnThreshold.getFieldType()).isEqualTo(ConfigField.FieldType.NUMBER);
        assertThat(warnThreshold.isRequired()).isTrue();
        assertThat(alarm.getFields()).isNotEmpty();
    }

    // ── 用例 2：创建任务立即落库，步骤初值按任务类型给定 ──

    @Test
    void taskCreatePersistsImmediately() {
        Task task = taskService.create(Task.TaskType.EXPORT, "S5A-创建即落库");

        assertThat(task.getId()).isNotNull();
        assertThat(task.getStatus()).as("新建任务为活动态").isEqualTo(Task.TaskStatus.ACTIVE);
        assertThat(task.getCurrentStep()).as("导出任务首步").isEqualTo("SELECT_DEFS");

        Task loaded = taskService.findById(task.getId());
        assertThat(loaded.getId()).isEqualTo(task.getId());
        assertThat(loaded.getItems()).as("未勾选配置项时无任务条目").isEmpty();

        assertThat(taskService.create(Task.TaskType.IMPORT, "S5A-创建即落库-导入").getCurrentStep())
                .as("导入任务首步")
                .isEqualTo("UPLOAD");
    }

    // ── 用例 3：未知任务类型被拒 ──

    @Test
    void taskCreateRejectsUnknownType() throws Exception {
        // glm 断言 BizException("不支持的任务类型")；本仓库任务类型是枚举，
        // 字符串入口只有 REST（TaskController#create → TaskType.valueOf）→ 400
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UNKNOWN")));
    }

    // ── 用例 4：任务列表返回刚创建的任务 ──

    @Test
    void listTasksReturnsCreated() {
        Task export = taskService.create(Task.TaskType.EXPORT, "S5A-CAT-导出");
        Task imp = taskService.create(Task.TaskType.IMPORT, "S5A-CAT-导入");

        Page<Task> page = taskService.search(null, null, "S5A-CAT-", PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Task::getId)
                .contains(export.getId(), imp.getId());
        assertThat(page.getContent()).allSatisfy(t -> {
            assertThat(t.getStatus()).as("列表项必须带状态").isNotNull();
            assertThat(t.getCurrentStep()).as("列表项必须带当前步骤").isNotNull();
        });
    }

    private ConfigField fieldOf(ConfigDefinition def, String code) {
        return def.getFields().stream()
                .filter(f -> code.equals(f.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("字段不存在: " + def.getCode() + "." + code));
    }
}
