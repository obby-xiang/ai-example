package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作业终态守卫用例族（R-96 批 B 收口修 F1，红队 M2/m2；R1-11 改为真实 H2 条件 UPDATE）：
 * {@link JobProgressService#finish} 仅当作业当前为 PENDING/RUNNING 才写入终态 ——
 * 看门狗判死 FAILED 与作业自然完成 COMPLETED 的竞态内先到者胜、后到者不覆盖
 * （含计数/进度等伴随字段整体不写入）。
 *
 * <p><b>不再用 Mockito 事务锁模拟串行化</b>：守卫已下沉为数据库条件 UPDATE，
 * 本用例直接跑在 H2 上，用真实行锁/提交顺序验证影响行数语义。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r1-11-terminal-guard;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.job.demo-batch-delay-ms=0"
})
class JobProgressServiceTerminalGuardTest {

    @Autowired
    private JobProgressService progressService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobHeartbeatRegistry heartbeatRegistry;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * I-3 死锁不变量：{@code finish} 必须走 REQUIRES_NEW 并先提交 job 行。
     *
     * <p>在调用方事务里执行 finish 后强制回滚，若 finish 误用 REQUIRED，job 终态会随之回滚；
     * 本用例断言终态仍在，从而把“job 行锁在写 task 前释放”钉死。
     */
    @Test
    void finishCommitsIndependentlyWhenCallerTransactionRollsBack() {
        Job job = runningJob(99L);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            assertThat(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10)).isTrue();
            status.setRollbackOnly();
        });

        Job stored = reload(job.getId());
        assertThat(stored.getStatus())
                .as("finish 必须 REQUIRES_NEW：调用方回滚不得回滚 job 终态")
                .isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(stored.getFinishedAt()).isNotNull();
    }

    @Test
    void completedThenFailedKeepsCompletedIgnoringSecondWriteEntirely() {
        Job job = runningJob(1L);

        assertThat(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10)).isTrue();

        assertThat(progressService.finish(job.getId(), Job.JobStatus.FAILED, 9, 0, 10, 10)).isFalse();

        Job stored = reload(job.getId());
        assertThat(stored.getStatus()).as("终态守卫：后到者不覆盖先到终态")
                .isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(stored.getErrorCount()).as("第二次写入被整体忽略（含计数）").isZero();
        assertThat(stored.getFinishedAt()).isNotNull();
    }

    @Test
    void failedThenCompletedKeepsFailedIgnoringSecondWriteEntirely() {
        Job job = runningJob(2L);

        assertThat(progressService.finish(job.getId(), Job.JobStatus.FAILED, 1, 0, 3, 10)).isTrue();
        assertThat(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10)).isFalse();

        Job stored = reload(job.getId());
        assertThat(stored.getStatus())
                .as("看门狗判死 FAILED 在先时，作业线程晚到的 COMPLETED 不得翻回")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(stored.getErrorCount()).isEqualTo(1);
        assertThat(stored.getProgress()).isEqualTo(3);
    }

    @Test
    void concurrentFinishFromTwoThreadsNeverFlipsTerminalState() throws Exception {
        Job job = runningJob(3L);

        int writers = 6;
        CyclicBarrier start = new CyclicBarrier(writers);
        AtomicInteger wins = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < writers; i++) {
            boolean completed = i % 2 == 0;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    if (progressService.finish(job.getId(),
                            completed ? Job.JobStatus.COMPLETED : Job.JobStatus.FAILED,
                            completed ? 0 : 1, 0, completed ? 10 : 7, 10)) {
                        wins.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        assertThat(wins.get()).as("真实 H2 上只有一个写者拿到影响行数 1").isEqualTo(1);
        Job stored = reload(job.getId());
        assertThat(stored.getStatus()).isIn(Job.JobStatus.COMPLETED, Job.JobStatus.FAILED);
        assertThat(stored.getErrorCount())
                .as("终态与计数必须来自同一次写入（先到者胜，后到者整段跳过）")
                .isEqualTo(stored.getStatus() == Job.JobStatus.FAILED ? 1 : 0);
        assertThat(stored.getFinishedAt()).isNotNull();
    }

    @Test
    void guardedSecondFinishAlsoClearsTheHeartbeatEntryReinsertedByEntryBeat() {
        Job job = runningJob(4L);

        assertThat(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10)).isTrue();
        assertThat(heartbeatRegistry.lastBeatAtMs(job.getId())).as("终态写入成功后条目已清（F3）").isNull();

        // 终态作业再被 finish 调用：入口的无条件 beat 先把条目塞回登记簿，随后被终态守卫拦下
        assertThat(progressService.finish(job.getId(), Job.JobStatus.FAILED, 9, 0, 10, 10)).isFalse();

        assertThat(reload(job.getId()).getStatus()).as("终态守卫：后到者不覆盖先到终态")
                .isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(heartbeatRegistry.lastBeatAtMs(job.getId()))
                .as("被守卫拦下的路径同样清理登记簿 —— 入口 beat 不得留下无人清理的条目（F1b）").isNull();
    }

    private Job runningJob(Long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.RUNNING);
        return jobRepository.saveAndFlush(job);
    }

    private Job reload(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }
}
