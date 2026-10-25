package com.example.configmgr.file;

import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 共享文件下载的<b>字节一致性</b>契约（多实例验收用例 {@code shared-file-download} 的产品侧根因）。
 *
 * <h2>契约</h2>
 * {@code GET /api/tasks/{id}/files/download?codes=<单编码>} 必须回<b>该文件本身的字节</b>：
 * 前端 {@code api/tasks.ts#downloadSelected} 的注释与响应头（{@code Content-Type: xlsx} +
 * {@code Content-Disposition: <fileName>}）都声明"单个 xlsx 或 zip"，只有多编码才是 zip。
 *
 * <p>修复前的实现无论命中几个文件一律 {@code buildZip}，于是单编码下载回的是"再包一层的 zip"：
 * 字节与上传件不同（三实例验收里 A 上传 sha256=4172fd…、B 下载 sha256=2207a8…），
 * 而响应头仍声称是 xlsx —— 调用方按字节比对/按类型解析都会错。
 *
 * <p>本用例走真实 H2 + 真实存储目录 + 真实 multipart/下载端点（无 mock），断言字节相等；
 * 多编码分支同时钉住"仍是 zip"，避免修单文件时把打包语义改掉。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:file-download-identity;DB_CLOSE_DELAY=-1",
        // CI 无 Redis（backend job 不依赖 Redis 服务）；本机若恰有 Redis，这里的 KB 缓存/可用性探测会
        // 去读写它。钉到不可达端口 ⇒ 行为与 CI 一致，也不会污染开发机的 Redis 键。
        "spring.data.redis.port=1",
        "app.job.demo-batch-delay-ms=0"
})
class FileDownloadByteIdentityTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        registry.add("app.file.storage-path", STORAGE_ROOT::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskItemRepository taskItemRepository;

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
    void singleCodeDownloadReturnsTheStoredFileBytesNotAZipEnvelope() throws Exception {
        long taskId = taskWithDefs("CURRENCY");
        byte[] uploaded = payload("CURRENCY", 512);
        upload(taskId, "CURRENCY.xlsx", uploaded);

        var response = this.mockMvc
                .perform(get("/api/tasks/{taskId}/files/download", taskId).param("codes", "CURRENCY"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        byte[] body = response.getContentAsByteArray();

        assertThat(sha256(body))
                .as("单编码下载必须与上传件同字节（修复前是 zip 包裹 ⇒ 与上传件不同）")
                .isEqualTo(sha256(uploaded));
        assertThat(isZipContainer(body))
                .as("单文件下载不得是 zip 容器（本地文件头 PK 03 04）")
                .isFalse();
        assertThat(response.getContentType())
                .as("响应头声明单文件为 xlsx，正文必须是同一个 xlsx 字节流")
                .startsWith(XLSX);
        assertThat(response.getHeader("Content-Disposition")).contains("CURRENCY.xlsx");
    }

    @Test
    void multipleCodesStillReturnAZipWithOneEntryPerCode() throws Exception {
        long taskId = taskWithDefs("CURRENCY", "DOC_TYPE");
        upload(taskId, "CURRENCY.xlsx", payload("CURRENCY", 300));
        upload(taskId, "DOC_TYPE.xlsx", payload("DOC_TYPE", 300));

        var response = this.mockMvc
                .perform(get("/api/tasks/{taskId}/files/download", taskId).param("codes", "CURRENCY", "DOC_TYPE"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentType()).as("多编码仍打包").startsWith("application/zip");
        assertThat(isZipContainer(response.getContentAsByteArray()))
                .as("多编码下载是 zip 容器（本地文件头 PK 03 04）")
                .isTrue();
    }

    // ---------- helpers ----------

    private long taskWithDefs(String... defCodes) {
        Task task = new Task();
        task.setType(Task.TaskType.EXPORT);
        task.setTitle("下载字节一致性-" + String.join("-", defCodes));
        task.setCurrentStep("DEFINE");
        long taskId = this.taskRepository.save(task).getId();
        int sort = 0;
        for (String defCode : defCodes) {
            TaskItem item = new TaskItem();
            item.setTaskId(taskId);
            item.setDefCode(defCode);
            item.setSortOrder(sort++);
            this.taskItemRepository.save(item);
        }
        return taskId;
    }

    private void upload(long taskId, String filename, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, XLSX, content);
        this.mockMvc.perform(multipart("/api/tasks/{taskId}/files/upload", taskId).file(file))
                .andExpect(status().isOk());
    }

    /** 确定性载荷：内容取决于编码，便于发现"下载了别的文件"。 */
    private static byte[] payload(String defCode, int size) {
        byte[] out = new byte[size];
        byte[] seed = defCode.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (seed[i % seed.length] + (i * 31));
        }
        return out;
    }

    private static boolean isZipContainer(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static Path createStorageRoot() {
        try {
            Path root = Files.createTempDirectory("file-download-identity-");
            root.toFile().deleteOnExit();
            return root;
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法创建测试存储目录", ex);
        }
    }

}
