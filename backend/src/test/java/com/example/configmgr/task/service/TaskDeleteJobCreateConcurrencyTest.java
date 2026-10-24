package com.example.configmgr.task.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.service.AsyncJobExecutor;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-13 / Important 1：建作业与删除任务的 TOCTOU。
 *
 * <p>两侧都在事务开始处锁同一 task 行（统一 task → job 顺序），因此两个真实线程并发时
 * <b>只能一方成功</b>：
 * <ul>
 * <li>建作业先赢得 task 锁并提交 → 删除看到 PENDING，返回 409 {@code JOB_ALREADY_RUNNING}；</li>
 * <li>删除先赢得 task 锁并提交 → 建作业拿不到 task 行，返回 404。</li>
 * </ul>
 *
 * <p>这里 {@link AsyncJobExecutor} 被 mock 掉，只验证“建作业事务”与“删除事务”的互斥，
 * 不让异步 runner 把 PENDING 改成 COMPLETED（否则“先建则删 409”的前提不稳定）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r113-delete-create-race;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r113-race-test-files"
})
class TaskDeleteJobCreateConcurrencyTest {

    private static final int ROUNDS = 20;

    @Autowired
    private TaskService taskService;
    @Autowired
    private JobService jobService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;

    @MockitoBean
    private AsyncJobExecutor asyncJobExecutor;

    @Test
    void concurrentCreateAndDeleteHasExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<String> failures = new ArrayList<>();
            for (int round = 0; round < ROUNDS; round++) {
                long taskId = taskService.create(Task.TaskType.EXPORT, "R113-RACE-" + round).getId();
                CyclicBarrier start = new CyclicBarrier(2);

                Future<Throwable> create = pool.submit(() -> {
                    start.await();
                    try {
                        jobService.createAndStart(taskId, Job.JobType.EXPORT);
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

                Throwable createError = create.get(15, TimeUnit.SECONDS);
                Throwable deleteError = delete.get(15, TimeUnit.SECONDS);
                boolean createWon = createError == null;
                boolean deleteWon = deleteError == null;
                if (createWon == deleteWon) {
                    failures.add("round=" + round + " createWon=" + createWon + " deleteWon=" + deleteWon
                            + " createError=" + createError + " deleteError=" + deleteError);
                    continue;
                }

                if (createWon) {
                    assertThat(deleteError)
                            .as("建作业先成功时删除必须 409，round=%s", round)
                            .isInstanceOf(ConflictException.class);
                    assertThat(((ConflictException) deleteError).getCode())
                            .isEqualTo("JOB_ALREADY_RUNNING");
                    assertThat(taskRepository.existsById(taskId)).as("任务仍存在").isTrue();
                    assertThat(jobRepository.findByTaskId(taskId)).as("作业存在且未丢").hasSize(1)
                            .allMatch(j -> j.getStatus() == Job.JobStatus.PENDING);
                } else {
                    assertThat(createError)
                            .as("删除先成功时建作业必须 404，round=%s", round)
                            .isInstanceOf(ResourceNotFoundException.class);
                    assertThat(taskRepository.existsById(taskId)).as("任务已删").isFalse();
                    assertThat(jobRepository.findByTaskId(taskId)).as("无孤儿作业").isEmpty();
                }
            }
            assertThat(failures).as("并发轮次里出现双方同时成功/同时失败：%s", failures).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
