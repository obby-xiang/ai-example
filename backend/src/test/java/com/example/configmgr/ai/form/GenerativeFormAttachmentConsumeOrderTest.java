package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.file.FileStorageService;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-E 附件的<b>校验与消费分离</b>（2026-10-29 修复批）。
 *
 * <p>旧实现的 {@code inspectResult} 在"值校验通过"时就消费附件并删盘 —— 那一步发生在决策
 * 抢 guard / 落库<b>之前</b>。于是同一份回灌里只要有一件附件消费失败（并发、登记过期、Redis
 * 抖动），前几件已经被删掉，回灌却被判成"可修正后重试"：用户手里剩下的 fileId 全是死的，
 * 重试注定 {@code ATTACHMENT_CONSUMED}。更糟的是"决策没写成"与"附件已消费"同时发生，
 * 卡还 PENDING 但数据已经不可用。
 *
 * <p>现在的口径：
 * <ol>
 * <li>{@code inspectResult} 只做<b>只读</b>三段校验（存在 / 归属 / 形状），一件都不消费；</li>
 * <li>消费发生在决策落库之后的 {@code settleAcceptedResult}（{@link FrontendToolGuard} 的
 * 决策后钩子）—— 此时结论已经定了，部分失败只记日志，绝不再对外宣称"可重试"；</li>
 * <li>多附件先<b>全部</b>校验并全部取到元数据，再逐件消费；</li>
 * <li>表单收口（决策接受 / 用户放弃）后回收该表单名下<b>没被提交</b>的登记与落盘文件
 * （用户重传后放弃的旧件就是孤儿）。</li>
 * </ol>
 */
class GenerativeFormAttachmentConsumeOrderTest {

	private static final String ONE_ATTACHMENT = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"evidence\",\"label\":\"附件\",\"type\":\"attachment\",\"required\":true}]}}";

	private static final String TWO_ATTACHMENTS = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"evidence\",\"label\":\"证据\",\"type\":\"attachment\"},"
			+ "{\"key\":\"contract\",\"label\":\"合同\",\"type\":\"attachment\"}]}}";

	private static final Path STORAGE_ROOT = createStorageRoot();

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
	void inspectionValidationDoesNotConsumeAnyAttachment() throws IOException {
		Path file = Files.writeString(STORAGE_ROOT.resolve("inspect-only.pdf"), "pdf-bytes");
		FormAttachmentStore.Attachment attachment = register("evidence.pdf", file);

		FrontendToolGuard.Verdict verdict = this.guard.inspectResult("run-1", "call-1", ONE_ATTACHMENT,
				values("evidence", attachment.fileId()));

		assertThat(verdict.accepted()).isTrue();
		assertThat(this.store.find(attachment.fileId())).as("校验是只读的：登记必须还在").isNotNull();
		assertThat(file).as("校验是只读的：落盘文件不得被删").exists();
	}

	@Test
	void acceptedDecisionConsumesAllAttachmentsAndDeletesTheirFiles() throws IOException {
		Path evidence = Files.writeString(STORAGE_ROOT.resolve("decided-evidence.pdf"), "pdf-bytes");
		Path contract = Files.writeString(STORAGE_ROOT.resolve("decided-contract.pdf"), "pdf-bytes");
		FormAttachmentStore.Attachment first = register("evidence.pdf", evidence);
		FormAttachmentStore.Attachment second = register("contract.pdf", contract);
		String submitted = "{\"evidence\":\"" + first.fileId() + "\",\"contract\":\"" + second.fileId() + "\"}";

		// 决策落库之后的钩子：一次提交里的每一件都要消费（多附件不得只消费第一件）
		this.guard.settleAcceptedResult("run-1", "call-1", TWO_ATTACHMENTS, submitted);

		assertThat(this.store.find(first.fileId())).isNull();
		assertThat(this.store.find(second.fileId())).isNull();
		assertThat(this.store.wasConsumed(first.fileId())).isTrue();
		assertThat(this.store.wasConsumed(second.fileId())).isTrue();
		assertThat(evidence).doesNotExist();
		assertThat(contract).doesNotExist();
	}

	@Test
	void failedConsumptionKeepsTheRegistrationEvenWhenTheFormCloses() throws IOException {
		Path evidence = Files.writeString(STORAGE_ROOT.resolve("partial-evidence.pdf"), "pdf-bytes");
		Path contract = Files.writeString(STORAGE_ROOT.resolve("partial-contract.pdf"), "pdf-bytes");
		// 生产实现（Redis 登记簿的进程内路径）+ 只对 consume 注入故障：findByForm 是真实语义，
		// 不是"默认空表"的替身，避免收口回收被空表悄悄跳过而假绿。
		FailingConsumeStore production = new FailingConsumeStore();
		GenerativeFormGuard productionGuard = new GenerativeFormGuard(new ObjectMapper(), production,
				storageService());
		FormAttachmentStore.Attachment first = production.register("run-1", "call-1", "sess-1", "evidence.pdf",
				sizeOf(evidence), "application/pdf", evidence.getFileName().toString());
		FormAttachmentStore.Attachment second = production.register("run-1", "call-1", "sess-1", "contract.pdf",
				sizeOf(contract), "application/pdf", contract.getFileName().toString());
		production.failConsumeFor = second.fileId();
		String submitted = "{\"evidence\":\"" + first.fileId() + "\",\"contract\":\"" + second.fileId() + "\"}";

		// 决策已经落库：消费失败只告警，不得回头改结论；失败件必须保留到 TTL，
		// 不能被"表单收口回收"顺手删掉（那等于把"保留到 TTL"变成"立刻删"）。
		productionGuard.settleAcceptedResult("run-1", "call-1", TWO_ATTACHMENTS, submitted);

		assertThat(production.find(first.fileId())).as("已消费的件照常删除").isNull();
		assertThat(evidence).doesNotExist();
		assertThat(production.find(second.fileId())).as("消费失败的件保留登记").isNotNull();
		assertThat(contract).exists();
		assertThat(production.findByForm("run-1", "call-1"))
			.as("生产 findByForm 只应剩失败件（失败件不得被收口回收）")
			.extracting(FormAttachmentStore.Attachment::fileId)
			.containsExactly(second.fileId());
	}

	@Test
	void leftoverUploadsOfTheFormAreReclaimedWhenTheDecisionIsAccepted() throws IOException {
		Path evidence = Files.writeString(STORAGE_ROOT.resolve("kept-evidence.pdf"), "pdf-bytes");
		Path superseded = Files.writeString(STORAGE_ROOT.resolve("superseded.pdf"), "pdf-bytes");
		FormAttachmentStore.Attachment submitted = register("evidence.pdf", evidence);
		FormAttachmentStore.Attachment abandoned = register("superseded.pdf", superseded);

		// 只提交第一件：同表单的其余上传件（用户重传后放弃的旧件）在收口时一起回收
		this.guard.settleAcceptedResult("run-1", "call-1", TWO_ATTACHMENTS, values("evidence", submitted.fileId()));

		assertThat(this.store.find(submitted.fileId())).isNull();
		assertThat(this.store.find(abandoned.fileId())).as("没被提交的上传件不得留成孤儿").isNull();
		assertThat(superseded).doesNotExist();
	}

	@Test
	void discardedFormReclaimsEveryRegisteredUpload() throws IOException {
		Path evidence = Files.writeString(STORAGE_ROOT.resolve("dismissed-evidence.pdf"), "pdf-bytes");
		Path contract = Files.writeString(STORAGE_ROOT.resolve("dismissed-contract.pdf"), "pdf-bytes");
		FormAttachmentStore.Attachment first = register("evidence.pdf", evidence);
		FormAttachmentStore.Attachment second = register("contract.pdf", contract);

		// 用户关掉表单（cancelled=true）：一件都没提交，两件都不得留到 TTL
		this.guard.settleDiscardedResult("run-1", "call-1");

		assertThat(this.store.find(first.fileId())).isNull();
		assertThat(this.store.find(second.fileId())).isNull();
		assertThat(evidence).doesNotExist();
		assertThat(contract).doesNotExist();
		// 放弃不是消费：不置"已消费"墓碑（两者对用户是不同的提示）
		assertThat(this.store.wasConsumed(first.fileId())).isFalse();
	}

	@Test
	void formWaitingPastItsRegistrationTtlIsStillSubmittableBecauseRenewalTouchesIt() throws Exception {
		ExpiringStore expiring = new ExpiringStore();
		GenerativeFormGuard expiringGuard = new GenerativeFormGuard(new ObjectMapper(), expiring);
		com.example.configmgr.ai.conformance.InMemoryRunStore runStore =
				new com.example.configmgr.ai.conformance.InMemoryRunStore();
		runStore.seedRun("run-touch", "sess-touch");
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-touch");
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setArguments(ONE_ATTACHMENT);
		runStore.putPending("run-touch", pending);
		com.example.configmgr.ai.run.RunRegistry registry = new com.example.configmgr.ai.run.RunRegistry(runStore,
				new ObjectMapper(), null);
		com.example.configmgr.ai.run.CancellationRegistry cancellations = org.mockito.Mockito
			.mock(com.example.configmgr.ai.run.CancellationRegistry.class);
		org.mockito.Mockito.when(cancellations.isCancelled("run-touch")).thenReturn(false);
		org.mockito.Mockito.when(cancellations.onCancel(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> (AutoCloseable) () -> {
				});
		ConfirmGate gate = new ConfirmGate(runStore, registry, new AiProperties(), cancellations,
				new com.example.configmgr.ai.run.ToolActivityBeacon(), List.of(expiringGuard));
		FormAttachmentStore.Attachment attachment = expiring.register("run-touch", "call-touch", "sess-touch",
				"late.pdf", 9L, "application/pdf", null);

		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			// 表单已经等了 23 小时；心跳的一拍会顺带 touch 附件登记
			expiring.advance(java.time.Duration.ofHours(23));
			java.util.concurrent.Future<PendingToolCall> waiting = pool.submit(() -> gate.awaitFrontendResult(
					"run-touch", pending));
			Thread.sleep(2_600);
			// 再过 2 小时 = 距登记 25 小时（原 TTL 24h 已过），但续期已把它拉回满额 ⇒ 仍可提交
			expiring.advance(java.time.Duration.ofHours(2));
			ConfirmGate.Submission submission = gate.submitFrontendResult("run-touch", "call-touch",
					"{\"evidence\":\"" + attachment.fileId() + "\"}", "http-post");
			assertThat(submission.outcome()).as("续期后 25 小时仍可提交").isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			waiting.get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(expiring.find(attachment.fileId())).isNull();
		}
		finally {
			pool.shutdownNow();
		}

		// 对照：同样 25 小时但没有续期 ⇒ 登记已过期，提交被拒（ATTACHMENT_NOT_FOUND）
		ExpiringStore idle = new ExpiringStore();
		GenerativeFormGuard idleGuard = new GenerativeFormGuard(new ObjectMapper(), idle);
		FormAttachmentStore.Attachment stale = idle.register("run-idle", "call-idle", "sess-idle", "idle.pdf", 9L,
				"application/pdf", null);
		idle.advance(java.time.Duration.ofHours(25));
		var verdict = idleGuard.inspectResult("run-idle", "call-idle", ONE_ATTACHMENT,
				"{\"evidence\":\"" + stale.fileId() + "\"}");
		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_NOT_FOUND);
	}

	@Test
	void parkedFormStaysSubmittableAfterAReadSideRenewal() throws Exception {
		ExpiringStore expiring = new ExpiringStore();
		GenerativeFormGuard expiringGuard = new GenerativeFormGuard(new ObjectMapper(), expiring);
		AiProperties parked = new AiProperties();
		parked.getPark().setEnabled(true);
		com.example.configmgr.ai.conformance.InMemoryRunStore runStore =
				new com.example.configmgr.ai.conformance.InMemoryRunStore();
		runStore.seedRun("run-park", "sess-park");
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-park");
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setArguments(ONE_ATTACHMENT);
		runStore.putPending("run-park", pending);
		com.example.configmgr.ai.run.RunRegistry registry = new com.example.configmgr.ai.run.RunRegistry(runStore,
				new ObjectMapper(), null);
		com.example.configmgr.ai.run.CancellationRegistry cancellations = org.mockito.Mockito
			.mock(com.example.configmgr.ai.run.CancellationRegistry.class);
		org.mockito.Mockito.when(cancellations.isCancelled("run-park")).thenReturn(false);
		org.mockito.Mockito.when(cancellations.onCancel(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> (AutoCloseable) () -> {
				});
		ConfirmGate gate = new ConfirmGate(runStore, registry, parked, cancellations,
				new com.example.configmgr.ai.run.ToolActivityBeacon(), List.of(expiringGuard));
		FormAttachmentStore.Attachment attachment = expiring.register("run-park", "call-park", "sess-park",
				"parked.pdf", 9L, "application/pdf", null);

		// park=true：没有等待线程就没有心跳。刷新（/runs/current、重挂 /events、决策/回灌端点）的读侧续期
		// 必须顺带把附件登记续上，否则卡还活着、附件先过期。
		expiring.advance(java.time.Duration.ofHours(23));
		gate.touchSuspendedResources("run-park");
		expiring.advance(java.time.Duration.ofHours(2));

		ConfirmGate.Submission submission = gate.submitFrontendResult("run-park", "call-park",
				"{\"evidence\":\"" + attachment.fileId() + "\"}", "http-post");

		assertThat(submission.outcome()).as("park= 真下读侧续期后 25 小时仍可提交")
			.isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(expiring.find(attachment.fileId())).isNull();
	}

	@Test
	void settleWithNoParsableAttachmentRefsNeverReclaimsUploads() throws IOException {
		Path file = Files.writeString(STORAGE_ROOT.resolve("defensive-keep.pdf"), "pdf-bytes");
		FormAttachmentStore.Attachment attachment = register("defensive-keep.pdf", file);

		// 回灌原文解析不出任何附件引用（JSON 坏了 / 形状异常）：这时"无 refs"既可能是"没传附件"，
		// 也可能是"解析失败"。收口回收若照删，会把用户已上传的附件当"未提交"删光 —— fail-safe 是不回收。
		this.guard.settleAcceptedResult("run-1", "call-1", ONE_ATTACHMENT, "{not-json");

		assertThat(this.store.find(attachment.fileId())).as("解析不出引用时不得回收").isNotNull();
		assertThat(file).exists();
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private FormAttachmentStore.Attachment register(String fileName, Path file) {
		return this.store.register("run-1", "call-1", "sess-1", fileName, sizeOf(file), "application/pdf",
				file == null ? null : file.getFileName().toString());
	}

	private static long sizeOf(Path file) {
		try {
			return file == null ? 0L : Files.size(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String values(String key, String fileId) {
		return "{\"" + key + "\":\"" + fileId + "\"}";
	}

	private FileStorageService storageService() {
		return new FileStorageService(properties());
	}

	private static AppProperties properties() {
		AppProperties properties = new AppProperties();
		properties.getFile().setStoragePath(STORAGE_ROOT.toString());
		return properties;
	}

	private static Path createStorageRoot() {
		try {
			return Files.createTempDirectory("flc-attachment-order-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}



	/** 带 TTL 语义的登记簿（虚拟时钟）：语义对齐 Redis 的 EXPIRE —— touch 把每件拉回满额。 */
	private static final class ExpiringStore implements FormAttachmentStore {

		private static final long TTL_MS = java.time.Duration.ofHours(24).toMillis();

		private final Map<String, long[]> expiry = new LinkedHashMap<>();

		private final Map<String, Attachment> items = new LinkedHashMap<>();

		private final Set<String> consumed = new java.util.HashSet<>();

		private final AtomicInteger seq = new AtomicInteger();

		private long nowMs;

		void advance(java.time.Duration duration) {
			this.nowMs += duration.toMillis();
		}

		@Override
		public synchronized Attachment register(String runId, String toolCallId, String sessionId, String fileName,
				long size, String contentType, String storagePath) {
			Attachment attachment = new Attachment("att_exp" + String.format("%05d", this.seq.incrementAndGet()),
					runId, toolCallId, sessionId, fileName, size, contentType, storagePath);
			this.items.put(attachment.fileId(), attachment);
			this.expiry.put(attachment.fileId(), new long[] { this.nowMs + TTL_MS });
			return attachment;
		}

		@Override
		public synchronized Attachment find(String fileId) {
			Attachment attachment = this.items.get(fileId);
			if (attachment == null) {
				return null;
			}
			long[] deadline = this.expiry.get(fileId);
			return deadline != null && this.nowMs < deadline[0] ? attachment : null;
		}

		@Override
		public synchronized boolean consume(String fileId) {
			if (find(fileId) == null) {
				return false;
			}
			this.items.remove(fileId);
			this.expiry.remove(fileId);
			this.consumed.add(fileId);
			return true;
		}

		@Override
		public synchronized boolean wasConsumed(String fileId) {
			return this.consumed.contains(fileId);
		}

		@Override
		public synchronized java.util.List<Attachment> findByForm(String runId, String toolCallId) {
			java.util.List<Attachment> out = new java.util.ArrayList<>();
			for (String fileId : this.items.keySet()) {
				Attachment attachment = find(fileId);
				if (attachment != null && java.util.Objects.equals(runId, attachment.runId())
						&& java.util.Objects.equals(toolCallId, attachment.toolCallId())) {
					out.add(attachment);
				}
			}
			return out;
		}

		@Override
		public synchronized Attachment deleteRegistration(String fileId) {
			this.expiry.remove(fileId);
			return this.items.remove(fileId);
		}

		@Override
		public synchronized void touch(String runId, String toolCallId) {
			for (String fileId : this.items.keySet()) {
				Attachment attachment = find(fileId);
				if (attachment != null && java.util.Objects.equals(runId, attachment.runId())
						&& java.util.Objects.equals(toolCallId, attachment.toolCallId())) {
					this.expiry.put(fileId, new long[] { this.nowMs + TTL_MS });
				}
			}
		}
	}

	@Test
	void consumptionSkipsABlankStoragePath() {
		RecordingStorage recording = new RecordingStorage();
		GenerativeFormGuard recordingGuard = new GenerativeFormGuard(new ObjectMapper(), this.store, recording);
		FormAttachmentStore.Attachment legacy = this.store.register("run-1", "call-1", "sess-1", "legacy.pdf", 3L,
				"application/pdf", "");
		String submitted = values("evidence", legacy.fileId());

		assertThat(recordingGuard.inspectResult("run-1", "call-1", ONE_ATTACHMENT, submitted).accepted()).isTrue();
		recordingGuard.settleAcceptedResult("run-1", "call-1", ONE_ATTACHMENT, submitted);

		assertThat(this.store.find(legacy.fileId())).isNull();
		assertThat(recording.deleted).as("空 storagePath 必须安全跳过（历史登记没有路径）").isEmpty();
	}

	/** 记录删盘调用的存储（真实 {@link FileStorageService} 的相对键校验仍在）。 */
	private static final class RecordingStorage extends FileStorageService {

		private final java.util.List<String> deleted = new java.util.concurrent.CopyOnWriteArrayList<>();

		RecordingStorage() {
			super(properties());
		}

		@Override
		public void delete(String keyOrPath) {
			this.deleted.add(String.valueOf(keyOrPath));
		}
	}

	/** 生产登记簿（进程内路径）+ consume 故障注入。 */
	private static final class FailingConsumeStore extends RedisFormAttachmentStore {

		private volatile String failConsumeFor;

		FailingConsumeStore() {
			super(providerOfNull(), new ObjectMapper());
		}

		@Override
		public boolean consume(String fileId) {
			if (fileId != null && fileId.equals(this.failConsumeFor)) {
				throw new AiStateUnavailableException("模拟消费失败（Redis 抖动）");
			}
			return super.consume(fileId);
		}
	}

	private static org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> providerOfNull() {
		@SuppressWarnings("unchecked")
		org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> provider = org.mockito.Mockito
			.mock(org.springframework.beans.factory.ObjectProvider.class);
		org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
		return provider;
	}

	/** 进程内登记簿（含表单索引；语义与 {@code RedisFormAttachmentStore} 的索引一致）。 */
	private static final class InMemoryStore implements FormAttachmentStore {

		private final Map<String, Attachment> items = new LinkedHashMap<>();

		private final Set<String> consumed = ConcurrentHashMap.newKeySet();

		private final Set<String> failConsume = ConcurrentHashMap.newKeySet();

		private final AtomicInteger seq = new AtomicInteger();

		private boolean failReads;

		private boolean failFormIndex;

		@Override
		public synchronized Attachment register(String runId, String toolCallId, String sessionId, String fileName,
				long size, String contentType, String storagePath) {
			Attachment attachment = new Attachment("att_" + String.format("ord%05d", this.seq.incrementAndGet()),
					runId, toolCallId, sessionId, fileName, size, contentType, storagePath);
			this.items.put(attachment.fileId(), attachment);
			return attachment;
		}

		@Override
		public synchronized Attachment find(String fileId) {
			if (this.failReads) {
				throw new AiStateUnavailableException("模拟 Redis 读失败（内部细节）");
			}
			return this.items.get(fileId);
		}

		@Override
		public synchronized boolean consume(String fileId) {
			if (this.failConsume.contains(fileId)) {
				throw new AiStateUnavailableException("模拟 Redis 消费失败（内部细节）");
			}
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
			if (this.failFormIndex) {
				throw new AiStateUnavailableException("模拟 Redis 索引读失败（内部细节）");
			}
			List<Attachment> out = new ArrayList<>();
			for (Attachment attachment : this.items.values()) {
				if (java.util.Objects.equals(runId, attachment.runId())
						&& java.util.Objects.equals(toolCallId, attachment.toolCallId())) {
					out.add(attachment);
				}
			}
			return out;
		}

		@Override
		public synchronized Attachment deleteRegistration(String fileId) {
			return this.items.remove(fileId);
		}

	}

}
