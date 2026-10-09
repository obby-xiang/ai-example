package com.example.configmgr.ai.tool;

import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * issue #3 裁决③ 用例：系统提示词里「已选配置项」的取数来源。
 *
 * <p>
 * 手动勾选/减选<b>不落库</b>（落库会把后端 {@code currentStep} 强制回退到选择步，见遗留 L-1），
 * 因此任务条目代表"上一次落库的集合"，而页面 {@code extra.selectedDefs} 才代表"用户当下选的"。
 * 两者对立时（减选场景）模型会答错 —— 本用例锁三条：
 * <ol>
 * <li>页面已同步（含<b>空集合</b>）⇒ 以页面真值为准；</li>
 * <li>页面<b>从未同步</b> ⇒ 退回任务条目（不把"不知道"当成"空选择"）；</li>
 * <li>两边都没有 ⇒ 该行仍不出现（与改动前一致）。</li>
 * </ol>
 *
 * <p>
 * 每个用例自建 mock 与数据，互不依赖（不共享字段级可变夹具）。
 */
class ContextBuilderSelectedDefsTest {

    private static final long TASK_ID = 7L;

    private final TaskService taskService = mock(TaskService.class);

    private final ContextBuilder builder = new ContextBuilder(this.taskService, new ObjectMapper());

    private void givenTaskItems(String... defCodes) {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setType(Task.TaskType.EXPORT);
        task.setTitle("用例任务");
        task.setCurrentStep("SELECT_DEFS");
        task.setStatus(Task.TaskStatus.ACTIVE);
        List<TaskItem> items = new ArrayList<>();
        for (int i = 0; i < defCodes.length; i++) {
            TaskItem item = new TaskItem();
            item.setTaskId(TASK_ID);
            item.setDefCode(defCodes[i]);
            item.setSortOrder(i);
            items.add(item);
        }
        task.setItems(items);
        when(this.taskService.findById(TASK_ID)).thenReturn(task);
    }

    private static Map<String, Object> extra(String pageId, List<String> selectedDefs) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("pageId", pageId);
        extra.put("selectedDefs", selectedDefs);
        return extra;
    }

    @Test
    void pageSelectionWinsOverStaleTaskItems() {
        givenTaskItems("CURRENCY", "DOC_TYPE");

        String message = this.builder.buildContextMessage(
                AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, extra("export", List.of("CURRENCY"))));

        assertThat(message).contains("已选配置项: CURRENCY（来源：页面实时勾选）");
        assertThat(message).doesNotContain("DOC_TYPE");
    }

    @Test
    void emptyPageSelectionIsAuthoritativeOverNonEmptyTaskItems() {
        // 减选到空：页面已同步空集合，这是有效信息，不能被"任务条目里还有两个"盖掉
        givenTaskItems("CURRENCY", "DOC_TYPE");

        String message = this.builder.buildContextMessage(
                AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, extra("export", List.of())));

        assertThat(message).contains("已选配置项: （空）（来源：页面实时勾选）");
        assertThat(message).doesNotContain("来源：任务条目");
        assertThat(message).doesNotContain("CURRENCY");
    }

    @Test
    void taskItemsAreTheFallbackWhenPageNeverSynced() {
        // 任务中心（pageId=tasks）不拥有选中集 ⇒ 页面态不成立，退回任务条目
        givenTaskItems("CURRENCY", "DOC_TYPE");

        String message = this.builder.buildContextMessage(
                AiContext.of("tasks", null, null, TASK_ID, extra("tasks", List.of())));

        assertThat(message).contains("已选配置项: CURRENCY, DOC_TYPE（来源：任务条目）");
    }

    @Test
    void taskItemsAreTheFallbackWhenClientNeverReportsSelectedDefs() {
        // 客户端在向导页上却根本没带 selectedDefs 键：判为"从未同步"，不得当成空选择
        givenTaskItems("CURRENCY");

        String message = this.builder.buildContextMessage(
                AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, Map.of("pageId", "export")));

        assertThat(message).contains("已选配置项: CURRENCY（来源：任务条目）");
    }

    @Test
    void selectionOnNonWizardPageIsNotTreatedAsAuthoritative() {
        // 数据浏览页沿用了上一个任务的 taskId：这里的 selectedDefs 对该任务无意义 ⇒ 退回任务条目
        givenTaskItems("CURRENCY");

        String message = this.builder.buildContextMessage(
                AiContext.of("data", null, null, TASK_ID, extra("data", List.of("DOC_TYPE"))));

        assertThat(message).contains("已选配置项: CURRENCY（来源：任务条目）");
        assertThat(message).doesNotContain("CURRENCY, DOC_TYPE");
    }

    @Test
    void noSelectedDefsLineWhenNeitherPageNorTaskHasSelection() {
        // 页面从未同步（任务中心）且任务也没有条目 ⇒ 该行仍不出现（与改动前一致）
        givenTaskItems();

        String message = this.builder.buildContextMessage(
                AiContext.of("tasks", null, null, TASK_ID, extra("tasks", List.of())));

        assertThat(message).contains("任务: #7 [EXPORT]").doesNotContain("已选配置项");
    }

    @Test
    void emptyPageSelectionStillRendersWhenTaskHasNoItems() {
        // 页面同步了空集合就是有效信息 —— 即使任务条目也是空的，也要如实说"页面当前没勾"
        givenTaskItems();

        String message = this.builder.buildContextMessage(
                AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, extra("export", List.of())));

        assertThat(message).contains("已选配置项: （空）（来源：页面实时勾选）");
    }

    @Test
    void selectionPageIdsMirrorFrontendWizardPages() {
        // 与前端 enterPage({pageId, ..., selectedDefs}) 的调用点逐页对应（多/少都算契约漂移）
        assertThat(ContextBuilder.SELECTION_PAGE_IDS).containsExactly("export", "import");
    }
}
