package com.example.configmgr.ai.form;

import java.util.List;

/**
 * 生成式表单 attachment 字段的<b>上传件登记簿</b>（G-E）。
 *
 * <h2>为什么值只能是 fileId</h2>
 * 附件字段的值是一个由固定上传接口签发的 {@code fileId}（形如 {@code att_xxxxxxxx}）：
 * 文件本体落在既有 {@link com.example.configmgr.file.FileStorageService} 的存储目录里，
 * 表单回灌只带 id —— <b>文件内容永不进模型上下文</b>，模型只看得到 fileName/fileId/size。
 *
 * <h2>三段校验 + 一次消费（{@link GenerativeFormGuard}）</h2>
 * <ol>
 * <li><b>存在</b>：{@link #find(String)} 非 null（回灌<b>校验</b>阶段执行，只读）；</li>
 * <li><b>归属</b>：登记时绑定的 {@code runId + toolCallId} 必须是本次挂起的表单
 * （比"同一会话"更严：文件只能被请求它的那张表单消费一次）；</li>
 * <li><b>一次性</b>：{@link #consume(String)} 原子地把状态从"可用"翻成"已消费"，
 * 二次回灌同一 id 一律 {@code ATTACHMENT_CONSUMED}。</li>
 * </ol>
 *
 * <p><b>校验与消费分离（2026-10-29 修复批）</b>：前两段在回灌校验里只读执行；{@link #consume} 只在
 * <b>决策已落库之后</b>调用（{@code FrontendToolGuard#settleAcceptedResult}）—— 校验阶段的拒绝是
 * "可修正后重试"，在那里消费会让"重试"失去意义。
 * </p>
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
	 * 该会话仍在册的附件（会话销毁的枚举面；不存在/无 sessionId 归属的登记不返回）。
	 *
	 * <p>默认空表是给"只实现最小语义"的测试替身/轻量实现用的：它们没有会话索引，
	 * 销毁时自然看不到这些登记。生产实现 {@code RedisFormAttachmentStore} 维护
	 * {@code ai:form:att:sess:<sessionId>} 索引并覆盖本方法。<b>读失败必须抛出</b>
	 * （销毁流程要求"先枚举报废面，失败不半删"），不得折成空表。
	 */
	default List<Attachment> findBySessionId(String sessionId) {
		return List.of();
	}

	/**
	 * 续期一个表单名下所有附件登记（挂起续期链的一环）。
	 *
	 * <p>为什么必须有：登记 TTL 取 {@code app.ai.session-ttl}，而生成式表单<b>无等待上限</b> ——
	 * 只写一次的 TTL 必然在"等得久"的表单上先过期（卡还在、附件已 {@code ATTACHMENT_NOT_FOUND}，
	 * 失效文件还没人删）。待决条目在挂起期由心跳/读侧续期，附件登记必须跟着同一条链续，
	 * 否则两处寿命仍然对不齐。
	 *
	 * <p>调用时机：{@code ConfirmGate} 在挂起期续期（心跳）时，对该轮仍 {@code PENDING} 的条目逐个调用；
	 * 决策/放弃之后登记已被消费或回收，再 touch 不会把已删的键重新养出来（Redis {@code EXPIRE}
	 * 对不存在的键是无操作）。默认空实现 = 该实现没有 TTL 可续（进程内替身/轻量实现）。
	 *
	 * <p>实现应<b>逐件隔离失败</b>并只告警：续期是养活性维护，不该让确认门心跳因一个键失败而中断。
	 */
	default void touch(String runId, String toolCallId) {
	}

	/**
	 * 该表单（{@code runId + toolCallId}）名下仍在册的附件（表单收口回收面）。
	 *
	 * <p>用途有两处：<b>①</b>上传端点的"按表单限件数"（每个 attachment 字段只装一个 fileId，
	 * 在册数已达字段数就没有空位）；<b>②</b>表单收口（决策接受 / 用户放弃）后回收"传了但没提交"
	 * 的登记与落盘文件 —— 没有这个面，那些件只能等 TTL 过期，文件成孤儿。
	 *
	 * <p>默认空表是给"只实现最小语义"的测试替身/轻量实现用的；生产实现
	 * {@code RedisFormAttachmentStore} 维护 {@code ai:form:att:form:<runId>:<toolCallId>} 索引并覆盖。
	 * <b>读失败必须抛出</b>（调用方据此拒绝上传，不得按空表处理成"没有在册件"）。
	 */
	default List<Attachment> findByForm(String runId, String toolCallId) {
		return List.of();
	}

	/**
	 * 删除一条登记（会话销毁 / 表单收口回收用；返回被删的登记，包含删除文件所必需的
	 * {@code storagePath}）。
	 *
	 * <p>与 {@link #consume(String)} 的区别：不置"已消费"墓碑（销毁不是消费），
	 * 幂等（不存在返回 null）。默认实现没有撤销登记的能力，返回 null；生产实现覆盖。
	 */
	default Attachment deleteRegistration(String fileId) {
		return null;
	}

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
