package com.example.configmgr.task.controller;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导入侧上传契约：<b>文件名匹配按请求携带的 {@code defCodes}</b>，不依赖预先写入的 task_items。
 *
 * <h2>契约</h2>
 * 配置项选择是页面草案，只有"下一步"才提交 task_items。上传是用户明确动作，落库对象是
 * {@code task_files} + 文件存储；文件名 → 配置编码的匹配只需要"页面此刻选了哪些编码"，
 * 因此前端把草案 {@code defCodes} 随 multipart 请求带上：
 * <ul>
 * <li>给了 {@code defCodes} ⇒ 只在这些编码里匹配（最长前缀，既有语义不变），且<b>不写 task_items</b>；</li>
 * <li>{@code defCodes} 里有不存在的配置编码 ⇒ 400 {@code BAD_REQUEST}，零业务写入；</li>
 * <li>没给 {@code defCodes}（旧调用面）⇒ 仍按任务已有的 task_items 匹配（兼容零变化）。</li>
 * </ul>
 *
 * <p>ZIP 批量、多文件匹配、文件名不匹配时报错等既有行为都在本用例里钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:file-upload-defcodes;DB_CLOSE_DELAY=-1",
        "spring.data.redis.port=1"
})
class FileUploadDefCodesContractTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage-path", STORAGE_ROOT::toString);
    }

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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private DefinitionService definitionService;

    @Test
    void uploadMatchesRequestDefCodesWithoutAnyTaskItem() throws Exception {
        String code = "DEFCODES_SINGLE";
        define(code);
        long taskId = importTask("单文件-defCodes");

        MockMultipartFile file = new MockMultipartFile("file", code + "_2026.xlsx", XLSX, "x".getBytes());

        this.mockMvc.perform(multipart("/api/tasks/{taskId}/files/upload", taskId)
                        .file(file)
                        .param("defCodes", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defCode").value(code))
                .andExpect(jsonPath("$.data.count").value(1));

        assertThat(taskFileRepository.findByTaskId(taskId))
                .as("上传落库的是 task_files（文件本身），编码来自请求 defCodes")
                .hasSize(1)
                .allSatisfy(tf -> {
                    assertThat(tf.getDefCode()).isEqualTo(code);
                    assertThat(tf.getFileType()).isEqualTo("UPLOAD");
                });
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(taskId))
                .as("上传不得顺带提交选择集：task_items 仍为空")
                .isEmpty();
    }

    @Test
    void zipUploadMatchesEveryRequestedCodeAndKeepsLongestPrefixRule() throws Exception {
        define("DEFCODES_BASE");
        define("DEFCODES_BASE_LONG");
        define("DEFCODES_OTHER");
        long taskId = importTask("ZIP-defCodes");

        byte[] zip = zipOf(
                "DEFCODES_BASE_LONG-2026.xlsx",
                "DEFCODES_OTHER_2026.xlsx",
                "NOT_AN_EXCEL.txt");
        MockMultipartFile file = new MockMultipartFile("file", "batch.zip", "application/zip", zip);

        this.mockMvc.perform(multipart("/api/tasks/{taskId}/files/upload", taskId)
                        .file(file)
                        .param("defCodes", "DEFCODES_BASE", "DEFCODES_BASE_LONG", "DEFCODES_OTHER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.matchedFiles[0].defCode").value("DEFCODES_BASE_LONG"))
                .andExpect(jsonPath("$.data.matchedFiles[1].defCode").value("DEFCODES_OTHER"))
                .andExpect(jsonPath("$.data.unmatchedFiles[0]").value(containsString("NOT_AN_EXCEL.txt")));

        assertThat(taskFileRepository.findByTaskId(taskId))
                .as("两个匹配项各自落一份 task_files")
                .hasSize(2);
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(taskId)).isEmpty();
    }

    @Test
    void unknownDefCodeIsRejectedBeforeAnyBusinessWrite() throws Exception {
        long taskId = importTask("未知编码");

        MockMultipartFile file = new MockMultipartFile("file", "DEFCODES_UNKNOWN.xlsx", XLSX, "x".getBytes());

        this.mockMvc.perform(multipart("/api/tasks/{taskId}/files/upload", taskId)
                        .file(file)
                        .param("defCodes", "DEFCODES_UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("DEFCODES_UNKNOWN")));

        assertThat(taskFileRepository.findByTaskId(taskId)).as("非法编码不得落任何文件行").isEmpty();
        assertEmptyStorage();
    }

    @Test
    void filenameMatchingNoRequestedCodeIsRejected() throws Exception {
        define("DEFCODES_ONLY_ALLOWED");
        long taskId = importTask("文件名不匹配");

        MockMultipartFile file = new MockMultipartFile("file", "SOMETHING_ELSE.xlsx", XLSX, "x".getBytes());

        this.mockMvc.perform(multipart("/api/tasks/{taskId}/files/upload", taskId)
                        .file(file)
                        .param("defCodes", "DEFCODES_ONLY_ALLOWED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ILLEGAL_ARGUMENT"));

        assertThat(taskFileRepository.findByTaskId(taskId)).isEmpty();
        assertEmptyStorage();
    }

    @Test
    void legacyCallWithoutDefCodesStillMatchesTaskItems() throws Exception {
        String code = "DEFCODES_LEGACY";
        define(code);
        long taskId = importTask("旧调用面");
        TaskItem item = new TaskItem();
        item.setTaskId(taskId);
        item.setDefCode(code);
        taskItemRepository.save(item);

        MockMultipartFile file = new MockMultipartFile("file", code + ".xlsx", XLSX, "x".getBytes());

        this.mockMvc.perform(multipart("/api/tasks/{taskId}/files/upload", taskId).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defCode").value(code));
    }

    // ---------- helpers ----------

    private long importTask(String title) {
        return taskService.create(Task.TaskType.IMPORT, "DEFCODES-" + title).getId();
    }

    private void define(String code) {
        ConfigField key = new ConfigField();
        key.setCode("k");
        key.setLabel("主键");
        key.setFieldType(ConfigField.FieldType.STRING);
        key.setRequired(true);
        key.setKey(true);

        ConfigDefinition def = new ConfigDefinition();
        def.setCode(code);
        def.setName("上传匹配-" + code);
        def.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        def.setSortOrder(97);
        def.setFields(new ArrayList<>(List.of(key)));
        definitionService.save(def);
    }

    private static byte[] zipOf(String... entryNames) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (String name : entryNames) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(("content:" + name).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static void assertEmptyStorage() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(STORAGE_ROOT)) {
            assertThat(walk.filter(Files::isRegularFile).toList())
                    .as("请求非法时不得往存储里写任何文件")
                    .isEmpty();
        }
    }

    private static Path createStorageRoot() {
        try {
            Path root = Files.createTempDirectory("file-upload-defcodes-");
            root.toFile().deleteOnExit();
            return root;
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法创建测试存储目录", ex);
        }
    }
}
