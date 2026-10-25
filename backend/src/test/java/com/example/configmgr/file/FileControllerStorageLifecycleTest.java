package com.example.configmgr.file;

import com.example.configmgr.task.controller.FileController;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.file.FileStorageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件生命周期（上传/导出保存）三项小修的用例：<b>真实临时目录 + 真实 H2</b>，不用 mock 断言。
 *
 * <ol>
 * <li>重复上传/重复保存覆盖 {@code task_files.storage_path} 时，旧物理文件在<b>提交成功后</b>删除
 * （失败/回滚时旧文件与旧引用都必须完好）；</li>
 * <li>写盘成功但事务提交期回滚（{@code afterCompletion(STATUS_ROLLED_BACK)}）时，本次新写入的文件要回收；</li>
 * <li>提交失败（回滚）不得误删当前仍被 {@code task_files} 引用的旧文件。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:file-storage-lifecycle;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0"
})
class FileControllerStorageLifecycleTest {

    private static final String DEF_CODE = "ROLE_DICT";

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage-path", STORAGE_ROOT::toString);
    }

    @Autowired
    private FileController fileController;
    @Autowired
    private TaskService taskService;
    @Autowired
    private FileStorageService fileStorage;
    @MockitoSpyBean
    private TaskFileRepository taskFileRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mockMvc;

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

    // ── ① 重复上传：旧物理文件在提交后回收 ──────────────────────────────

    @Test
    void repeatedUploadDeletesPreviousPhysicalFileAfterCommit() throws Exception {
        long taskId = taskWithDef("FLC-上传覆盖");
        upload(taskId, "v1");
        Path first = storagePath(taskId, "UPLOAD");
        assertThat(Path.of(storageKey(taskId, "UPLOAD")).isAbsolute())
                .as("task_files.storage_path 只存 UUID 相对键").isFalse();
        assertThat(Files.readString(first)).isEqualTo("v1");

        upload(taskId, "v2");
        Path second = storagePath(taskId, "UPLOAD");

        assertThat(second).as("覆盖写必须落到新路径（旧引用在新行提交前不被就地改写）").isNotEqualTo(first);
        assertThat(Files.readString(second)).isEqualTo("v2");
        assertThat(first).as("旧上传件在提交成功后删除").doesNotExist();
    }

    // ── ② 上传在提交期回滚：新文件回收、旧文件与旧引用保留 ─────────────

    @Test
    void rolledBackUploadDeletesTheNewFileAndKeepsTheCommittedOne() throws Exception {
        long taskId = taskWithDef("FLC-上传回滚");
        upload(taskId, "v1");
        Path committed = storagePath(taskId, "UPLOAD");
        Set<Path> before = storedFiles();

        rolledBack(() -> upload(taskId, "v2"));

        assertThat(storedFiles()).as("afterCompletion(STATUS_ROLLED_BACK) 回收本次新写入的文件")
                .isEqualTo(before);
        assertThat(Files.readString(committed)).as("旧文件不得被回滚清理误删").isEqualTo("v1");
        assertThat(storagePath(taskId, "UPLOAD")).isEqualTo(committed);
    }

    // ── ②b 任务删除：按相对键回收物理文件（TaskService 的删除出口）────────

    @Test
    void deletingTheTaskRemovesFilesStoredUnderRelativeKeys() throws Exception {
        long taskId = taskWithDef("FLC-删除任务回收");
        upload(taskId, "v1");
        Path stored = storagePath(taskId, "UPLOAD");
        assertThat(stored).exists();

        this.taskService.delete(taskId);

        assertThat(stored).as("任务删除时按库里的相对键解析并删除物理文件").doesNotExist();
    }

    // ── ③ 重复导出（PUT /files/{defCode}）：旧物理文件在提交后回收 ─────

    @Test
    void repeatedSaveDeletesPreviousPhysicalFileAfterCommit() throws Exception {
        long taskId = taskWithDef("FLC-导出覆盖");
        this.fileController.save(taskId, DEF_CODE, "v1".getBytes());
        Path first = storagePath(taskId, "EXPORT");
        assertThat(Path.of(storageKey(taskId, "EXPORT")).isAbsolute())
                .as("PUT 保存同样只落相对键").isFalse();

        this.fileController.save(taskId, DEF_CODE, "v2".getBytes());
        Path second = storagePath(taskId, "EXPORT");

        assertThat(second).isNotEqualTo(first);
        assertThat(Files.readString(second)).isEqualTo("v2");
        assertThat(first).as("旧导出件在提交成功后删除").doesNotExist();
    }

    // ── ③b 历史绝对路径行：仍按原路径下载（不要求先迁移）──────────────

    @Test
    void legacyAbsolutePathRowIsStillDownloadable() throws Exception {
        long taskId = taskWithDef("FLC-历史绝对路径");
        Path legacy = Files.createTempFile("flc-legacy-", ".xlsx");
        Files.writeString(legacy, "legacy-bytes");
        try {
            TaskFile tf = new TaskFile();
            tf.setTaskId(taskId);
            tf.setDefCode(DEF_CODE);
            tf.setFileType("EXPORT");
            tf.setStoragePath(legacy.toAbsolutePath().toString());
            tf.setOriginalPath(legacy.toAbsolutePath().toString());
            tf.setFileName(DEF_CODE + ".xlsx");
            this.taskFileRepository.save(tf);

            byte[] body = this.mockMvc
                .perform(get("/api/tasks/{taskId}/files/{defCode}", taskId, DEF_CODE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

            assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .as("历史绝对路径不经迁移也必须能读")
                .isEqualTo("legacy-bytes");
        }
        finally {
            Files.deleteIfExists(legacy);
        }
    }

    // ── ④ 保存在提交期回滚：新文件回收、旧文件与旧内容完好 ─────────────

    @Test
    void rolledBackSaveKeepsThePreviousExportContent() throws Exception {
        long taskId = taskWithDef("FLC-导出回滚");
        this.fileController.save(taskId, DEF_CODE, "v1".getBytes());
        Path committed = storagePath(taskId, "EXPORT");
        Set<Path> before = storedFiles();

        rolledBack(() -> this.fileController.save(taskId, DEF_CODE, "v2".getBytes()));

        assertThat(storedFiles()).isEqualTo(before);
        assertThat(Files.readString(committed))
                .as("非原子覆盖的坑：回滚后不能留下'新内容 + 旧元数据'的半成品")
                .isEqualTo("v1");
        assertThat(storagePath(taskId, "EXPORT")).isEqualTo(committed);
    }

    // ─────────── ⑤ 与并发导出撞版本（乐观锁）：明确 409，且新旧文件都不误删 ───────────

    /**
     * 换路径引入 {@code task_files} 的版本 UPDATE，与在途导出作业撞车时 JPA 抛
     * {@code ObjectOptimisticLockingFailureException}。口径：<b>409</b>（可重试），
     * 不是 500；同时回滚回收本次新件、旧件与旧引用原样保留。
     */
    @Test
    void optimisticLockConflictReturns409AndKeepsBothFiles() throws Exception {
        long taskId = taskWithDef("FLC-导出并发冲突");
        this.fileController.save(taskId, DEF_CODE, "v1".getBytes());
        Path committed = storagePath(taskId, "EXPORT");
        Set<Path> before = storedFiles();

        // 确定性地制造版本冲突：等价于导出作业在本次读-写之间提交了新版本
        doThrow(new ObjectOptimisticLockingFailureException(TaskFile.class, 1L))
                .when(this.taskFileRepository).saveAndFlush(any(TaskFile.class));

        this.mockMvc
            .perform(put("/api/tasks/{taskId}/files/{defCode}", taskId, DEF_CODE)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("v2".getBytes()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));

        assertThat(storedFiles()).as("冲突不得误删新旧任何一件").isEqualTo(before);
        assertThat(Files.readString(committed)).isEqualTo("v1");
        assertThat(storagePath(taskId, "EXPORT")).isEqualTo(committed);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private long taskWithDef(String title) {
        Task task = this.taskService.create(Task.TaskType.EXPORT, title);
        this.taskService.selectDefs(task.getId(), List.of(DEF_CODE));
        return task.getId();
    }

    private void upload(long taskId, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", DEF_CODE + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content.getBytes());
        this.fileController.upload(taskId, file);
    }

    private Path storagePath(long taskId, String fileType) {
        return this.fileStorage.resolve(storageKey(taskId, fileType));
    }

    /** {@code task_files.storage_path} 的原始值：新口径下必须是相对键。 */
    private String storageKey(long taskId, String fileType) {
        TaskFile tf = this.taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, DEF_CODE, fileType)
                .orElseThrow();
        return tf.getStoragePath();
    }

    /** 在独立事务里跑一次动作并标记回滚（触发 afterCompletion(STATUS_ROLLED_BACK)）。 */
    private void rolledBack(ThrowingRunnable action) {
        TransactionTemplate template = new TransactionTemplate(this.transactionManager);
        template.executeWithoutResult(status -> {
            try {
                action.run();
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            status.setRollbackOnly();
        });
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
            return Files.createTempDirectory("flc-file-storage-");
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

}
