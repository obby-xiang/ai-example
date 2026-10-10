package com.example.configmgr.ai.tools;

import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code list_tasks} 截断上限（20）与截断告知的回归锁（数字规格清点裁决②：原值 10 无注释、
 * 无告知、无截断标记 = 隐蔽谎报）。纯单测：只桩 {@link TaskService#findAll()}，
 * 形态同 {@code ConfirmGateDeadlockResumeTest}（不经 Spring 容器）。
 *
 * <h2>为什么必须锁</h2>
 * 本工具是模型判断"任务是否存在、处于哪一步"的入口。两个方向都会制造错误结论：
 * <ul>
 * <li><b>静默截断</b>（旧值 10）—— 模型把不完整清单当完整清单；</li>
 * <li><b>假告知</b>（恰好 20 条也追加"仅显示前 20 条"）—— 模型以为还有更多。</li>
 * </ul>
 * 故三条判据（行数 / 告知文案 / 边界不告知）收敛为两个用例：截断且告知（21 条）、边界不告知（恰 20 条）。
 */
class AiToolsListTasksTruncationTest {

    /** 规格值：与 {@link AiTools#LIST_TASKS_MAX_ROWS} 互为锁（常量为实现侧，本值为裁决侧）。 */
    private static final int SPEC_MAX_ROWS = 20;

    private final TaskService taskService = mock(TaskService.class);

    private final AiTools aiTools = new AiTools(mock(DefinitionService.class), taskService,
            mock(ConfigDataService.class), mock(JobService.class));

    @Test
    void truncatesToMaxRowsAndAppendsTotalCountNotice() {
        when(taskService.findAll()).thenReturn(tasks(SPEC_MAX_ROWS + 1));

        String text = aiTools.listTasks();
        List<String> lines = List.of(text.split("\n", -1));

        assertThat(AiTools.LIST_TASKS_MAX_ROWS).isEqualTo(SPEC_MAX_ROWS);
        assertThat(lines).hasSize(SPEC_MAX_ROWS + 1);
        assertThat(lines.get(lines.size() - 1)).isEqualTo("（共 21 条，仅显示前 20 条）");
        assertThat(text).contains("共 21 条").contains("仅显示前 20 条");
        assertThat(lines.stream().filter(line -> line.startsWith("- #")).count()).isEqualTo(SPEC_MAX_ROWS);
        // 第 21 条只允许出现在告知里的数字中，不得作为任务行出现
        assertThat(text).doesNotContain("任务 21");
    }

    @Test
    void doesNotAppendNoticeWhenRowCountEqualsMaxRows() {
        when(taskService.findAll()).thenReturn(tasks(SPEC_MAX_ROWS));

        String text = aiTools.listTasks();

        assertThat(List.of(text.split("\n", -1))).hasSize(SPEC_MAX_ROWS);
        assertThat(text).doesNotContain("仅显示前").doesNotContain("（共 ");
        assertThat(text).contains("任务 20");
    }

    private static List<Task> tasks(int count) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Task task = new Task();
            task.setId((long) i);
            task.setType(i % 2 == 0 ? Task.TaskType.IMPORT : Task.TaskType.EXPORT);
            task.setTitle("任务 " + i);
            task.setCurrentStep("STEP-" + i);
            task.setStatus(Task.TaskStatus.ACTIVE);
            tasks.add(task);
        }
        return tasks;
    }
}
