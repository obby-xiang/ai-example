package com.example.configmgr.ai.form;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上传端的<b>表单预算</b>与<b>归属权威值</b>（2026-10-29 修复批）。
 *
 * <h2>四件旧缺陷</h2>
 * <ol>
 * <li><b>sessionId 信客户端</b>：上传请求自带 {@code sessionId}，它进的是"会话 → 附件"索引 ——
 * 于是任何知道 runId/toolCallId 的请求都能把文件挂到<b>别的会话</b>名下（那个会话销毁时被连带删）。</li>
 * <li><b>没有件数上限</b>：一张表单的 {@code attachment} 字段数是固定的（每个字段只装一个 fileId），
 * 但仍可以往同一个 (runId, toolCallId) 无限上传 —— 多出来的件永远不会被提交，只会落到 TTL 才消失。</li>
 * <li><b>没有总量上限</b>：每件 5MB 的上限 × 无限件 = 无上限，磁盘配额只由攻击者决定。</li>
 * <li><b>不校验目标字段</b>：模型给的 schema 里没有这个 {@code attachment} 字段（或根本不是
 * attachment 类型）时，上传照样成功，前端拿到的 fileId 永远填不进任何字段。</li>
 * </ol>
 *
 * <p>另外锁住流关闭（{@code MultipartFile#getInputStream} 必须 try-with-resources）与
 * "会话权威值取轮次快照"的口径。
 */
class GenerativeFormAttachmentUploadLimitsTest {

	private static final String RUN_ID = "run-upload";

	private static final String CALL_ID = "call-upload";

	private static final String TWO_ATTACHMENT_FIELDS = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"evidence\",\"label\":\"证据\",\"type\":\"attachment\"},"
			+ "{\"key\":\"contract\",\"label\":\"合同\",\"type\":\"attachment\"}]}}";

	private static final String NO_ATTACHMENT_FIELDS = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"note\",\"label\":\"备注\",\"type\":\"text\"}]}}";

	private static final String ONE_ATTACHMENT_FIELD = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"evidence\",\"label\":\"证据\",\"type\":\"attachment\"}]}}";

	private static final String MIXED_FIELDS = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"note\",\"label\":\"备注\",\"type\":\"text\"},"
			+ "{\"key\":\"evidence\",\"label\":\"证据\",\"type\":\"attachment\"}]}}";

	private final FileStorageService storage = mock(FileStorageService.class);

	private final RunStore runStore = mock(RunStore.class);

	private final InMemoryStore attachments = new InMemoryStore();

	private final GenerativeFormAttachmentController controller = new GenerativeFormAttachmentController(this.storage,
			this.attachments, this.runStore, new FormOptionSourceRegistry(List.of()));

	@Test
	void uploadRegistersUnderTheAuthoritativeSessionFromTheRunSnapshot() throws Exception {
		waitingForm(TWO_ATTACHMENT_FIELDS);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-evidence.pdf", Path.of("/data/evidence.pdf")));

		// 客户端自报的是别人的会话：不得进入登记（会话销毁会按登记删文件）
		var response = this.controller.upload(pdf("evidence.pdf"), RUN_ID, CALL_ID, "sess-of-someone-else");

		String fileId = (String) response.getData().get("fileId");
		assertThat(this.attachments.find(fileId).sessionId()).isEqualTo("sess-authoritative");
		assertThat(this.attachments.findBySessionId("sess-of-someone-else")).isEmpty();
	}

	@Test
	void uploadRejectsAFormWithoutAttachmentFields() {
		waitingForm(NO_ATTACHMENT_FIELDS);

		assertThatThrownBy(() -> this.controller.upload(pdf("evidence.pdf"), RUN_ID, CALL_ID, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("没有附件字段");
	}

	@Test
	void uploadRejectsWhenEveryAttachmentSlotIsTaken() {
		waitingForm(TWO_ATTACHMENT_FIELDS);
		this.attachments.register(RUN_ID, CALL_ID, "sess-authoritative", "a.pdf", 9, "application/pdf", "k-a");
		this.attachments.register(RUN_ID, CALL_ID, "sess-authoritative", "b.pdf", 9, "application/pdf", "k-b");

		// 两个 attachment 字段 = 两个附件位；第三件永远提交不进任何字段，只会在 TTL 前占着盘。
		// 拒绝文案必须是"真能照做"的：没有字段归属映射时不存在"按 fieldKey 精确替换"，
		// 不能把 fieldKey 说成替换开关（那是死路指引）。
		assertThatThrownBy(() -> this.controller.upload(pdf("c.pdf"), RUN_ID, CALL_ID, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("附件位已满")
			.hasMessageContaining("2")
			.hasMessageContaining("提交当前表单")
			.hasMessageContaining("取消表单")
			.hasMessageNotContaining("fieldKey");
	}

	@Test
	void uploadRejectsWhenTheFormByteBudgetWouldBeExceeded() {
		waitingForm(sixAttachmentFields());
		// 四件各 4MB 在册（16MB）；再来 5MB 就是 21MB > 单表 20MB 总预算 —— 件数还没满也必须拒
		long fourMb = 4L * 1024 * 1024;
		for (int i = 1; i <= 4; i++) {
			this.attachments.register(RUN_ID, CALL_ID, "sess-authoritative", "f" + i + ".pdf", fourMb,
					"application/pdf", "k-" + i);
		}

		MockMultipartFile fifth = new MockMultipartFile("file", "fifth.pdf", "application/pdf",
				new byte[(int) GenerativeFormAttachmentController.MAX_ATTACHMENT_BYTES]);
		assertThatThrownBy(() -> this.controller.upload(fifth, RUN_ID, CALL_ID, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("附件总量超限")
			.hasMessageContaining(String.valueOf(GenerativeFormAttachmentController.MAX_TOTAL_ATTACHMENT_BYTES));
	}

	@Test
	void uploadValidatesTheTargetAttachmentFieldWhenOneIsNamed() throws Exception {
		waitingForm(MIXED_FIELDS);

		// 目标是 text 字段：即便它有 key，也不是附件位
		assertThatThrownBy(() -> this.controller.upload(pdf("evidence.pdf"), RUN_ID, CALL_ID, null, "note"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不是本表单的附件字段");
		assertThatThrownBy(() -> this.controller.upload(pdf("evidence.pdf"), RUN_ID, CALL_ID, null, "nope"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不是本表单的附件字段");

		// 正例：指向真实的 attachment 字段照常放行
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-keyed.pdf", Path.of("/data/keyed.pdf")));
		var response = this.controller.upload(pdf("evidence.pdf"), RUN_ID, CALL_ID, null, "evidence");
		assertThat(response.getData().get("fileId")).isNotNull();
	}

	@Test
	void uploadClosesTheMultipartInputStream() throws Exception {
		waitingForm(TWO_ATTACHMENT_FIELDS);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-closed.pdf", Path.of("/data/closed.pdf")));
		byte[] bytes = "pdf-bytes".getBytes();
		AtomicBoolean closed = new AtomicBoolean();
		MockMultipartFile file = new MockMultipartFile("file", "evidence.pdf", "application/pdf", bytes) {
			@Override
			public InputStream getInputStream() {
				return new ByteArrayInputStream(bytes) {
					@Override
					public void close() throws IOException {
						closed.set(true);
						super.close();
					}
				};
			}
		};

		this.controller.upload(file, RUN_ID, CALL_ID, null);

		assertThat(closed).as("上传输入流必须 try-with-resources（容器流不关会漏到连接池/临时文件）").isTrue();
	}

	@Test
	void uploadSupersedesThePreviousFileOnASingleAttachmentForm() throws Exception {
		waitingForm(ONE_ATTACHMENT_FIELD);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-a.pdf", Path.of("/data/a.pdf")))
			.thenReturn(new StoredFile("stored-b.pdf", Path.of("/data/b.pdf")));

		var first = this.controller.upload(pdf("a.pdf"), RUN_ID, CALL_ID, null);
		// 前端"重新选择文件"= 再上传一次（没有删除接口）：单附件字段的表单必须能顶掉旧件，
		// 否则选错一次就再也改不了。在册件数仍然 ≤ 字段数（1）。
		var second = this.controller.upload(pdf("b.pdf"), RUN_ID, CALL_ID, null);

		assertThat(second.getData().get("fileId")).isNotEqualTo(first.getData().get("fileId"));
		assertThat(this.attachments.find((String) first.getData().get("fileId")))
			.as("被顶掉的登记必须删掉").isNull();
		assertThat(this.attachments.findByForm(RUN_ID, CALL_ID)).hasSize(1);
		org.mockito.Mockito.verify(this.storage).delete("stored-a.pdf");
	}

	@Test
	void aRejectedFieldKeyNeverSupersedesTheExistingUpload() throws Exception {
		waitingForm(ONE_ATTACHMENT_FIELD);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-keep.pdf", Path.of("/data/keep.pdf")));
		var kept = this.controller.upload(pdf("keep.pdf"), RUN_ID, CALL_ID, null);
		String keptId = (String) kept.getData().get("fileId");

		// 非法目标字段是一次"错请求"：它不得先顶掉旧件再报错（否则用户上传一次错字段就丢了文件）
		assertThatThrownBy(() -> this.controller.upload(pdf("bad.pdf"), RUN_ID, CALL_ID, null, "not-a-field"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不是本表单的附件字段");

		assertThat(this.attachments.find(keptId)).as("错请求不得改动在册件").isNotNull();
		org.mockito.Mockito.verify(this.storage, org.mockito.Mockito.never()).delete("stored-keep.pdf");
	}

	@Test
	void reuploadKeepsTheOldFileWhenStoringTheNewOneFails() throws Exception {
		waitingForm(ONE_ATTACHMENT_FIELD);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-old.pdf", Path.of("/data/old.pdf")));
		String oldId = (String) this.controller.upload(pdf("old.pdf"), RUN_ID, CALL_ID, null).getData().get("fileId");

		// 磁盘写失败（最后一版证据不能因为"想换一张"就没了）
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenThrow(new java.io.IOException("模拟磁盘写失败"));

		assertThatThrownBy(() -> this.controller.upload(pdf("new.pdf"), RUN_ID, CALL_ID, null))
			.isInstanceOf(java.io.IOException.class);
		assertThat(this.attachments.find(oldId)).as("新件没写成，旧件必须原样在册").isNotNull();
		org.mockito.Mockito.verify(this.storage, org.mockito.Mockito.never()).delete("stored-old.pdf");
	}

	@Test
	void reuploadKeepsTheOldFileWhenRegisteringTheNewOneFails() throws Exception {
		waitingForm(ONE_ATTACHMENT_FIELD);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-old.pdf", Path.of("/data/old.pdf")))
			.thenReturn(new StoredFile("stored-new.pdf", Path.of("/data/new.pdf")));
		String oldId = (String) this.controller.upload(pdf("old.pdf"), RUN_ID, CALL_ID, null).getData().get("fileId");

		// 登记失败（Redis 抖动）：新件删掉即可，旧件必须还在（否则用户换一张就丢了原证据）
		this.attachments.failRegister = true;
		assertThatThrownBy(() -> this.controller.upload(pdf("new.pdf"), RUN_ID, CALL_ID, null))
			.isInstanceOf(com.example.configmgr.common.AiStateUnavailableException.class);

		assertThat(this.attachments.find(oldId)).as("登记失败不得动旧件").isNotNull();
		org.mockito.Mockito.verify(this.storage).delete("stored-new.pdf");
		org.mockito.Mockito.verify(this.storage, org.mockito.Mockito.never()).delete("stored-old.pdf");
	}

	@Test
	void reuploadRemovesTheOldRegistrationOnlyAfterTheNewOneIsRegistered() throws Exception {
		waitingForm(ONE_ATTACHMENT_FIELD);
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-a.pdf", Path.of("/data/a.pdf")))
			.thenReturn(new StoredFile("stored-b.pdf", Path.of("/data/b.pdf")));
		this.controller.upload(pdf("a.pdf"), RUN_ID, CALL_ID, null);

		this.controller.upload(pdf("b.pdf"), RUN_ID, CALL_ID, null);

		int register = this.attachments.events.lastIndexOf("register");
		int removeOld = this.attachments.events.indexOf("deleteRegistration");
		assertThat(register).as("必须先登记新件").isGreaterThanOrEqualTo(0);
		assertThat(removeOld).as("新件登记成功之后才回收旧件").isGreaterThan(register);
	}

	@Test
	void uploadFailsClosedWhenTheAuthoritativeSessionIsUnavailable() throws Exception {
		waitingForm(TWO_ATTACHMENT_FIELDS);
		// 快照缺失：没有 sessionId 的登记不进会话索引 ⇒ 会话销毁看不见它 ⇒ 文件成孤儿
		when(this.runStore.get(RUN_ID)).thenReturn(null);
		assertThatThrownBy(() -> this.controller.upload(pdf("a.pdf"), RUN_ID, CALL_ID, null))
			.isInstanceOf(com.example.configmgr.common.AiStateUnavailableException.class)
			.hasMessageContaining("会话归属");

		// 读取失败同样 fail-closed（不采信请求参数、不静默无索引成功）
		when(this.runStore.get(RUN_ID)).thenThrow(
				new com.example.configmgr.common.AiStateUnavailableException("Redis 不可用"));
		assertThatThrownBy(() -> this.controller.upload(pdf("a.pdf"), RUN_ID, CALL_ID, "sess-of-someone-else"))
			.isInstanceOf(com.example.configmgr.common.AiStateUnavailableException.class);

		// fail-closed 发生在落盘之前：不得留下无主的物理文件
		org.mockito.Mockito.verify(this.storage, org.mockito.Mockito.never())
			.store(org.mockito.ArgumentMatchers.any(InputStream.class), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void unparsableSchemaIsLoggedWithoutTheRawArguments() throws Exception {
		String marker = "SENSITIVE-DEFAULT-MARKER-9f3a";
		waitingForm("{\"form\": {\"scenario\": \"CLARIFY\", \"fields\": [{\"label\": \"" + marker + "\"}");
		Logger logger = (Logger) LoggerFactory.getLogger(GenerativeFormAttachmentController.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> this.controller.upload(pdf("a.pdf"), RUN_ID, CALL_ID, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("没有附件字段");
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).as("必须留下一条诊断日志").isNotEmpty();
		assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
			.as("入参原文可能带用户默认值/长文本，不得进日志")
			.noneMatch(message -> message.contains(marker));
	}

	@Test
	void reuploadSkipsABlankStoragePathOnTheOldRegistration() throws Exception {
		waitingForm(ONE_ATTACHMENT_FIELD);
		// 历史登记可能没有 storagePath（迁移前的行）：删盘必须安全跳过，不能因此让重传失败
		this.attachments.register(RUN_ID, CALL_ID, "sess-authoritative", "legacy.pdf", 3, "application/pdf", "");
		when(this.storage.store(any(InputStream.class), anyString()))
			.thenReturn(new StoredFile("stored-new.pdf", Path.of("/data/new.pdf")));

		this.controller.upload(pdf("new.pdf"), RUN_ID, CALL_ID, null);

		assertThat(this.attachments.findByForm(RUN_ID, CALL_ID)).hasSize(1);
		org.mockito.Mockito.verify(this.storage, org.mockito.Mockito.never()).delete("");
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private static String sixAttachmentFields() {
		StringBuilder fields = new StringBuilder();
		for (int i = 1; i <= 6; i++) {
			fields.append(i > 1 ? "," : "").append("{\"key\":\"f").append(i)
					.append("\",\"label\":\"附件").append(i).append("\",\"type\":\"attachment\"}");
		}
		return "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":[" + fields + "]}}";
	}

	private void waitingForm(String arguments) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setArguments(arguments);
		when(this.runStore.pending(RUN_ID, CALL_ID)).thenReturn(pending);
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("sess-authoritative");
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
	}

	private static MockMultipartFile pdf(String name) {
		return new MockMultipartFile("file", name, "application/pdf", "pdf-bytes".getBytes());
	}

	/** 进程内登记簿（含表单/会话索引语义）。 */
	private static final class InMemoryStore implements FormAttachmentStore {

		private final Map<String, Attachment> items = new LinkedHashMap<>();

		private final Set<String> consumed = new java.util.HashSet<>();

		private final AtomicInteger seq = new AtomicInteger();

		private boolean failRegister;

		private final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

		@Override
		public synchronized Attachment register(String runId, String toolCallId, String sessionId, String fileName,
				long size, String contentType, String storagePath) {
			if (this.failRegister) {
				throw new com.example.configmgr.common.AiStateUnavailableException("模拟登记失败（Redis 抖动）");
			}
			Attachment attachment = new Attachment("att_" + String.format("upl%05d", this.seq.incrementAndGet()),
					runId, toolCallId, sessionId, fileName, size, contentType, storagePath);
			this.items.put(attachment.fileId(), attachment);
			this.events.add("register");
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
		public boolean wasConsumed(String fileId) {
			return this.consumed.contains(fileId);
		}

		@Override
		public synchronized List<Attachment> findByForm(String runId, String toolCallId) {
			List<Attachment> out = new ArrayList<>();
			for (Attachment attachment : this.items.values()) {
				if (Objects.equals(runId, attachment.runId()) && Objects.equals(toolCallId, attachment.toolCallId())) {
					out.add(attachment);
				}
			}
			return out;
		}

		@Override
		public synchronized Attachment deleteRegistration(String fileId) {
			this.events.add("deleteRegistration");
			return this.items.remove(fileId);
		}

		@Override
		public synchronized List<Attachment> findBySessionId(String sessionId) {
			List<Attachment> out = new ArrayList<>();
			for (Attachment attachment : this.items.values()) {
				if (Objects.equals(sessionId, attachment.sessionId())) {
					out.add(attachment);
				}
			}
			return out;
		}

	}

}
