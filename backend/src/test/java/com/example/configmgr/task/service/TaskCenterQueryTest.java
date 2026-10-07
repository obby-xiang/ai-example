package com.example.configmgr.task.service;

import com.example.configmgr.task.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4.3 §3 任务中心用例（Q16①②）。
 *
 * <p>排序第二键：同一 createdAt 的多条任务跨页按 id DESC 排定（不重不漏、顺序稳定）；
 * 关键词 LIKE 转义：输入 {@code %} / {@code _} 只作字面量匹配，不再命中全表。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s43b-task-center;DB_CLOSE_DELAY=-1"
})
class TaskCenterQueryTest {

    private static final String TIE_PREFIX = "S43B-tie-";

    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sameCreatedAtIsBrokenByIdAcrossPages() {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            created.add(taskService.create(Task.TaskType.IMPORT, TIE_PREFIX + i).getId());
        }
        // 人为把 createdAt 拉平（跨页稳定性的边界场景）
        Timestamp tie = Timestamp.valueOf("2026-10-07 09:00:00");
        for (Long id : created) {
            jdbcTemplate.update("UPDATE TASKS SET CREATED_AT = ? WHERE ID = ?", tie, id);
        }

        Page<Task> page0 = taskService.search(null, null, TIE_PREFIX, PageRequest.of(0, 5));
        Page<Task> page1 = taskService.search(null, null, TIE_PREFIX, PageRequest.of(1, 5));
        Page<Task> page0Again = taskService.search(null, null, TIE_PREFIX, PageRequest.of(0, 5));

        List<Long> ids0 = page0.getContent().stream().map(Task::getId).toList();
        List<Long> ids1 = page1.getContent().stream().map(Task::getId).toList();

        assertThat(page0.getTotalElements()).isEqualTo(8);
        assertThat(ids0).hasSize(5);
        assertThat(ids1).hasSize(3);
        assertThat(ids0).as("createdAt 并列时按 id DESC 排定").isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(ids1).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
        assertThat(ids0.get(4)).as("第一页最后一页与第二页首条之间也是 id DESC（跨页不重不漏）")
                .isGreaterThan(ids1.get(0));
        assertThat(page0Again.getContent().stream().map(Task::getId).toList())
                .as("重复请求同一页顺序稳定").isEqualTo(ids0);
    }

    @Test
    void likeWildcardsInKeywordAreEscaped() {
        Task percent = taskService.create(Task.TaskType.IMPORT, "S43B-100%覆盖率");
        taskService.create(Task.TaskType.IMPORT, "S43B-plain-title");

        Page<Task> byPercent = taskService.search(null, null, "%", PageRequest.of(0, 50));
        assertThat(byPercent.getContent()).extracting(Task::getId)
                .as("keyword=% 只应命中标题里真的含 % 的任务，而不是全表")
                .containsExactly(percent.getId());

        Page<Task> byUnderscore = taskService.search(null, null, "_", PageRequest.of(0, 50));
        assertThat(byUnderscore.getContent())
                .as("keyword=_ 只应命中标题里真的含下划线的任务（未转义时会全表命中）")
                .isEmpty();

        Page<Task> byBackslash = taskService.search(null, null, "\\", PageRequest.of(0, 50));
        assertThat(byBackslash.getContent())
                .as("转义符自身也必须被当作字面量").isEmpty();
    }
}
