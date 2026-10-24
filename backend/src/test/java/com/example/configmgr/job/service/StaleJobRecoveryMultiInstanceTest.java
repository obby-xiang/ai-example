package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-6 回归：共享 DB/Redis 的双实例启动恢复。
 *
 * <p>A 实例通过 {@link JobHeartbeatRegistry#beginRunner(Long)} 写入 runner-active 键后，
 * B 实例的 {@link StaleJobRecoveryRunner} 不得把 A 仍在跑的 PENDING/RUNNING 作业判 FAILED；
 * Redis 读失败时同样跳过（fail-closed）；本机死 PID 键必须先被
 * {@link RunnerActiveKeyCleanupRunner} 清理，恢复才能收掉真正的僵尸作业。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:p16-stale-multi;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.job.runner-active-store=redis",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/p16-stale-multi-files"
})
@Transactional
class StaleJobRecoveryMultiInstanceTest {

    @Autowired
    private StaleJobRecoveryRunner staleJobRecoveryRunner;
    @Autowired
    private RunnerActiveKeyCleanupRunner cleanupRunner;
    @Autowired
    private JobHeartbeatRegistry instanceB;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;

    @MockitoBean
    private StringRedisTemplate redis;

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
    void aliveRunnerFromAnotherInstanceIsNotMarkedFailed() {
        Seed seed = seedRunningJob("P16-其它实例活跃");
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(Clock.systemUTC(), redis, true);
        assertThat(instanceA.beginRunner(seed.jobId)).isTrue();

        staleJobRecoveryRunner.run(null);

        assertThat(reloadJob(seed.jobId).getStatus())
                .as("A 实例 runner 仍活跃，B 恢复不得判 FAILED")
                .isEqualTo(Job.JobStatus.RUNNING);
        assertThat(jobItemRepository.findById(seed.itemId).orElseThrow().getStatus())
                .isEqualTo("RUNNING");
        assertThat(taskRepository.findById(seed.taskId).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.ACTIVE);
    }

    @Test
    void runnerStateReadFailureSkipsRecoveryFailClosed() {
        Seed seed = seedRunningJob("P16-Redis读失败");
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        staleJobRecoveryRunner.run(null);

        assertThat(reloadJob(seed.jobId).getStatus())
                .as("无法确认 runner 存活时不得错误写 FAILED（fail-closed）")
                .isEqualTo(Job.JobStatus.RUNNING);
        assertThat(taskRepository.findById(seed.taskId).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.ACTIVE);
    }

    @Test
    void deadLocalRunnerKeyIsCleanedBeforeRecoveryThenJobIsFailed() throws Exception {
        Seed seed = seedRunningJob("P16-本机死PID");
        String localHost = InetAddress.getLocalHost().getHostName();
        String key = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + seed.jobId;
        redisStore.put(key, "runner-" + localHost + "-pid" + Long.MAX_VALUE + "-dead");

        cleanupRunner.run(null);
        assertThat(redisStore).as("本机死 PID 键必须先清理").doesNotContainKey(key);

        staleJobRecoveryRunner.run(null);

        assertThat(reloadJob(seed.jobId).getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(jobItemRepository.findById(seed.itemId).orElseThrow().getStatus())
                .isEqualTo("FAILED");
        assertThat(taskRepository.findById(seed.taskId).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.FAILED);
    }

    @Test
    void cleanupRunnerOrderIsBeforeStaleRecovery() {
        assertThat(OrderUtils.getOrder(RunnerActiveKeyCleanupRunner.class))
                .as("本机死 PID 清理必须先于僵尸恢复执行")
                .isLessThan(OrderUtils.getOrder(StaleJobRecoveryRunner.class));
    }

    private Seed seedRunningJob(String title) {
        Task task = taskService.create(Task.TaskType.EXPORT, title);
        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.RUNNING);
        job = jobRepository.saveAndFlush(job);

        JobItem item = new JobItem();
        item.setJobId(job.getId());
        item.setDefCode("SYS_PARAM");
        item.setStatus("RUNNING");
        item = jobItemRepository.saveAndFlush(item);
        return new Seed(task.getId(), job.getId(), item.getId());
    }

    private Job reloadJob(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }

    private record Seed(Long taskId, Long jobId, Long itemId) {
    }
}
