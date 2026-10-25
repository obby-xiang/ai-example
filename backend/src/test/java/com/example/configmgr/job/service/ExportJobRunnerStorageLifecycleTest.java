package com.example.configmgr.job.service;

import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 导出作业的文件生命周期：<b>真实临时目录 + 真实 H2 + 真实 Excel 写盘</b>。
 *
 * <p>锁两件事：重复导出覆盖 {@code task_files.storage_path} 后旧件在提交成功时回收；
 * 提交期回滚（{@code STATUS_ROLLED_BACK}）时本次新写入件被回收、旧件与旧引用保留。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:export-storage-lifecycle;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0"
})
class ExportJobRunnerStorageLifecycleTest {

    private static final String DEF_CODE = "ROLE_DICT";

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage-path", STORAGE_ROOT::toString);
    }

    @Autowired
    private ExportJobRunner exportJobRunner;
    @Autowired
    private TaskService taskService;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobProgressService progressService;
    @Autowired
    private JobItemRepository jobItemRepository;
    @MockitoSpyBean
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterAll
    static void removeStorageRoot() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(STORAGE_ROOT)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void repeatedExportDeletesThePreviousFileAfterCommit() {
        long taskId = task("FLC-导出作业覆盖");
        runExport(taskId);
        Path first = storagePath(taskId);
        assertThat(first).as("首轮导出件已落盘").exists();
        assertThat(Path.of(this.taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, DEF_CODE, "EXPORT").orElseThrow().getStoragePath())
            .isAbsolute()).as("task_files.storage_path 只存相对键").isFalse();

        runExport(taskId);
        Path second = storagePath(taskId);

        assertThat(second).isNotEqualTo(first);
        assertThat(second).exists();
        assertThat(first).as("重复导出后旧件在提交成功时删除").doesNotExist();
    }

    @Test
    void rolledBackExportDeletesTheNewFileAndKeepsThePreviousOne() throws IOException {
        long taskId = task("FLC-导出作业回滚");
        runExport(taskId);
        Path committed = storagePath(taskId);
        Set<Path> before = storedFiles();
        // 作业行必须先独立提交：run() 内的进度写走 REQUIRES_NEW，看不到外层未提交的 job 行
        Job job = createJob(taskId);

        TransactionTemplate template = new TransactionTemplate(this.transactionManager);
        template.executeWithoutResult(status -> {
            this.exportJobRunner.run(job);
            try {
                assertThat(storedFiles()).as("回滚前新导出件必须已落盘（否则本例是空断言）")
                        .hasSize(before.size() + 1);
            }
            catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            status.setRollbackOnly();
        });

        assertThat(storedFiles()).as("回滚后本次新写入的导出件被回收").isEqualTo(before);
        assertThat(committed).exists();
        assertThat(storagePath(taskId)).as("旧引用不被回滚清理波及").isEqualTo(committed);
    }

    // ───────── ③ 回滚后作业终态收敛：不得停在 RUNNING，也不得覆盖已有终态 ─────────

    /**
     * 回滚时数据面（task_files）整批撤回，但进度/终态写在 {@code REQUIRES_NEW} 里已提交 ——
     * 修复前作业停在 RUNNING（进度满格），只能等看门狗 30min 判死。收敛口径：回滚即 FAILED。
     */
    @Test
    void rolledBackExportConvergesJobStatusToFailedInsteadOfStayingRunning() {
        long taskId = task("FLC-导出回滚收敛");
        Job job = createJob(taskId);

        TransactionTemplate template = new TransactionTemplate(this.transactionManager);
        template.executeWithoutResult(status -> {
            this.exportJobRunner.run(job);
            status.setRollbackOnly();
        });

        Job converged = this.jobRepository.findById(job.getId()).orElseThrow();
        assertThat(converged.getStatus()).as("回滚后不得停在 RUNNING（看门狗最长要等 30min）")
                .isEqualTo(Job.JobStatus.FAILED);
    }

    /** 收敛是"补终态"，不是"翻终态"：已有终态（取消先胜出）必须原样保留。 */
    @Test
    void rollbackConvergenceDoesNotOverwriteAnExistingTerminalStatus() {
        long taskId = task("FLC-导出回滚不翻写终态");
        Job job = createJob(taskId);

        TransactionTemplate template = new TransactionTemplate(this.transactionManager);
        template.executeWithoutResult(status -> {
            this.exportJobRunner.run(job);
            // 取消先胜出：终态经 REQUIRES_NEW 独立提交，之后的数据面才回滚
            assertThat(this.progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 0)).isTrue();
            status.setRollbackOnly();
        });

        assertThat(this.jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .as("收敛不得把已落定的 CANCELLED 改写成 FAILED")
                .isEqualTo(Job.JobStatus.CANCELLED);
    }

    /**
     * finishItem <b>之前</b>就失败且事务已被标记回滚：若直接把异常抛给外层，条目会停在 RUNNING
     * （它由 {@code startItem} 在 REQUIRES_NEW 里提交，数据面回滚不会带走它），
     * 形成 jobs=FAILED + job_items=RUNNING 的坏形态。口径：先做一次 REQUIRES_NEW 的条目补偿再抛。
     */
    @Test
    void rollbackOnlyFailureBeforeFinishItemCompensatesTheItemInsteadOfLeavingItRunning() {
        long taskId = task("FLC-回滚条目补偿");
        Job job = createJob(taskId);

        // 等价于"内层参与事务（仓储/服务）抛错并被标记 rollback-only"：这里在读取数据行这一步
        // 确定性复现（该调用位于 startItem 之后、finishItem 之前）。
        doAnswer(invocation -> {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new IllegalStateException("模拟 finishItem 之前的失败");
        }).when(this.dataRowRepository).findByDefCodeOrderByRowKey(anyString());

        TransactionTemplate template = new TransactionTemplate(this.transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> this.exportJobRunner.run(job)))
            .as("事务被标记 rollback-only 时，提交期静默回滚并抛 UnexpectedRollbackException")
            .isInstanceOf(UnexpectedRollbackException.class);

        List<JobItem> items = this.jobItemRepository.findByJobId(job.getId());
        assertThat(items).as("条目已由 startItem 落库").isNotEmpty();
        assertThat(items).extracting(JobItem::getStatus)
                .as("finishItem 前失败 + rollback-only：条目不能残留 RUNNING")
                .doesNotContain("RUNNING");
        assertThat(this.jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(Job.JobStatus.FAILED);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private long task(String title) {
        Task task = this.taskService.create(Task.TaskType.EXPORT, title);
        this.taskService.selectDefs(task.getId(), List.of(DEF_CODE));
        return task.getId();
    }

    private Job runExport(long taskId) {
        Job job = createJob(taskId);
        this.exportJobRunner.run(job);
        return job;
    }

    private Job createJob(long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        return this.jobRepository.save(job);
    }

    private Path storagePath(long taskId) {
        TaskFile tf = this.taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, DEF_CODE, "EXPORT")
                .orElseThrow();
        return this.fileStorage.resolve(tf.getStoragePath());
    }

    private static Set<Path> storedFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(STORAGE_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .collect(Collectors.toSet());
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("flc-export-storage-");
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
