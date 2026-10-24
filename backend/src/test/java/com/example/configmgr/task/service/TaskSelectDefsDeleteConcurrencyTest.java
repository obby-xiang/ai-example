package com.example.configmgr.task.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-5 回归：{@link TaskService#selectDefs} 与 {@link TaskService#delete} 的锁序。
 *
 * <p>两侧都必须先锁 task 行，再碰 task_items；否则 delete 持 task 锁等子表行、
 * selectDefs 持子表行锁等 task 行，形成环，H2 死锁检测回滚一方（调用方看到 500）。
 *
 * <p>判定口径：delete 必须成功（任务没有作业，删除无业务冲突）；selectDefs 允许成功
 * 或“任务已被删”的 404；任何数据库锁冲突/死锁异常都判失败。最终任务必须已被删除。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:p15-select-delete-race;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.job.demo-batch-delay-ms=0"
})
class TaskSelectDefsDeleteConcurrencyTest {

    private static final int ROUNDS = 30;

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private DataSource dataSource;

    @Test
    void selectDefsAndDeleteDoNotDeadlockOnTaskChildLockOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<String> failures = new ArrayList<>();
            for (int round = 0; round < ROUNDS; round++) {
                long taskId = taskService.create(Task.TaskType.EXPORT, "P15-RACE-" + round).getId();
                CyclicBarrier start = new CyclicBarrier(2);

                Future<Throwable> select = pool.submit(() -> {
                    start.await();
                    try {
                        taskService.selectDefs(taskId, List.of("SYS_PARAM"));
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                });
                Future<Throwable> delete = pool.submit(() -> {
                    start.await();
                    try {
                        taskService.delete(taskId);
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                });

                Throwable selectError = select.get(15, TimeUnit.SECONDS);
                Throwable deleteError = delete.get(15, TimeUnit.SECONDS);
                if (deleteError != null) {
                    failures.add("round=" + round + " delete failed: " + deleteError);
                    continue;
                }
                if (selectError != null && !(selectError instanceof ResourceNotFoundException)) {
                    failures.add("round=" + round + " selectDefs non-404 failure: " + selectError);
                    continue;
                }
                if (taskRepository.existsById(taskId)) {
                    failures.add("round=" + round + " 任务未被删除");
                }
            }
            assertThat(failures)
                    .as("selectDefs/delete 并发出现死锁或锁冲突（task→child 锁序未统一）：%s", failures)
                    .isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 确定性锁序回归：T1 只锁 task 行（原生 {@code SELECT ... FOR UPDATE}，不连带 item），
     * 随后按 delete 的顺序删 task_items；T2 并发 selectDefs。
     *
     * <ul>
     * <li>selectDefs 先锁 task（修复后）：T2 在入口等待，未持有 item 锁；T1 删 item、提交后 T2 继续 → 双方成功。</li>
     * <li>selectDefs 先读后写（修复前）：T2 先拿到 item 行锁、再等 task 行；T1 等 item 行 → 环形等待，
     *     H2 死锁检测回滚一方。</li>
     * </ul>
     */
    @Test
    void selectDefsLocksTaskBeforeTouchingItemsInsteadOfDeadlockingWithDelete() throws Exception {
        long taskId = taskService.create(Task.TaskType.EXPORT, "P15-LOCK-ORDER").getId();
        TaskItem item = new TaskItem();
        item.setTaskId(taskId);
        item.setDefCode("SYS_PARAM");
        item.setSortOrder(0);
        taskItemRepository.saveAndFlush(item);

        CountDownLatch taskLocked = new CountDownLatch(1);
        CountDownLatch selectStarted = new CountDownLatch(1);
        AtomicReference<Throwable> deleteError = new AtomicReference<>();
        AtomicReference<Throwable> selectError = new AtomicReference<>();

        Thread deleteSide = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT ID FROM TASKS WHERE ID = ? FOR UPDATE")) {
                    lock.setLong(1, taskId);
                    var locked = lock.executeQuery();
                    assertThat(locked.next()).isTrue();
                    locked.close();
                }
                taskLocked.countDown();
                if (!selectStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("selectDefs 未启动");
                }
                // 让 selectDefs（修复前）先拿到 item 行锁并走到等 task 行
                Thread.sleep(800);
                try (Statement delete = connection.createStatement()) {
                    delete.executeUpdate("DELETE FROM TASK_ITEMS WHERE TASK_ID = " + taskId);
                }
                connection.commit();
            } catch (Throwable e) {
                deleteError.set(e);
            }
        }, "p15-delete-side");

        Thread selectSide = new Thread(() -> {
            try {
                if (!taskLocked.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("delete 侧未锁到 task");
                }
                selectStarted.countDown();
                taskService.selectDefs(taskId, List.of("OTHER_DEF"));
            } catch (Throwable e) {
                selectError.set(e);
            }
        }, "p15-select-side");

        deleteSide.start();
        selectSide.start();
        deleteSide.join(15000);
        selectSide.join(15000);

        assertThat(deleteError.get()).as("delete 侧不得因锁冲突/死锁失败").isNull();
        assertThat(selectError.get()).as("selectDefs 不得因锁冲突/死锁失败").isNull();
        assertThat(taskRepository.findById(taskId)).isPresent();
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(taskId))
                .extracting(TaskItem::getDefCode)
                .as("selectDefs 在 delete 释放 task 锁后完成：旧项删除、新项插入")
                .containsExactly("OTHER_DEF");
    }
}
