package com.example.configmgr.data.service;

import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.service.ExportJobRunner;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 收尾守卫③（S5.a §5 待裁决 #3 / S5.b 裁决"采纳，优先级最高"）的回归用例：
 * <b>未知操作符抛 {@link IllegalArgumentException}</b>（原为 {@code default → true} 静默放行）。
 *
 * <p>为什么这是守卫而不是风格问题：静默放行 = 忽略该条件 = <b>导出范围比用户预期更宽</b>
 * （用户以为筛掉了，实际全量导出/发布）——数据面后果。
 *
 * <p>两条出口都断言：
 * <ul>
 * <li>列表/计数端点：{@code parseCondition} 在逐行求值前校验 → 400（行数为 0 时同样 400）；</li>
 * <li>导出作业：早失败 → 该配置项与作业 FAILED，且不产出导出文件（绝不产出"更宽"的件）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5f-condition-operator;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/s5f-test-files"
})
class ConditionOperatorGuardTest {

    private static final String DEF = "SYS_PARAM";

    /** 前端 {@code types/condition.ts#ConditionOperator} 与后端求值器的公共算子集。 */
    private static final List<String> REGISTERED = List.of(
            "EQ", "NE", "CONTAINS", "LIKE", "STARTS_WITH", "IN", "EMPTY", "NOT_EMPTY", "GT", "GTE", "LT", "LTE");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ExportJobRunner exportJobRunner;

    // ── 求值器单元面 ──

    @Test
    void evaluatorThrowsOnUnknownOperator() {
        assertThatThrownBy(() -> ConditionEvaluator.matches(Map.of("code", "CNY"), cond("BETWEEN", "CNY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BETWEEN");
    }

    @Test
    void evaluatorAcceptsEveryRegisteredOperator() {
        for (String op : REGISTERED) {
            assertThatCode(() -> ConditionEvaluator.matches(Map.of("code", "CNY"), cond(op, "CNY")))
                    .as("已登记算子 %s 不得被守卫误伤", op)
                    .doesNotThrowAnyException();
            assertThatCode(() -> ConditionEvaluator.validate(cond(op, "CNY"))).doesNotThrowAnyException();
        }
    }

    @Test
    void validateRejectsUnknownOperatorIndependentOfRowData() {
        assertThatThrownBy(() -> ConditionEvaluator.validate(cond("BETWEEN", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的查询条件操作符");
    }

    // ── REST 通道：列表 / 计数端点 → 400 ──

    @Test
    void listEndpointReturns400OnUnknownOperator() throws Exception {
        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", betweenJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("未知的查询条件操作符")));
    }

    @Test
    void countEndpointReturns400OnUnknownOperator() throws Exception {
        mockMvc.perform(get("/api/data/{def}/count", DEF).param("conditions", betweenJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("未知的查询条件操作符")));
    }

    @Test
    void unknownOperatorIsRejectedEvenWhenNoRowMatches() throws Exception {
        // 极端边界：一个 rowKey 根本不存在的定义也要 400（不能因"恰好没行"而静默通过）
        mockMvc.perform(get("/api/data/{def}", "NO_SUCH_DEF_FOR_GUARD").param("conditions", betweenJson()))
                .andExpect(status().isBadRequest());
    }

    // ── 作业通道：导出作业 FAILED，且不产出导出文件（不静默放宽）──

    @Test
    void exportJobFailsInsteadOfWideningScope() {
        long rowsBefore = dataRowRepository.countByDefCode(DEF);
        assertThat(rowsBefore).as("前置：SYS_PARAM 有已发布行").isGreaterThan(0);

        Task task = taskService.create(Task.TaskType.EXPORT, "S5F-守卫③-未知操作符");
        long taskId = task.getId();
        taskService.selectDefs(taskId, List.of(DEF));
        taskService.setCondition(taskId, DEF, betweenJson());

        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        exportJobRunner.run(job);

        Job finished = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).as("未知操作符 → 作业 FAILED（不得产出一份更宽的文件）")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(finished.getErrorCount()).isEqualTo(1);
        assertThat(taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, DEF, "EXPORT"))
                .as("不得落导出文件").isEmpty();
        assertThat(taskItemRepository.findByTaskIdAndDefCode(taskId, DEF).orElseThrow().getStatus())
                .isEqualTo("FAILED");
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 未知操作符（BETWEEN 只存在于前端表单草案层，传输口径里不应出现）。 */
    private String betweenJson() {
        return "{\"fields\":[{\"fieldCode\":\"paramKey\",\"operator\":\"BETWEEN\",\"value\":\"a\"}]}";
    }

    private QueryCondition cond(String operator, Object value) {
        QueryCondition.FieldCondition fc = new QueryCondition.FieldCondition();
        fc.setFieldCode("code");
        fc.setOperator(operator);
        fc.setValue(value);
        QueryCondition c = new QueryCondition();
        c.setFields(new ArrayList<>(List.of(fc)));
        return c;
    }
}
