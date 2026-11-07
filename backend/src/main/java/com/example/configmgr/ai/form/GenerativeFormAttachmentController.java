package com.example.configmgr.ai.form;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
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
 * 且回灌<b>决策落库之后</b>才一次性消费（{@code ATTACHMENT_CONSUMED}）。上传前再核一次
 * "该调用确实在等用户"，避免任意请求往登记簿里塞文件。
 *
 * <h2>上传预算（2026-10-29 修复批）</h2>
 * 件数上限 = 表单 schema 里的 attachment 字段数（每个字段只装一个 fileId），总量上限 =
 * {@link #MAX_TOTAL_ATTACHMENT_BYTES}；归属的 sessionId 只认轮次快照（不采信请求参数）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/form")
public class GenerativeFormAttachmentController {

	/** 单件上限（5MB）：够放截图/PDF 证据，又不至于把上传变成文件搬运通道。 */
	public static final long MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024;

	/**
	 * 单张表单的附件<b>总字节</b>上限（20MB）。
	 *
	 * <p>为什么单件上限不够：件数此前没有上限，5MB × 无限件 = 磁盘配额由调用方决定。件数上限
	 * （= 表单的 attachment 字段数）与总量上限一起，才把"一张表单能占多少盘"变成有界量。
	 */
	public static final long MAX_TOTAL_ATTACHMENT_BYTES = 20L * 1024 * 1024;

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

	private final ObjectMapper objectMapper;

	public GenerativeFormAttachmentController(FileStorageService fileStorage, FormAttachmentStore attachments,
			RunStore runStore, FormOptionSourceRegistry optionSources) {
		this(fileStorage, attachments, runStore, optionSources, new ObjectMapper());
	}

	@org.springframework.beans.factory.annotation.Autowired
	public GenerativeFormAttachmentController(FileStorageService fileStorage, FormAttachmentStore attachments,
			RunStore runStore, FormOptionSourceRegistry optionSources,
			ObjectMapper objectMapper) {
		this.fileStorage = fileStorage;
		this.attachments = attachments;
		this.runStore = runStore;
		this.optionSources = optionSources;
		this.objectMapper = objectMapper;
	}

	/**
	 * 上传一件附件（返回 fileId / fileName / size / contentType；不含存储路径）。
	 *
	 * <p><b>{@code sessionId} 请求参数不再采信</b>（2026-10-29 修复批）：它进的是"会话 → 附件"索引，
	 * 信客户端就等于允许任何调用方把文件挂到<b>别的会话</b>名下（那个会话销毁时会被连带删除）。
	 * 归属的权威值只有一个：本轮次快照的 {@code sessionId}（待决条目就挂在这一轮上）。
	 * 参数保留只为兼容旧前端（值被忽略）。
	 *
	 * <p><b>{@code fieldKey}</b>（可选）：本次上传要填的 attachment 字段。带了就必须是<b>这张表单</b>
	 * schema 里的 attachment 字段 —— 否则前端拿到的 fileId 永远填不进任何字段（也不会被提交消费，
	 * 只是占着盘）。不带时按"表单总附件位"限额。
	 */
	@PostMapping("/attachments")
	public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
			@RequestParam("runId") String runId,
			@RequestParam("toolCallId") String toolCallId,
			@RequestParam(value = "sessionId", required = false) String clientSessionId,
			@RequestParam(value = "fieldKey", required = false) String fieldKey) throws Exception {
		UploadValidation validation = validateUpload(file, runId, toolCallId, fieldKey);
		FormAttachmentStore.Attachment stored = storeUpload(file, validation, runId, toolCallId);
		supersedeUploads(validation.superseded(), runId, toolCallId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("fileId", stored.fileId());
		data.put("fileName", stored.fileName());
		data.put("size", Long.toString(stored.size()));
		data.put("contentType", stored.contentType());
		log.info("form attachment registered runId={} toolCallId={} fileId={} size={}", runId, toolCallId,
				stored.fileId(), stored.size());
		return ApiResponse.ok(data);
	}

	private UploadValidation validateUpload(MultipartFile file, String runId, String toolCallId, String fieldKey) {
		UploadType type = validateUploadType(file);
		return validateUploadCapacity(type, runId, toolCallId, fieldKey, file.getSize());
	}

	private UploadType validateUploadType(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST, "The attachment is empty; select a file again");
		}
		if (file.getSize() > MAX_ATTACHMENT_BYTES) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"Attachment is too large: " + file.getSize() + " bytes (limit " + MAX_ATTACHMENT_BYTES + " bytes)");
		}
		String originalName = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
		String ext = extensionOf(originalName);
		Set<String> mimes = ALLOWED.get(ext);
		if (mimes == null) {
			throw new CodedIllegalArgumentException(ErrorCodes.FILE_TYPE_UNSUPPORTED,
					"Unsupported attachment type: " + originalName + " (allowed "
							+ String.join("/", ALLOWED.keySet().stream().sorted().toList()) + ")");
		}
		String contentType = StringUtils.defaultString(file.getContentType());
		if (!contentType.isBlank() && !mimes.contains(contentType)) {
			throw new CodedIllegalArgumentException(ErrorCodes.FILE_TYPE_UNSUPPORTED,
					"Attachment MIME does not match the extension: " + contentType + " (." + ext + " allows " + String.join("/", mimes) + ")");
		}
		return new UploadType(originalName, contentType);
	}

	private UploadValidation validateUploadCapacity(UploadType type, String runId, String toolCallId, String fieldKey,
			long fileSize) {
		PendingToolCall pending = assertFormIsWaiting(runId, toolCallId);
		Set<String> attachmentFields = attachmentFieldKeys(runId, pending);
		if (attachmentFields.isEmpty()) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"This form has no attachment fields; start a new form before uploading");
		}
		// 目标字段先判：非法请求必须在任何"顶掉旧件/落盘"之前被挡下（否则一次错请求会删掉用户的文件）
		if (StringUtils.isNotBlank(fieldKey) && !attachmentFields.contains(fieldKey)) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"Field " + fieldKey + " is not an attachment field in this form and cannot receive uploads");
		}
		List<FormAttachmentStore.Attachment> existing = this.attachments.findByForm(runId, toolCallId);
		List<FormAttachmentStore.Attachment> superseded = List.of();
		if (existing.size() >= attachmentFields.size()) {
			// 单附件字段的表单：前端"重新选择文件"就是再传一次（没有删除接口），旧件的 fileId 已被
			// 新值覆盖 —— 顶掉旧登记是唯一能让用户改主意的做法，在册件数仍然 ≤ 1。
			// 多附件字段时无法判断"这次替换的是哪个字段"（猜错会删掉另一个字段正在用的件）：
			// 明确告诉用户可行动的做法（提交或取消当前表单后重新发起），不拿 fieldKey 当替换开关。
			if (attachmentFields.size() == 1) {
				superseded = List.copyOf(existing);
				existing = List.of();
			}
			else {
				throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
						"The attachment slots in this form are full: " + existing.size() + " registered files across " + attachmentFields.size()
								+ " attachment fields, one fileId per field. Submit the current form, "
								+ "or cancel and start a new one before replacing an attachment");
			}
		}
		long usedBytes = 0L;
		for (FormAttachmentStore.Attachment attachment : existing) {
			usedBytes += Math.max(0L, attachment.size());
		}
		if (usedBytes + fileSize > MAX_TOTAL_ATTACHMENT_BYTES) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"Total attachment size exceeds the per-form limit: registered " + usedBytes + " bytes + current " + fileSize + " bytes > "
							+ MAX_TOTAL_ATTACHMENT_BYTES + " bytes");
		}
		return new UploadValidation(type.originalName(), type.contentType(), superseded);
	}

	private record UploadType(String originalName, String contentType) {
	}

	private FormAttachmentStore.Attachment storeUpload(MultipartFile file, UploadValidation validation, String runId,
			String toolCallId) throws Exception {
		String authoritativeSessionId = authoritativeSessionId(runId);
		StoredFile storedFile;
		// MultipartFile 的输入流由容器分配：不关会把连接/临时文件留到 GC（上传口是高频入口）
		try (InputStream in = file.getInputStream()) {
			storedFile = this.fileStorage.store(in, validation.originalName());
		}
		try {
			return this.attachments.register(runId, toolCallId, authoritativeSessionId,
					validation.originalName(), file.getSize(), validation.contentType(), storedFile.key());
		}
		catch (RuntimeException ex) {
			// I-5：登记失败（B3 fail-closed）时已落盘文件必须回收，不能留下孤儿。
			deleteQuietly(storedFile.key());
			log.warn("attachment registration failed; stored file cleaned up runId={} toolCallId={} key={}", runId, toolCallId,
					storedFile.key(), ex);
			throw ex;
		}
	}

	private void supersedeUploads(List<FormAttachmentStore.Attachment> superseded, String runId, String toolCallId) {
		// 新件登记成功之后才回收被顶掉的旧件：先删旧的再存新的会把"换一张"变成"两张都没了"
		// （存档/磁盘失败时旧证据已不可恢复）。这里失败只告警：旧件仍可用，由表单收口或 TTL 兜底。
		for (FormAttachmentStore.Attachment old : superseded) {
			try {
				FormAttachmentStore.Attachment removed = this.attachments.deleteRegistration(old.fileId());
				if (removed != null) {
					deleteQuietly(removed.storagePath());
					log.info("single-attachment form re-uploaded; old file superseded runId={} toolCallId={} fileId={}", runId, toolCallId,
							removed.fileId());
				}
			}
			catch (RuntimeException ex) {
				log.warn("could not supersede old attachment (new file registered; old file kept until TTL) runId={} toolCallId={} fileId={}", runId,
						toolCallId, old.fileId(), ex);
			}
		}
	}

	private record UploadValidation(String originalName, String contentType,
			List<FormAttachmentStore.Attachment> superseded) {
	}

	/** 兼容重载：旧调用方不带目标字段（限额仍按表单总附件位）。 */
	public ApiResponse<Map<String, Object>> upload(MultipartFile file, String runId, String toolCallId,
			String clientSessionId) throws Exception {
		return upload(file, runId, toolCallId, clientSessionId, null);
	}

	/** 解析选项数据源（只认注册表里的 id）。 */
	@GetMapping("/option-sources/{sourceId}")
	public ApiResponse<Map<String, Object>> options(@PathVariable String sourceId,
			@RequestParam("value") String parentValue) {
		if (!this.optionSources.supports(sourceId)) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"Unregistered option source: " + sourceId + " (available: " + String.join("/", this.optionSources.ids()) + ")");
		}
		List<Map<String, String>> options = this.optionSources.options(sourceId, parentValue);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("sourceId", sourceId);
		data.put("options", ObjectUtils.defaultIfNull(options, List.of()));
		return ApiResponse.ok(data);
	}

	/**
	 * 归属的权威会话：本轮次快照的 {@code sessionId}（待决条目属于这一轮）。
	 *
	 * <p>读不到（快照缺失/读取失败）就<b>拒绝上传</b>（fail-closed）：没有 sessionId 的登记不进会话索引，
	 * 会话销毁时看不见它 ⇒ 文件成孤儿。同理绝不回落到请求参数 —— 那等于把"文件归属谁"交给调用方。
	 */
	private String authoritativeSessionId(String runId) {
		RunSnapshot snapshot;
		try {
			snapshot = this.runStore.get(runId);
		}
		catch (RuntimeException ex) {
			log.warn("could not read sessionId from run snapshot (fail-closed; request parameter not trusted) runId={}", runId, ex);
			throw new AiStateUnavailableException("Attachment upload is temporarily unavailable because the current session ownership cannot be read; retry later", ex);
		}
		String sessionId = snapshot == null ? null : snapshot.getSessionId();
		if (StringUtils.isBlank(sessionId)) {
			log.warn("run snapshot has no sessionId (fail-closed: unowned registrations cannot be reclaimed on session destroy) runId={}", runId);
			throw new AiStateUnavailableException("Attachment upload is temporarily unavailable because the current run has no session ownership; retry later");
		}
		return sessionId;
	}

	/** 删盘（空路径/<b>历史登记</b>的空 storagePath 安全跳过；失败只告警）。 */
	private void deleteQuietly(String storageKey) {
		if (StringUtils.isBlank(storageKey)) {
			return;
		}
		try {
			this.fileStorage.delete(storageKey);
		}
		catch (RuntimeException ex) {
			log.warn("could not delete stored file key={} (registration TTL / session destroy will clean up)", storageKey, ex);
		}
	}

	/**
	 * 表单 schema 里的 attachment 字段 key 全集（上传预算的判据来源）。
	 *
	 * <p>schema 已在下发前过了白名单（{@code GenerativeFormRules}），这里只做形状读取：
	 * 解析失败/没有 form/没有 fields 一律返回空集 —— 调用方按"没有附件字段"拒绝上传。
	 */
	private Set<String> attachmentFieldKeys(String runId, PendingToolCall pending) {
		Set<String> keys = new java.util.LinkedHashSet<>();
		String arguments = pending == null ? null : pending.getArguments();
		if (StringUtils.isBlank(arguments)) {
			return keys;
		}
		try {
			JsonNode root = this.objectMapper.readTree(arguments);
			JsonNode form = root == null ? null : root.get("form");
			if (form == null || !form.isObject()) {
				return keys;
			}
			for (JsonNode field : form.path("fields")) {
				if ("attachment".equals(field.path("type").asText())) {
					String key = field.path("key").asText("");
					if (!key.isEmpty()) {
						keys.add(key);
					}
				}
			}
		}
		catch (Exception ex) {
			// 不打印入参原文（schema 可能带用户填的默认值/长文本）：只记入参形状，异常仅用于服务端诊断
			log.warn("form arguments are not parseable; upload rejected as having no attachment field runId={} toolCallId={} chars={}", runId, pending.getToolCallId(), arguments.length(), ex);
		}
		return keys;
	}

	/** 上传前确认"这张表单确实在等用户"（跨轮次/跨表单的调用直接拒绝）。 */
	private PendingToolCall assertFormIsWaiting(String runId, String toolCallId) {
		if (StringUtils.isBlank(runId) || StringUtils.isBlank(toolCallId)) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST, "runId and toolCallId are required");
		}
		PendingToolCall pending = this.runStore.pending(runId, toolCallId);
		if (pending == null || !GenerativeFormRules.TOOL_NAME.equals(pending.getName())
				|| !PendingToolCall.PENDING.equals(pending.getStatus())) {
			throw new CodedIllegalArgumentException(ErrorCodes.BAD_REQUEST,
					"This form call is not waiting for input and cannot receive attachments; start a new form");
		}
		return pending;
	}

	private static String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase();
	}

}
