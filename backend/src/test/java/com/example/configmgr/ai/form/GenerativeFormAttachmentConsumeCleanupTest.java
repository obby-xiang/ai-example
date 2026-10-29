package com.example.configmgr.ai.form;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表单附件的一次性消费与物理文件回收（G-E 生命周期收口）：<b>真实临时目录 + 真实文件</b>。
 *
 * <ul>
 * <li>consume 成功（回灌被接受）⇒ 落盘文件删除；一次性语义不变（同一 fileId 二次提交仍
 * {@code ATTACHMENT_CONSUMED}）；</li>
 * <li>回灌被拒（归属不符等）⇒ 既不消费登记、也<b>不</b>删盘，用户修正后可继续用。</li>
 * </ul>
 */
class GenerativeFormAttachmentConsumeCleanupTest {

    private static final String ARGS = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
            + "{\"key\":\"evidence\",\"label\":\"附件\",\"type\":\"attachment\",\"required\":true}]}}";

    private static final Path STORAGE_ROOT = createStorageRoot();

    private final AppProperties properties = new AppProperties();

    private final InMemoryStore store = new InMemoryStore();

    private final GenerativeFormGuard guard = new GenerativeFormGuard(new ObjectMapper(), this.store,
            storageService());

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
    void consumedAttachmentFileIsDeletedAndTheRegistrationStaysOneShot() throws IOException {
        Path file = Files.writeString(STORAGE_ROOT.resolve("evidence.pdf"), "pdf-bytes");
        FormAttachmentStore.Attachment attachment = this.store.register("run-1", "call-1", "sess-1",
                "evidence.pdf", 9, "application/pdf", file.toString());

        FrontendToolGuard.Verdict accepted = inspect("run-1", "call-1", attachment.fileId());
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.resultSuffix()).as("元数据在校验阶段取出，回灌结论不受删盘影响").contains("evidence.pdf");
        assertThat(Files.exists(file)).as("校验是只读的：决策落库前不删盘").isTrue();

        // 决策落库之后的消费：删登记、删物理文件
        settle("run-1", "call-1", attachment.fileId());
        assertThat(this.store.find(attachment.fileId())).isNull();
        assertThat(Files.exists(file)).as("consume 成功后删除物理文件").isFalse();

        // 一次性语义不变：同一 fileId 二次提交一律 ATTACHMENT_CONSUMED（且不因文件已删而报错）
        FrontendToolGuard.Verdict again = inspect("run-1", "call-1", attachment.fileId());
        assertThat(again.accepted()).isFalse();
        assertThat(again.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_CONSUMED);
    }

    @Test
    void rejectedSubmissionKeepsTheFileAndTheRegistration() throws IOException {
        Path file = Files.writeString(STORAGE_ROOT.resolve("evidence-keep.pdf"), "pdf-bytes");
        FormAttachmentStore.Attachment attachment = this.store.register("run-1", "call-1", "sess-1",
                "evidence-keep.pdf", 9, "application/pdf", file.toString());

        FrontendToolGuard.Verdict otherRun = inspect("run-2", "call-1", attachment.fileId());
        assertThat(otherRun.accepted()).isFalse();
        assertThat(otherRun.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_FORBIDDEN);
        assertThat(Files.exists(file)).as("被拒的回灌不删盘（文件仍属当前登记）").isTrue();
        assertThat(this.store.find(attachment.fileId())).isNotNull();

        // 修正归属后可正常提交，决策落库之后才删盘
        assertThat(inspect("run-1", "call-1", attachment.fileId()).accepted()).isTrue();
        settle("run-1", "call-1", attachment.fileId());
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void keyStoredAttachmentIsResolvedAndDeletedOnConsume() throws IOException {
        FileStorageService storage = storageService();
        StoredFile stored = storage.store(
                new java.io.ByteArrayInputStream("pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "evidence.pdf");
        assertThat(java.nio.file.Path.of(stored.key()).isAbsolute())
                .as("附件登记簿只存相对存储键").isFalse();

        FormAttachmentStore.Attachment attachment = this.store.register("run-1", "call-1", "sess-1",
                "evidence.pdf", 9, "application/pdf", stored.key());

        assertThat(inspect("run-1", "call-1", attachment.fileId()).accepted()).isTrue();
        settle("run-1", "call-1", attachment.fileId());
        assertThat(stored.path()).as("消费后按存储键解析并删除落盘文件").doesNotExist();
    }

    @Test
    void missingPhysicalFileDoesNotBreakConsumption() {
        // 盘上文件已被外部清掉（本批不做扫描器，只保证消费路径不因删盘异常而失败）
        FormAttachmentStore.Attachment attachment = this.store.register("run-1", "call-1", "sess-1",
                "gone.pdf", 3, "application/pdf", STORAGE_ROOT.resolve("gone-never-written.pdf").toString());

        assertThat(inspect("run-1", "call-1", attachment.fileId()).accepted()).isTrue();
        settle("run-1", "call-1", attachment.fileId());
        assertThat(this.store.find(attachment.fileId())).isNull();
    }

    private FrontendToolGuard.Verdict inspect(String runId, String toolCallId, String fileId) {
        String values = "{\"evidence\":\"" + fileId + "\"}";
        return this.guard.inspectResult(runId, toolCallId, ARGS, values);
    }

    /** 决策落库之后的消费口（消费不再发生在校验里）。 */
    private void settle(String runId, String toolCallId, String fileId) {
        this.guard.settleAcceptedResult(runId, toolCallId, ARGS, "{\"evidence\":\"" + fileId + "\"}");
    }

    private FileStorageService storageService() {
        this.properties.getFile().setStoragePath(STORAGE_ROOT.toString());
        return new FileStorageService(this.properties);
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("flc-attachment-storage-");
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 进程内登记簿（生产实现见 {@code RedisFormAttachmentStore}，本用例不触 Redis）。 */
    private static final class InMemoryStore implements FormAttachmentStore {

        private final Map<String, Attachment> items = new LinkedHashMap<>();

        private final java.util.Set<String> consumed = new java.util.HashSet<>();

        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public synchronized Attachment register(String runId, String toolCallId, String sessionId, String fileName,
                long size, String contentType, String storagePath) {
            Attachment attachment = new Attachment("att_" + String.format("flc%05d", this.seq.incrementAndGet()),
                    runId, toolCallId, sessionId, fileName, size, contentType, storagePath);
            this.items.put(attachment.fileId(), attachment);
            return attachment;
        }

        @Override
        public synchronized Attachment find(String fileId) {
            return this.items.get(fileId);
        }

        @Override
        public synchronized boolean consume(String fileId) {
            if (this.items.remove(fileId) != null) {
                this.consumed.add(fileId);
                return true;
            }
            return false;
        }

        @Override
        public synchronized boolean wasConsumed(String fileId) {
            return this.consumed.contains(fileId);
        }

    }

}
