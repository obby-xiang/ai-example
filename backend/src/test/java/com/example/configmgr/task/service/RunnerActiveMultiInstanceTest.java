package com.example.configmgr.task.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.job.service.AsyncJobExecutor;
import com.example.configmgr.job.service.JobCancellationRegistry;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1-13 / 多实例 runner-active：A 实例写 Redis，B 实例删除必须读共享态。
 *
 * <p>Spring 里的 {@link JobHeartbeatRegistry} 是 B 实例；手工 new 的 {@code instanceA} 共享同一个
 * mock Redis，模拟另一实例。测试默认 {@code runner-active-store=memory}，本类显式覆盖为 redis。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r113-runner-multi;DB_CLOSE_DELAY=-1",
        "app.job.runner-active-store=redis",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r113-multi-test-files"
})
class RunnerActiveMultiInstanceTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobHeartbeatRegistry instanceB;
    @Autowired
    private JobService jobService;
    @Autowired
    private JobCancellationRegistry cancellationRegistry;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ValidationIssueRepository issueRepository;

    @MockitoBean
    private StringRedisTemplate redis;
    @MockitoBean
    private AsyncJobExecutor asyncJobExecutor;

    private final Map<String, String> redisStore = new ConcurrentHashMap<>();
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireFakeRedis() {
        redisStore.clear();
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        when(redis.hasKey(anyString()))
                .thenAnswer(invocation -> redisStore.containsKey(invocation.getArgument(0)));
        when(redis.delete(anyString()))
                .thenAnswer(invocation -> redisStore.remove(invocation.getArgument(0)) != null);
    }

    @Test
    void instanceARegistrationBlocksInstanceBDeleteUntilEndRunner() {
        long taskId = terminalTask();
        long jobId = jobRepository.findByTaskId(taskId).get(0).getId();
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);

        instanceA.beginRunner(jobId);
        assertThat(instanceB.isRunnerActive(jobId)).as("B 本地没有登记").isFalse();
        assertThatThrownBy(() -> taskService.delete(taskId))
                .as("A 的 runner 未退出，B 删除必须 409")
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> {
                    assertThat(((ConflictException) e).getCode()).isEqualTo("JOB_ALREADY_RUNNING");
                    assertThat(e.getMessage()).contains("收尾");
                });
        assertThat(taskRepository.existsById(taskId)).isTrue();

        instanceA.endRunner(jobId);
        taskService.delete(taskId);
        assertThat(taskRepository.existsById(taskId)).isFalse();
        assertThat(jobRepository.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void redisFailureFailsClosedInsteadOfAllowingDelete() {
        long taskId = terminalTask();
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> taskService.delete(taskId))
                .as("Redis 不可读时不能把未知状态当成可删")
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("Redis"));
        assertThat(taskRepository.existsById(taskId)).as("fail-closed：任务仍在").isTrue();
    }

    @Test
    void noRunnerKeyAfterRestartAllowsDelete() {
        long taskId = terminalTask();
        taskService.delete(taskId);
        assertThat(taskRepository.existsById(taskId)).isFalse();
    }

    @Test
    void heartbeatRenewsSharedKey() {
        long taskId = terminalTask();
        long jobId = jobRepository.findByTaskId(taskId).get(0).getId();
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);
        String key = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId;

        instanceA.beginRunner(jobId);
        redisStore.clear();
        instanceA.beat(jobId);
        assertThat(redisStore).containsKey(key);
    }

    @Test
    void beginRunnerRedisFailureReturnsFalseAndDoesNotRegisterLocalRunner() {
        long taskId = terminalTask();
        long jobId = jobRepository.findByTaskId(taskId).get(0).getId();
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);

        assertThat(instanceA.beginRunner(jobId)).isFalse();
        assertThat(instanceA.isRunnerActive(jobId)).isFalse();
        assertThatThrownBy(() -> taskService.delete(taskId))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("Redis"));
    }

    @Test
    void createAndStartBeginFailureAbortsCreationWithoutAsyncExecution() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R113-BEGIN-FAIL");
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> jobService.createAndStart(task.getId(), Job.JobType.EXPORT))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("runner 活跃登记失败"));

        assertThat(jobRepository.findByTaskId(task.getId())).as("事务回滚，不留 PENDING 作业").isEmpty();
        assertThat(taskService.findById(task.getId()).getStatus()).isEqualTo(Task.TaskStatus.ACTIVE);
        assertThat(redisStore).as("回滚后不留 runner 键").isEmpty();
        verify(asyncJobExecutor, never()).execute(any());
    }

    @Test
    void createRegistersRunnerInsideTransactionAndRollbackCleansBothSides() {
        long taskId = taskService.create(Task.TaskType.EXPORT, "R113-ROLLBACK").getId();
        int before = cancellationRegistry.registeredJobs();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jobService.createAndStart(taskId, Job.JobType.EXPORT);
            status.setRollbackOnly();
        });

        assertThat(jobRepository.findByTaskId(taskId)).as("创建事务回滚后无作业").isEmpty();
        assertThat(redisStore).as("afterCompletion 回滚清理 runner 键").isEmpty();
        assertThat(cancellationRegistry.registeredJobs()).isEqualTo(before);
        verify(asyncJobExecutor, never()).execute(any());
    }

    @Test
    void createBlockedWhileInFlightRunnerStillAlive() {
        long taskId = taskService.create(Task.TaskType.EXPORT, "R113-RUNNER-ALIVE").getId();
        // 异类型 + 在途（PRECHECK）：先过同类互斥，验证的是 runner 存活守卫本身
        Job running = new Job();
        running.setTaskId(taskId);
        running.setJobType(Job.JobType.PRECHECK);
        running.setStatus(Job.JobStatus.RUNNING);
        Job seeded = jobRepository.saveAndFlush(running);
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);
        instanceA.beginRunner(seeded.getId());

        assertThatThrownBy(() -> jobService.createAndStart(taskId, Job.JobType.EXPORT))
                .as("在途旧作业的 runner 未退出仍阻新建")
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("收尾"));

        instanceA.endRunner(seeded.getId());
        Job created = jobService.createAndStart(taskId, Job.JobType.EXPORT);
        assertThat(created.getStatus()).isEqualTo(Job.JobStatus.PENDING);
    }

    /** S1：看门狗先把作业收为 FAILED 时，旧 runner 未退出仍不得放行第二个写者。 */
    @Test
    void failedJobWithLiveRunnerBlocksNewJob() {
        long taskId = taskService.create(Task.TaskType.EXPORT, "R113-FAILED-RUNNER-ALIVE").getId();
        Job failed = new Job();
        failed.setTaskId(taskId);
        failed.setJobType(Job.JobType.PRECHECK);
        failed.setStatus(Job.JobStatus.FAILED);
        Job seeded = jobRepository.saveAndFlush(failed);
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);
        instanceA.beginRunner(seeded.getId());

        assertThatThrownBy(() -> jobService.createAndStart(taskId, Job.JobType.EXPORT))
                .as("FAILED 只表示数据库终态，runner 仍存活时必须沿用收尾冲突")
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> {
                    assertThat(((ConflictException) e).getCode()).isEqualTo("JOB_ALREADY_RUNNING");
                    assertThat(e.getMessage()).contains("收尾");
                });
        assertThat(jobRepository.findByTaskId(taskId)).hasSize(1);
        verify(asyncJobExecutor, never()).execute(any());
    }

    @Test
    void createRedisReadFailureFailsClosed() {
        long taskId = taskService.create(Task.TaskType.EXPORT, "R113-REDIS-DOWN").getId();
        // 任一旧作业都会触发共享态读取；Redis 读失败必须 fail-closed
        Job running = new Job();
        running.setTaskId(taskId);
        running.setJobType(Job.JobType.PRECHECK);
        running.setStatus(Job.JobStatus.RUNNING);
        jobRepository.saveAndFlush(running);
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> jobService.createAndStart(taskId, Job.JobType.EXPORT))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("无法确认"));
        assertThat(jobRepository.findByTaskId(taskId)).hasSize(1);
    }

    @Test
    void asyncExecuteRejectionMarksFailedAndCleansRegistrations() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R113-EXEC-REJECT");
        int before = cancellationRegistry.registeredJobs();
        doThrow(new TaskRejectedException("pool full")).when(asyncJobExecutor).execute(any());

        Job job = jobService.createAndStart(task.getId(), Job.JobType.EXPORT);

        Job stored = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("拒绝启动不得留 PENDING").isEqualTo(Job.JobStatus.FAILED);
        assertThat(stored.getFinishedAt()).isNotNull();
        assertThat(issueRepository.findByJobIdAndDefCode(job.getId(), "-")).hasSize(1);
        assertThat(taskService.findById(task.getId()).getStatus()).isEqualTo(Task.TaskStatus.FAILED);
        assertThat(redisStore).doesNotContainKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + job.getId());
        assertThat(cancellationRegistry.registeredJobs()).isEqualTo(before);
        verify(asyncJobExecutor).execute(any());
    }

    @Test
    void startupCleanupRemovesDeadLocalInstanceKey() throws Exception {
        long taskId = terminalTask();
        long jobId = jobRepository.findByTaskId(taskId).get(0).getId();
        String localHost = InetAddress.getLocalHost().getHostName();
        redisStore.put(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId,
                "runner-" + localHost + "-pid" + Long.MAX_VALUE + "-dead");

        instanceB.cleanupDeadLocalRunner(jobId);
        assertThat(redisStore).doesNotContainKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId);
        taskService.delete(taskId);
    }

    @Test
    void watchdogRevokeClearsSharedKeysAndReleasesDeleteGateForAnotherInstance() {
        long taskId = terminalTask();
        long jobId = jobRepository.findByTaskId(taskId).get(0).getId();
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);
        instanceA.beginRunner(jobId);
        instanceA.beat(jobId);
        assertThat(redisStore).containsKeys(
                JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId,
                JobHeartbeatRegistry.BEAT_PREFIX + jobId);

        assertThatThrownBy(() -> taskService.delete(taskId))
                .as("A 的 runner 未退出：B 删除必须 409")
                .isInstanceOf(ConflictException.class);
        assertThat(taskRepository.existsById(taskId)).isTrue();

        // 看门狗判死成功后在 B 侧撤销 runner 登记（A 已失联，不会再有 endRunner）
        instanceB.revokeRunner(jobId);

        assertThat(redisStore).as("判死清 runner-active + 共享心跳，删除闸门立即释放（不等 30m/65m TTL）")
                .doesNotContainKeys(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId,
                        JobHeartbeatRegistry.BEAT_PREFIX + jobId);
        taskService.delete(taskId);
        assertThat(taskRepository.existsById(taskId)).isFalse();
        assertThat(jobRepository.findByTaskId(taskId)).isEmpty();
    }

    private long terminalTask() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R113-MULTI");
        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.CANCELLED);
        jobRepository.saveAndFlush(job);
        return task.getId();
    }
}
