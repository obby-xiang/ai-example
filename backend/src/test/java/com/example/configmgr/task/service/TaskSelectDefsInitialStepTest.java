package com.example.configmgr.task.service;

import com.example.configmgr.task.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-28 步骤披露收口（同族残余）：{@link TaskService#selectDefs} 写回的权威步骤必须按
 * <b>任务类型</b>取首步 —— EXPORT 是 {@code SELECT_DEFS}，IMPORT 是 {@code UPLOAD}
 * （{@code TaskSteps.IMPORT} = UPLOAD/PRECHECK/IMPORT/PUBLISH，导入没有 SELECT_DEFS 步）。
 *
 * <p><b>为什么要紧</b>：AI 的披露面按 {@code task:<type>/<step>} 收窄
 * （{@code AiTools#selectDefinitions} 的 {@code @ToolScope} 只在 {@code task:EXPORT/SELECT_DEFS}
 * 与 {@code task:IMPORT/UPLOAD} 披露），而 {@code RunContextRefresher} 刷新上下文时只认
 * {@link TaskService} 的权威 {@code currentStep}。若导入任务被写成 {@code SELECT_DEFS}，
 * 刷出来的上下文就是 {@code task:IMPORT/SELECT_DEFS}：该步不披露 select_definitions 与表格工具，
 * 模型"刚选过配置却看不见选择工具"。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:select-defs-initial-step;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0"
})
class TaskSelectDefsInitialStepTest {

    @Autowired
    private TaskService taskService;

    @Test
    void importSelectDefsResetsAuthoritativeStepToUpload() {
        Task task = this.taskService.create(Task.TaskType.IMPORT, "AI导入-首步归属");
        // 现场：勾选可能发生在任务已推进过之后（再次勾选/换配置项）⇒ 权威步骤必须回到该类型的首步
        this.taskService.goToStep(task.getId(), "PRECHECK");

        Task updated = this.taskService.selectDefs(task.getId(), List.of("SYS_PARAM"));

        assertThat(updated.getCurrentStep())
                .as("导入首步是 UPLOAD：select_defs 不得写入 EXPORT 的 SELECT_DEFS（导入没有这一步）")
                .isEqualTo("UPLOAD");
        // 权威值确实落库（不只是返回对象上改了一把）
        assertThat(this.taskService.findByIdRaw(task.getId()).orElseThrow().getCurrentStep())
                .as("落库的 current_step 与返回对象一致")
                .isEqualTo("UPLOAD");
    }

    @Test
    void exportSelectDefsResetsAuthoritativeStepToSelectDefs() {
        Task task = this.taskService.create(Task.TaskType.EXPORT, "AI导出-首步归属");
        this.taskService.goToStep(task.getId(), "QUERY_COND");

        Task updated = this.taskService.selectDefs(task.getId(), List.of("SYS_PARAM"));

        assertThat(updated.getCurrentStep())
                .as("导出首步是 SELECT_DEFS（正控：类型感知修正不得改坏 EXPORT）")
                .isEqualTo("SELECT_DEFS");
        assertThat(this.taskService.findByIdRaw(task.getId()).orElseThrow().getCurrentStep())
                .isEqualTo("SELECT_DEFS");
    }
}
