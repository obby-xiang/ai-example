package com.example.configmgr.ai.form;

/**
 * 生成式表单 attachment 字段的<b>上传件登记簿</b>（G-E）。
 *
 * <h2>为什么值只能是 fileId</h2>
 * 附件字段的值是一个由固定上传接口签发的 {@code fileId}（形如 {@code att_xxxxxxxx}）：
 * 文件本体落在既有 {@link com.example.configmgr.file.FileStorageService} 的存储目录里，
 * 表单回灌只带 id —— <b>文件内容永不进模型上下文</b>，模型只看得到 fileName/fileId/size。
 *
 * <h2>三重校验（{@link GenerativeFormGuard} 在回灌时执行）</h2>
 * <ol>
 * <li><b>存在</b>：{@link #find(String)} 非 null；</li>
 * <li><b>归属</b>：登记时绑定的 {@code runId + toolCallId} 必须是本次挂起的表单
 * （比"同一会话"更严：文件只能被请求它的那张表单消费一次）；</li>
 * <li><b>一次性</b>：{@link #consume(String)} 原子地把状态从"可用"翻成"已消费"，
 * 二次回灌同一 id 一律 {@code ATTACHMENT_CONSUMED}。</li>
 * </ol>
 */
public interface FormAttachmentStore {

	/** 登记一次上传（返回的 {@code fileId} 即表单值）。 */
	Attachment register(String runId, String toolCallId, String sessionId, String fileName, long size,
			String contentType, String storagePath);

	/** 按 id 查登记信息（不存在 / 已过期返回 null）。 */
	Attachment find(String fileId);

	/** 一次性消费：成功（从"可用"翻为"已消费"）返回 true；不存在或已消费返回 false。 */
	boolean consume(String fileId);

	/**
	 * 是否<b>已被消费</b>（用于把"用过了"和"根本不存在"区分开：两者对用户是不同提示）。
	 * 实现可以只保留短期墓碑（TTL 与登记同量级）。
	 */
	boolean wasConsumed(String fileId);

	/**
	 * 一次上传的登记信息（只有元数据，<b>没有文件内容</b>）。
	 *
	 * @param fileId      签发给前端的 id（表单值）
	 * @param runId       所属轮次
	 * @param toolCallId  所属表单调用（归属校验）
	 * @param sessionId   所属页签会话（审计用）
	 * @param fileName    原始文件名（模型可见）
	 * @param size        字节数（模型可见）
	 * @param contentType MIME（模型可见）
	 * @param storagePath 服务器存储路径（<b>绝不下发前端/模型</b>）
	 */
	record Attachment(String fileId, String runId, String toolCallId, String sessionId, String fileName,
			long size, String contentType, String storagePath) {
	}

}
