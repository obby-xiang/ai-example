package com.example.configmgr.ai.form;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.file.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成式表单的两条固定配套端点（GF 优化批 G-D 选项级联 / G-E 附件）。
 *
 * <ul>
 * <li>{@code POST /api/ai/form/attachments}：attachment 字段的<b>固定上传口</b>。只接受
 * 白名单类型与大小，文件落既有 {@link FileStorageService}，返回 {@code fileId}
 * （前端把 fileId 当作字段值提交）；<b>文件内容永不进模型上下文</b>。</li>
 * <li>{@code GET /api/ai/form/option-sources/{sourceId}}：{@code optionsFrom.sourceId} 的解析口。
 * 只认后端注册的数据源（{@link FormOptionSourceRegistry}），未注册一律 400 ——
 * 模型给 URL/脚本在 schema 闸门就被拒，到不了这里。</li>
 * </ul>
 *
 * <h2>为什么上传要带 runId + toolCallId</h2>
 * 归属校验比"同一会话"更严：登记时绑定<b>本次挂起的这张表单</b>，回灌时
 * {@link GenerativeFormGuard} 按同一对 id 复核（{@code ATTACHMENT_FORBIDDEN}），
 * 且回灌成功即一次性消费（{@code ATTACHMENT_CONSUMED}）。上传前再核一次"该调用确实在等用户"，
 * 避免任意请求往登记簿里塞文件。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/form")
public class GenerativeFormAttachmentController {

	/** 单件上限（5MB）：够放截图/PDF 证据，又不至于把上传变成文件搬运通道。 */
	public static final long MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024;

	/** 允许的类型（扩展名 → MIME 白名单；只按扩展名判定，内容不做嗅探 —— 文件永不进模型）。 */
	private static final Map<String, Set<String>> ALLOWED = Map.of(
			"png", Set.of("image/png"),
			"jpg", Set.of("image/jpeg"),
			"jpeg", Set.of("image/jpeg"),
			"webp", Set.of("image/webp"),
			"pdf", Set.of("application/pdf"),
			"txt", Set.of("text/plain"),
			"csv", Set.of("text/csv", "application/vnd.ms-excel", "text/plain"),
			"xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

	private final FileStorageService fileStorage;

	private final FormAttachmentStore attachments;

	private final RunStore runStore;

	private final FormOptionSourceRegistry optionSources;

	public GenerativeFormAttachmentController(FileStorageService fileStorage, FormAttachmentStore attachments,
			RunStore runStore, FormOptionSourceRegistry optionSources) {
		this.fileStorage = fileStorage;
		this.attachments = attachments;
		this.runStore = runStore;
		this.optionSources = optionSources;
	}

	/** 上传一件附件（返回 fileId / fileName / size / contentType；不含存储路径）。 */
	@PostMapping("/attachments")
	public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
			@RequestParam("runId") String runId,
			@RequestParam("toolCallId") String toolCallId,
			@RequestParam(value = "sessionId", required = false) String sessionId) throws Exception {
		if (file == null || file.isEmpty()) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST, "附件为空，请重新选择文件");
		}
		if (file.getSize() > MAX_ATTACHMENT_BYTES) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"附件过大：" + file.getSize() + " 字节（上限 " + MAX_ATTACHMENT_BYTES + " 字节）");
		}
		String originalName = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
		String ext = extensionOf(originalName);
		Set<String> mimes = ALLOWED.get(ext);
		if (mimes == null) {
			throw new CodedIllegalArgumentException(ErrorCodes.FILE_TYPE_UNSUPPORTED,
					"附件类型不支持：" + originalName + "（允许 "
							+ String.join("/", ALLOWED.keySet().stream().sorted().toList()) + "）");
		}
		String contentType = file.getContentType() == null ? "" : file.getContentType();
		if (!contentType.isBlank() && !mimes.contains(contentType)) {
			throw new CodedIllegalArgumentException(ErrorCodes.FILE_TYPE_UNSUPPORTED,
					"附件 MIME 与扩展名不符：" + contentType + "（." + ext + " 允许 " + String.join("/", mimes) + "）");
		}
		assertFormIsWaiting(runId, toolCallId);
		String storagePath = this.fileStorage.store(file.getInputStream(), originalName);
		FormAttachmentStore.Attachment stored = this.attachments.register(runId, toolCallId, sessionId,
				originalName, file.getSize(), contentType, storagePath);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("fileId", stored.fileId());
		data.put("fileName", stored.fileName());
		data.put("size", stored.size());
		data.put("contentType", stored.contentType());
		log.info("表单附件已登记 runId={} toolCallId={} fileId={} size={}", runId, toolCallId,
				stored.fileId(), stored.size());
		return ApiResponse.ok(data);
	}

	/** 解析选项数据源（只认注册表里的 id）。 */
	@GetMapping("/option-sources/{sourceId}")
	public ApiResponse<Map<String, Object>> options(@PathVariable String sourceId,
			@RequestParam("value") String parentValue) {
		if (!this.optionSources.supports(sourceId)) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"未注册的选项数据源：" + sourceId + "（可用：" + String.join("/", this.optionSources.ids()) + "）");
		}
		List<Map<String, String>> options = this.optionSources.options(sourceId, parentValue);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("sourceId", sourceId);
		data.put("options", options == null ? List.of() : options);
		return ApiResponse.ok(data);
	}

	/** 上传前确认"这张表单确实在等用户"（跨轮次/跨表单的调用直接拒绝）。 */
	private void assertFormIsWaiting(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST, "runId 与 toolCallId 均为必填");
		}
		PendingToolCall pending = this.runStore.pending(runId, toolCallId);
		if (pending == null || !GenerativeFormRules.TOOL_NAME.equals(pending.getName())
				|| !PendingToolCall.PENDING.equals(pending.getStatus())) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"该表单调用不在等待填写状态（无法上传附件），请重新发起表单");
		}
	}

	private static String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase();
	}

}
