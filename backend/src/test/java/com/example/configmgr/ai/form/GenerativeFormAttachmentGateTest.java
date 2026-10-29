package com.example.configmgr.ai.form;

import com.example.configmgr.common.AiStateUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GF 优化批（G-E 附件闸门 / G-D 选项数据源）的用例：存在 / 归属 / 一次性三重校验，
 * 以及"只有后端注册的数据源能被解析"的闭集口径。
 */
class GenerativeFormAttachmentGateTest {

	private static final String ARGS = "{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":["
			+ "{\"key\":\"evidence\",\"label\":\"附件\",\"type\":\"attachment\",\"required\":true}]}}";

	private final InMemoryStore store = new InMemoryStore();

	private final GenerativeFormGuard guard = new GenerativeFormGuard(new com.fasterxml.jackson.databind.ObjectMapper(),
			this.store);

	@Test
	void acceptsAnOwnedAttachmentAndConsumesItOnlyAfterTheDecision() {
		FormAttachmentStore.Attachment attachment = this.store.register("run-1", "call-1", "sess-1",
				"evidence.pdf", 1024, "application/pdf", "/tmp/evidence.pdf");
		String submitted = "{\"evidence\":\"" + attachment.fileId() + "\"}";

		FrontendToolGuardVerdict verdict = inspect("run-1", "call-1", submitted);
		assertThat(verdict.accepted()).isTrue();
		// 模型只看到元数据（文件名/大小/fileId），文件内容永不进上下文
		assertThat(verdict.suffix()).contains("evidence.pdf").contains("1024 字节")
				.contains(attachment.fileId());
		// 校验是只读的：决策还没落库，登记必须还在（否则"可修正后重试"就是假话）
		assertThat(this.store.find(attachment.fileId())).isNotNull();

		// 决策落库之后才消费；此后同一 fileId 二次提交即 ATTACHMENT_CONSUMED
		this.guard.settleAcceptedResult("run-1", "call-1", ARGS, submitted);
		assertThat(this.store.find(attachment.fileId())).isNull();
		FrontendToolGuardVerdict again = inspect("run-1", "call-1", submitted);
		assertThat(again.accepted()).isFalse();
		assertThat(again.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_CONSUMED);
	}

	@Test
	void rejectsAttachmentsThatDoNotExistOrBelongElsewhere() {
		FormAttachmentStore.Attachment attachment = this.store.register("run-1", "call-1", "sess-1",
				"evidence.pdf", 1024, "application/pdf", "/tmp/evidence.pdf");

		FrontendToolGuardVerdict missing = inspect("run-1", "call-1", "{\"evidence\":\"att_does_not_exist\"}");
		assertThat(missing.accepted()).isFalse();
		assertThat(missing.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_NOT_FOUND);

		FrontendToolGuardVerdict otherRun = inspect("run-2", "call-1",
				"{\"evidence\":\"" + attachment.fileId() + "\"}");
		assertThat(otherRun.accepted()).isFalse();
		assertThat(otherRun.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_FORBIDDEN);

		FrontendToolGuardVerdict otherCall = inspect("run-1", "call-2",
				"{\"evidence\":\"" + attachment.fileId() + "\"}");
		assertThat(otherCall.accepted()).isFalse();
		assertThat(otherCall.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.ATTACHMENT_FORBIDDEN);

		// 被拒的回灌**不消费**附件：改对归属后仍可用
		assertThat(this.store.find(attachment.fileId())).isNotNull();
		assertThat(inspect("run-1", "call-1", "{\"evidence\":\"" + attachment.fileId() + "\"}").accepted()).isTrue();
	}

	@Test
	void attachmentValueShapeIsStillGuardedByRules() {
		FrontendToolGuardVerdict url = inspect("run-1", "call-1",
				"{\"evidence\":\"https://evil.example/a.bin\"}");
		assertThat(url.accepted()).isFalse();
		assertThat(url.issues()).extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_ATTACHMENT_ID_INVALID);
	}

	@Test
	void registryUnavailableBecomesTypedRetryableIssue() {
		InMemoryStore unavailable = new InMemoryStore();
		unavailable.failReads = true;
		GenerativeFormGuard failingGuard = new GenerativeFormGuard(
				new com.fasterxml.jackson.databind.ObjectMapper(), unavailable);
		FormAttachmentStore.Attachment attachment = new FormAttachmentStore.Attachment("att_blocked0001", "run-1",
				"call-1", "sess-1", "evidence.pdf", 1024, "application/pdf", "/tmp/evidence.pdf");
		unavailable.items.put(attachment.fileId(), attachment);

		FrontendToolGuardVerdict verdict = new FrontendToolGuardVerdict(
				failingGuard.inspectResult("run-1", "call-1", ARGS,
						"{\"evidence\":\"" + attachment.fileId() + "\"}").accepted(),
				failingGuard.inspectResult("run-1", "call-1", ARGS,
						"{\"evidence\":\"" + attachment.fileId() + "\"}").issues(),
				null);

		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.issues()).extracting(FormIssue::code)
			.containsExactly(FormIssueCodes.ATTACHMENT_UNAVAILABLE);
		assertThat(verdict.issues().get(0).message()).doesNotContain("AiStateUnavailableException")
			.doesNotContain("Redis");
	}

	@Test
	void consumeOutageAfterTheDecisionIsLoggedInsteadOfReopeningTheSubmission() {
		InMemoryStore unavailable = new InMemoryStore();
		unavailable.failConsume = true;
		GenerativeFormGuard failingGuard = new GenerativeFormGuard(
				new com.fasterxml.jackson.databind.ObjectMapper(), unavailable);
		FormAttachmentStore.Attachment attachment = unavailable.register("run-1", "call-1", "sess-1",
				"evidence.pdf", 1024, "application/pdf", "/tmp/evidence.pdf");
		String submitted = "{\"evidence\":\"" + attachment.fileId() + "\"}";

		// 校验阶段不碰消费：登记簿读可用时，回灌照常放行
		assertThat(failingGuard.inspectResult("run-1", "call-1", ARGS, submitted).accepted()).isTrue();

		// 决策落库之后的消费故障：不抛、不判"可重试"，登记留在原处（保守：多留不漏删）
		failingGuard.settleAcceptedResult("run-1", "call-1", ARGS, submitted);
		assertThat(unavailable.find(attachment.fileId())).isNotNull();
		assertThat(unavailable.wasConsumed(attachment.fileId())).isFalse();
	}

	@Test
	void optionSourceRegistryIsAClosedSet() {
		FormOptionSource fake = new FormOptionSource() {
			@Override
			public String id() {
				return FormOptionSource.DefinitionFieldOptionSource.ID;
			}

			@Override
			public List<Map<String, String>> options(String parentValue) {
				return List.of(Map.of("value", parentValue + ".field", "label", "字段"));
			}
		};
		FormOptionSourceRegistry registry = new FormOptionSourceRegistry(List.of(fake));

		assertThat(registry.ids()).containsExactly(FormOptionSource.DefinitionFieldOptionSource.ID);
		assertThat(registry.supports("https://evil.example")).isFalse();
		assertThat(registry.options("https://evil.example", "CURRENCY")).isNull();
		assertThat(registry.options(FormOptionSource.DefinitionFieldOptionSource.ID, "CURRENCY"))
				.containsExactly(Map.of("value", "CURRENCY.field", "label", "字段"));
		// 闸门的闭集与注册表同源：注册表里能解析的 id 必须在 schema 白名单里
		assertThat(GenerativeFormRules.OPTION_SOURCE_IDS).contains(FormOptionSource.DefinitionFieldOptionSource.ID);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private FrontendToolGuardVerdict inspect(String runId, String toolCallId, String result) {
		var verdict = this.guard.inspectResult(runId, toolCallId, ARGS, result);
		return new FrontendToolGuardVerdict(verdict.accepted(), verdict.issues(), verdict.resultSuffix());
	}

	record FrontendToolGuardVerdict(boolean accepted, List<FormIssue> issues, String suffix) {
	}

	/** 进程内登记簿（生产实现见 {@code RedisFormAttachmentStore}；用例只需语义）。 */
	private static final class InMemoryStore implements FormAttachmentStore {

		private final Map<String, Attachment> items = new LinkedHashMap<>();

		private final AtomicInteger seq = new AtomicInteger();

		private boolean failReads;

		private boolean failConsume;

		@Override
		public Attachment register(String runId, String toolCallId, String sessionId, String fileName, long size,
				String contentType, String storagePath) {
			String fileId = "att_" + String.format("test%04d", this.seq.incrementAndGet());
			Attachment attachment = new Attachment(fileId, runId, toolCallId, sessionId, fileName, size,
					contentType, storagePath);
			this.items.put(fileId, attachment);
			return attachment;
		}

		@Override
		public Attachment find(String fileId) {
			if (this.failReads) {
				throw new AiStateUnavailableException("模拟 Redis 读失败（内部细节）");
			}
			return this.items.get(fileId);
		}

		@Override
		public boolean consume(String fileId) {
			if (this.failConsume) {
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

		private final java.util.Set<String> consumed = new java.util.HashSet<>();
	}

}
