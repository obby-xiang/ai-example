package com.example.configmgr.ai.form;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.file.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GF 优化批（G-E / G-D）两条固定端点的请求级用例：
 * 上传白名单（类型/大小/在等状态）与选项数据源的闭集解析。
 *
 * <p>不起 Spring：控制器只依赖文件存储、附件登记簿、待决条目读取与选项注册表，
 * 四者在生产里都是既有 Bean；这里直接对请求体的<b>返回形状</b>与**拒绝理由**断言。
 */
class GenerativeFormAttachmentEndpointTest {

	private final FileStorageService storage = mock(FileStorageService.class);

	private final RunStore runStore = mock(RunStore.class);

	private final InMemoryStore attachments = new InMemoryStore();

	private final FormOptionSourceRegistry registry = new FormOptionSourceRegistry(List.of(new FormOptionSource() {
		@Override
		public String id() {
			return FormOptionSource.DefinitionFieldOptionSource.ID;
		}

		@Override
		public List<Map<String, String>> options(String parentValue) {
			return "CURRENCY".equals(parentValue)
					? List.of(Map.of("value", "currency_code", "label", "币种编码"))
					: List.of();
		}
	}));

	private final GenerativeFormAttachmentController controller = new GenerativeFormAttachmentController(this.storage,
			this.attachments, this.runStore, this.registry);

	@Test
	void uploadReturnsFileIdAndMetadataOnly() throws Exception {
		waitingForm("run-1", "call-1");
		when(this.storage.store(any(), eq("evidence.pdf"))).thenReturn("/data/evidence.pdf");

		MockMultipartFile file = new MockMultipartFile("file", "evidence.pdf", "application/pdf",
				"pdf-bytes".getBytes());
		ApiResponse<Map<String, Object>> response = this.controller.upload(file, "run-1", "call-1", "sess-1");

		Map<String, Object> data = response.getData();
		assertThat((String) data.get("fileId")).startsWith("att_");
		assertThat(data.get("fileName")).isEqualTo("evidence.pdf");
		assertThat(data.get("size")).isEqualTo(9L);
		// 存储路径绝不下发（文件内容永不进模型上下文）
		assertThat(data).doesNotContainKey("storagePath");
		assertThat(data.values()).noneMatch(value -> String.valueOf(value).contains("/data/"));
		assertThat(this.attachments.find((String) data.get("fileId")).runId()).isEqualTo("run-1");
	}

	/** I-5：登记失败（B3 fail-closed）时已落盘文件必须回收，不能留下孤儿。 */
	@Test
	void uploadDeletesTheStoredFileWhenRegistrationFails() throws Exception {
		waitingForm("run-1", "call-1");
		when(this.storage.store(any(), eq("evidence.pdf"))).thenReturn("/data/orphan-evidence.pdf");
		FormAttachmentStore failing = new FormAttachmentStore() {
			@Override
			public Attachment register(String runId, String toolCallId, String sessionId, String fileName, long size,
					String contentType, String storagePath) {
				throw new AiStateUnavailableException("模拟 Redis 登记失败");
			}

			@Override
			public Attachment find(String fileId) {
				return null;
			}

			@Override
			public boolean consume(String fileId) {
				return false;
			}

			@Override
			public boolean wasConsumed(String fileId) {
				return false;
			}
		};
		GenerativeFormAttachmentController failingController = new GenerativeFormAttachmentController(this.storage,
				failing, this.runStore, this.registry);
		MockMultipartFile file = new MockMultipartFile("file", "evidence.pdf", "application/pdf",
				"pdf-bytes".getBytes());

		assertThatThrownBy(() -> failingController.upload(file, "run-1", "call-1", "sess-1"))
			.isInstanceOf(AiStateUnavailableException.class);
		verify(this.storage).delete("/data/orphan-evidence.pdf");
	}

	@Test
	void uploadRejectsUnsupportedTypeAndOversizedFiles() {
		waitingForm("run-1", "call-1");
		MockMultipartFile exe = new MockMultipartFile("file", "evil.exe", "application/octet-stream",
				"x".getBytes());
		assertThatThrownBy(() -> this.controller.upload(exe, "run-1", "call-1", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("附件类型不支持")
			.hasMessageContaining("evil.exe");

		byte[] big = new byte[(int) GenerativeFormAttachmentController.MAX_ATTACHMENT_BYTES + 1];
		MockMultipartFile oversized = new MockMultipartFile("file", "big.pdf", "application/pdf", big);
		assertThatThrownBy(() -> this.controller.upload(oversized, "run-1", "call-1", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("附件过大");
	}

	@Test
	void uploadRequiresTheFormToBeWaiting() {
		// 没有待决条目 / 名字不是 generative_form / 已终态 —— 一律拒绝
		MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
		assertThatThrownBy(() -> this.controller.upload(file, "run-1", "call-1", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不在等待填写状态");

		PendingToolCall other = new PendingToolCall();
		other.setToolCallId("call-1");
		other.setName("navigate_to");
		other.setStatus(PendingToolCall.PENDING);
		when(this.runStore.pending("run-1", "call-1")).thenReturn(other);
		assertThatThrownBy(() -> this.controller.upload(file, "run-1", "call-1", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("不在等待填写状态");
	}

	@Test
	void optionSourceResolvesOnlyRegisteredIds() {
		ApiResponse<Map<String, Object>> ok = this.controller.options(
				FormOptionSource.DefinitionFieldOptionSource.ID, "CURRENCY");
		assertThat((List<?>) ok.getData().get("options")).hasSize(1);

		assertThatThrownBy(() -> this.controller.options("https://evil.example/x", "CURRENCY"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("未注册的选项数据源");
		assertThatThrownBy(() -> this.controller.options("definition_fields_v2", "CURRENCY"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void endpointsAreMappedOnTheDocumentedPaths() throws Exception {
		waitingForm("run-1", "call-1");
		when(this.storage.store(any(), eq("evidence.pdf"))).thenReturn("/data/evidence.pdf");
		org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
			.standaloneSetup(this.controller)
			.build();

		MockMultipartFile file = new MockMultipartFile("file", "evidence.pdf", "application/pdf",
				"pdf-bytes".getBytes());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.multipart("/api/ai/form/attachments").file(file)
				.param("runId", "run-1").param("toolCallId", "call-1"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
				.jsonPath("$.data.fileId").value(org.hamcrest.Matchers.startsWith("att_")));

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/ai/form/option-sources/definition_fields").param("value", "CURRENCY"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
				.jsonPath("$.data.options[0].value").value("currency_code"));
	}

	private void waitingForm(String runId, String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setStatus(PendingToolCall.PENDING);
		when(this.runStore.pending(runId, toolCallId)).thenReturn(pending);
	}

	/** 进程内登记簿（生产实现见 {@code RedisFormAttachmentStore}）。 */
	private static final class InMemoryStore implements FormAttachmentStore {

		private final Map<String, Attachment> items = new LinkedHashMap<>();

		private final AtomicInteger seq = new AtomicInteger();

		@Override
		public Attachment register(String runId, String toolCallId, String sessionId, String fileName, long size,
				String contentType, String storagePath) {
			Attachment attachment = new Attachment("att_" + String.format("upl%05d", this.seq.incrementAndGet()),
					runId, toolCallId, sessionId, fileName, size, contentType, storagePath);
			this.items.put(attachment.fileId(), attachment);
			return attachment;
		}

		@Override
		public Attachment find(String fileId) {
			return this.items.get(fileId);
		}

		@Override
		public boolean consume(String fileId) {
			return this.items.remove(fileId) != null;
		}

		@Override
		public boolean wasConsumed(String fileId) {
			return false;
		}
	}

}
