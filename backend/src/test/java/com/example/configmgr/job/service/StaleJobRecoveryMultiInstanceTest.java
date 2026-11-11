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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

    /** 每主机 runner 精确索引（生产清理的候选集）：indexKey -> (jobId -> runner 值)。 */
    private final Map<String, Map<Object, Object>> hashStore = new ConcurrentHashMap<>();

    private ValueOperations<String, String> valueOps;

    private HashOperations<String, Object, Object> hashOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireFakeRedis() {
        redisStore.clear();
        hashStore.clear();
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        when(redis.hasKey(anyString()))
                .thenAnswer(invocation -> redisStore.containsKey(invocation.getArgument(0)));
        when(redis.delete(anyString()))
                .thenAnswer(invocation -> redisStore.remove(invocation.getArgument(0)) != null);
        when(hashOps.scan(anyString(), any(ScanOptions.class))).thenAnswer(invocation -> {
            Map<Object, Object> fields = hashStore.getOrDefault(invocation.getArgument(0), Map.of());
            return cursorOf(new ArrayList<>(fields.entrySet()));
        });
        when(hashOps.get(anyString(), any())).thenAnswer(invocation -> {
            Map<Object, Object> fields = hashStore.get(invocation.getArgument(0));
            return fields == null ? null : fields.get(invocation.getArgument(1));
        });
        // 只建模生产清理/登记实际发出的脚本（按脚本正文区分）：条件删除 runner-active、
        // 主机索引 HSET/HDEL。其它脚本保持未建模（返回 null，与既有调用方兼容）。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            String text = script.getScriptAsString();
            String key = ((List<String>) invocation.getArgument(1)).get(0);
            Object[] raw = invocation.getArguments();
            Object[] args = Arrays.copyOfRange(raw, 2, raw.length);
            if (text != null && text.contains("JOB_RUNNER_ACTIVE_DELETE_IF_VALUE")) {
                if (!Objects.equals(redisStore.get(key), String.valueOf(args[0]))) {
                    return 0L;
                }
                redisStore.remove(key);
                return 1L;
            }
            if (text != null && text.contains("HDEL")) {
                Map<Object, Object> fields = hashStore.get(key);
                return fields != null && fields.remove(args[0]) != null ? 1L : 0L;
            }
            if (text != null && text.contains("HSET")) {
                hashStore.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(args[0], args[1]);
                return 1L;
            }
            return null;
        });
    }

    private static <T> Cursor<T> cursorOf(List<T> items) {
        Iterator<T> iterator = items.iterator();
        return new Cursor<>() {
            @Override
            public void close() {
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public T next() {
                return iterator.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Cursor.CursorId getId() {
                return Cursor.CursorId.of(0L);
            }

            @Override
            public long getCursorId() {
                return 0L;
            }

            @Override
            public long getPosition() {
                return 0L;
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };
    }

    @Test
    void aliveRunnerFromAnotherInstanceIsNotMarkedFailed() {
        Seed seed = seedRunningJob("P16-其它实例活跃");
        JobHeartbeatRegistry instanceA = new JobHeartbeatRegistry(redis, "redis", new com.example.configmgr.config.AppProperties(), jobRepository,
                org.mockito.Mockito.mock(com.example.configmgr.job.service.JobCancellationRegistry.class));
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
        // 生产的判活读口是 GET job:runner-active:<jobId>（不是 HASKEY）；在确切键上注入读失败，
        // isRunnerAlive 才会包成 RunnerActiveStateUnavailableException，恢复侧按 fail-closed 跳过。
        when(valueOps.get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + seed.jobId))
                .thenThrow(new RedisConnectionFailureException("down"));

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
        // 生产清理的候选集是每主机 HASH 索引：先按真实登记路径写入 runner-active/beat/索引。
        JobHeartbeatRegistry deadInstance = new JobHeartbeatRegistry(redis, "redis",
                new com.example.configmgr.config.AppProperties(), jobRepository,
                org.mockito.Mockito.mock(com.example.configmgr.job.service.JobCancellationRegistry.class));
        assertThat(deadInstance.beginRunner(seed.jobId)).isTrue();
        // 同主机 + 已死 PID：键值停在上一个进程登记的那一刻（主机索引已由登记路径写好）。
        String localHost = InetAddress.getLocalHost().getHostName();
        String key = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + seed.jobId;
        redisStore.put(key, "runner-" + localHost + "-pid" + Long.MAX_VALUE + "-dead");

        cleanupRunner.run(null);
        assertThat(redisStore).as("本机死 PID 键必须先清理").doesNotContainKey(key);
        deadInstance.endRunner(seed.jobId); // 停掉旧实例 ticker，恢复面对的是"无 runner"

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
