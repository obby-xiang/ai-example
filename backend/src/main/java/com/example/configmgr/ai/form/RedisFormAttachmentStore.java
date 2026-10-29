package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附件登记簿的 Redis 实现（{@code ai:form:att:<fileId>}，TTL 见 {@link #ttl()}）。
 *
 * <p><b>B3 fail-closed</b>：只要 {@code StringRedisTemplate} 已装配，Redis 就是<b>唯一事实源</b> ——
 * 登记写失败直接告知上传失败，<b>不再</b>静默回落进程内 Map。原因：多实例下上传在 A、回灌在 B，
 * 进程内登记会让 B 看不到 A 的附件，表现为"附件不存在/未消费"判定失真；那不是性能降级，是数据面丢失。
 * 只有模板本身未装配（单测/无 Redis 的轻装配面）才使用进程内 Map。
 *
 * <p><b>与 ui_request_upload 的关系</b>：那条通道是"用浏览器手势把句柄交给网页"的既有交接卡，
 * 与本登记簿并存不冲突 —— 一个走 {@code frontend_tool_request} 挂起，一个走表单字段值；
 * 两者都不把文件内容送进模型上下文。
 */
@Slf4j
@Component
public class RedisFormAttachmentStore implements FormAttachmentStore {

	/**
	 * 登记有效期的兜底值：与 {@link AiProperties#getSessionTtl()} 的默认值（24h）一致。
	 *
	 * <p>为什么必须与会话/待决条目<b>同一把尺</b>：{@code generative_form} 无等待上限，待决条目的 TTL
	 * 是 {@code app.ai.session-ttl}（挂起期随心跳续期）。旧实现把登记写死 2h —— 等超过 2h 的表单
	 * 必然拿到 {@code ATTACHMENT_NOT_FOUND}，且失效文件没人回收（孤儿）。
	 * 现在登记/墓碑/两个索引同取一个旋钮；残留边界见类注释与
	 * {@code docs/research/修复-AI表单附件与决策竞态-2026-10-29.md}。
	 */
	static final Duration DEFAULT_TTL = Duration.ofHours(24);

	private static final String KEY_PREFIX = "ai:form:att:";

	/** 已消费墓碑（只用于把"用过了"与"不存在"分开；TTL 与登记同量级）。 */
	private static final String CONSUMED_PREFIX = "ai:form:att:used:";

	/**
	 * 会话 → 附件登记 id 索引（{@code ai:form:att:sess:<sessionId>}，SET，TTL 与登记同量级）。
	 *
	 * <p>为什么必须单独建索引：会话销毁要"只按登记删文件"，就得先枚举该会话的登记；
	 * 而登记本体是按 {@code fileId} 存的单键，没有索引就只能扫全键空间（会误伤/拖垮生产）。
	 * 索引成员只是 fileId，值里没有文件内容；消费/销毁时同步 SREM，空 SET 由 Redis 自动消失。
	 */
	static final String SESSION_INDEX_PREFIX = "ai:form:att:sess:";

	/**
	 * 表单 → 附件登记 id 索引（{@code ai:form:att:form:<runId>:<toolCallId>}，SET，TTL 与登记同量级）。
	 *
	 * <p>两个用途：<b>①上传限额</b>（在册件数 ≤ 表单的 attachment 字段数，每个字段只装一个 fileId）；
	 * <b>②表单收口回收</b>（决策接受 / 用户放弃后，把"传了但没提交"的登记与落盘文件一并删掉 ——
	 * 否则它们只能等 TTL，文件成孤儿）。
	 */
	static final String FORM_INDEX_PREFIX = "ai:form:att:form:";

	private final ObjectProvider<StringRedisTemplate> redisProvider;

	private final ObjectMapper objectMapper;

	/** 登记/墓碑/索引的有效期（与 {@code app.ai.session-ttl} 同源）。 */
	private final Duration ttl;

	/** 降级/测试路径的进程内登记簿（Redis 不可用时唯一事实源）。 */
	private final Map<String, Attachment> memory = new ConcurrentHashMap<>();

	/** 进程内已消费墓碑（与 Redis 的 {@link #CONSUMED_PREFIX} 同义）。 */
	private final java.util.Set<String> consumed = ConcurrentHashMap.newKeySet();

	public RedisFormAttachmentStore(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
		this(redisProvider, objectMapper, new AiProperties());
	}

	@org.springframework.beans.factory.annotation.Autowired
	public RedisFormAttachmentStore(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper,
			AiProperties properties) {
		this.redisProvider = redisProvider;
		this.objectMapper = objectMapper;
		Duration configured = properties == null ? null : properties.getSessionTtl();
		this.ttl = configured == null || configured.isZero() || configured.isNegative() ? DEFAULT_TTL : configured;
	}

	/** 登记寿命（排障/用例断言用：必须与 {@code app.ai.session-ttl} 同源）。 */
	Duration ttl() {
		return this.ttl;
	}

	@Override
	public Attachment register(String runId, String toolCallId, String sessionId, String fileName, long size,
			String contentType, String storagePath) {
		String fileId = "att_" + UUID.randomUUID().toString().replace("-", "");
		Attachment attachment = new Attachment(fileId, runId, toolCallId, sessionId, fileName, size, contentType,
				storagePath);
		StringRedisTemplate redis = redis();
		if (redis == null) {
			this.memory.put(fileId, attachment);
			return attachment;
		}
		try {
			redis.opsForValue().set(KEY_PREFIX + fileId, this.objectMapper.writeValueAsString(attachment), this.ttl);
		}
		catch (Exception ex) {
			// B3：多实例下登记失败必须 fail-closed —— 回落进程内 Map 会让"上传在 A、回灌在 B"时
			// B 看不到登记（判定失真），且日志会把数据面丢失伪装成性能降级。
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_REGISTER, ex);
			log.error("附件登记写 Redis 失败 fileId={}（多实例下 fail-closed，不回落进程内）：{}", fileId,
					ex.getMessage());
			throw new AiStateUnavailableException("附件登记失败（Redis 不可用；不得回退进程内）", ex);
		}
		// 两个索引都是"删文件"的枚举面：会话索引给会话销毁用，表单索引给表单收口回收与上传限额用。
		// 任一索引失败都必须回滚登记（否则销毁/收口时看不见这条登记，文件成孤儿）：先删数据键再抛出，
		// 调用方（上传端点）据此删除已落盘文件。
		try {
			addToIndex(redis, sessionIndexKey(sessionId), fileId);
			addToIndex(redis, formIndexKey(runId, toolCallId), fileId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_REGISTER, ex);
			log.error("附件登记已写入但索引失败 fileId={} sessionId={} runId={} toolCallId={}"
					+ "（回滚登记，避免销毁/收口时漏删文件）：{}", fileId, sessionId, runId, toolCallId,
					ex.getMessage());
			try {
				redis.delete(KEY_PREFIX + fileId);
				removeFromIndexQuietly(redis, sessionIndexKey(sessionId), fileId);
				removeFromIndexQuietly(redis, formIndexKey(runId, toolCallId), fileId);
			}
			catch (Exception cleanup) {
				log.warn("回滚附件登记失败 fileId={}（登记会在 TTL 后自清）：{}", fileId, cleanup.getMessage());
			}
			throw new AiStateUnavailableException("附件登记失败（索引不可写；不得留下不可枚举的登记）", ex);
		}
		return attachment;
	}

	@Override
	public Attachment find(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return null;
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.get(fileId);
		}
		try {
			String json = redis.opsForValue().get(KEY_PREFIX + fileId);
			return json == null ? null : this.objectMapper.readValue(json, Attachment.class);
		}
		catch (Exception ex) {
			// 读不出来 ≠ 不存在：多实例下必须让调用方知道无法校验，而不是回落本地 Map 误判。
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			log.error("附件读取 Redis 失败 fileId={}（fail-closed，不回落进程内）：{}", fileId, ex.getMessage());
			throw new AiStateUnavailableException("附件读取失败（Redis 不可用；不得回退进程内）", ex);
		}
	}

	@Override
	public boolean consume(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return false;
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			if (this.memory.get(fileId) == null) {
				return false;
			}
			// 进程内路径同为"墓碑先于删除"：先置已消费，再摘登记
			this.consumed.add(fileId);
			this.memory.remove(fileId);
			return true;
		}
		try {
			Attachment attachment = readRegistrationOrNull(redis, fileId);
			if (attachment == null) {
				// 不存在/已过期：没有可消费的登记，也不该留墓碑（否则"从没上传过"会被报成"已消费"）
				return false;
			}
			// I5：墓碑（WAL）<b>先写</b>，数据键<b>后删</b>。
			// 反过来（先删数据键）时，墓碑写失败会留下最坏组合：登记已删 + 无墓碑 + 落盘文件成孤儿
			// （谁也枚举不到它）。现在墓碑写不进去就直接抛，数据键原样保留 —— 用户还能重试。
			redis.opsForValue().set(CONSUMED_PREFIX + fileId, "1", this.ttl);
			Boolean deleted = redis.delete(KEY_PREFIX + fileId);
			if (Boolean.TRUE.equals(deleted)) {
				this.memory.remove(fileId);
				removeFromIndexQuietly(redis, sessionIndexKey(attachment.sessionId()), fileId);
				removeFromIndexQuietly(redis,
						formIndexKey(attachment.runId(), attachment.toolCallId()), fileId);
				return true;
			}
			// 墓碑已写但数据键已被并发消费/过期：不是本次消费成功（返回 false 由调用方告警）
			return false;
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_CONSUME, ex);
			log.error("附件消费写 Redis 失败 fileId={}（fail-closed，不回落进程内）：{}", fileId, ex.getMessage());
			throw new AiStateUnavailableException("附件消费失败（Redis 不可用；不得回退进程内）", ex);
		}
	}

	/**
	 * 该会话仍在册的附件（销毁枚举面）。
	 *
	 * <p>索引成员可能已过期/被并发消费（stale），逐个 {@link #find(String)} 过滤；读失败
	 * <b>抛出</b>（销毁要求"枚举失败不半删"），不折成空表。索引里混入别的会话 id 时按登记
	 * 自身的 {@code sessionId} 再判一次，避免索引污染导致跨会话删文件。
	 */
	@Override
	public List<Attachment> findBySessionId(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return List.of();
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.values().stream().filter(item -> sessionId.equals(item.sessionId())).toList();
		}
		SetOperations<String, String> setOps = redis.opsForSet();
		if (setOps == null) {
			return List.of();
		}
		Set<String> ids;
		try {
			ids = setOps.members(sessionIndexKey(sessionId));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			throw new AiStateUnavailableException("附件会话索引读取失败（销毁枚举不得按空表处理）", ex);
		}
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		List<Attachment> out = new ArrayList<>();
		for (String fileId : ids) {
			Attachment attachment = find(fileId);
			if (attachment != null && sessionId.equals(attachment.sessionId())) {
				out.add(attachment);
			}
		}
		return out;
	}

	/**
	 * 续期该表单名下的登记、表单索引与会话索引（挂起续期链的一次 touch）。
	 *
	 * <p>逐件隔离：单个键失败只告警（{@link RedisDegradation#B3_ATTACHMENT_READ} 计数），
	 * 不让确认门心跳因一个键失败而中断。索引读不出来同样只告警 —— 下一次心跳还会再试。
	 */
	@Override
	public void touch(String runId, String toolCallId) {
		String indexKey = formIndexKey(runId, toolCallId);
		if (indexKey == null) {
			return;
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return;
		}
		Set<String> ids;
		try {
			SetOperations<String, String> setOps = redis.opsForSet();
			if (setOps == null) {
				return;
			}
			ids = setOps.members(indexKey);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			log.warn("附件续期读表单索引失败 runId={} toolCallId={}（下一拍重试）：{}", runId, toolCallId,
					ex.getMessage());
			return;
		}
		if (ids == null || ids.isEmpty()) {
			return;
		}
		for (String fileId : ids) {
			try {
				redis.expire(KEY_PREFIX + fileId, this.ttl);
				Attachment attachment = readRegistrationOrNull(redis, fileId);
				if (attachment != null) {
					redis.expire(sessionIndexKey(attachment.sessionId()), this.ttl);
				}
			}
			catch (Exception ex) {
				log.warn("附件续期失败 fileId={} runId={} toolCallId={}（下一拍重试）：{}", fileId, runId,
						toolCallId, ex.getMessage());
			}
		}
		try {
			redis.expire(indexKey, this.ttl);
		}
		catch (Exception ex) {
			log.warn("附件续期写表单索引失败 runId={} toolCallId={}（下一拍重试）：{}", runId, toolCallId,
					ex.getMessage());
		}
	}

	/**
	 * 该表单仍在册的附件（上传限额面 + 表单收口回收面）。
	 *
	 * <p>索引成员可能是 stale（登记已过期/已被并发消费）：逐个 {@link #find(String)} 过滤，并按登记
	 * 自身的 {@code runId/toolCallId} 再判一次，避免索引污染导致回收别人的件。
	 * 读失败<b>抛出</b>（上传限额不得按空表处理成"一件都没有"）。
	 */
	@Override
	public List<Attachment> findByForm(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			return List.of();
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.values()
				.stream()
				.filter(item -> runId.equals(item.runId()) && toolCallId.equals(item.toolCallId()))
				.toList();
		}
		SetOperations<String, String> setOps = redis.opsForSet();
		if (setOps == null) {
			return List.of();
		}
		Set<String> ids;
		try {
			ids = setOps.members(formIndexKey(runId, toolCallId));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			throw new AiStateUnavailableException("附件表单索引读取失败（上传限额/收口回收不得按空表处理）", ex);
		}
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		List<Attachment> out = new ArrayList<>();
		for (String fileId : ids) {
			Attachment attachment = find(fileId);
			if (attachment != null && runId.equals(attachment.runId())
					&& toolCallId.equals(attachment.toolCallId())) {
				out.add(attachment);
			}
		}
		return out;
	}

	/**
	 * 删除一条登记（会话销毁 / 表单收口回收用）：数据键 + 墓碑键 + 两个索引成员。
	 *
	 * <p>返回被删登记（含 {@code storagePath}），调用方据此删文件；不存在返回 null（幂等）。
	 * 数据键删除失败抛出（由销毁流程记为部分失败，可重试）；索引清理失败只 WARN
	 * （登记已删，stale 成员会被枚举时的 {@link #find(String)} 过滤掉）。
	 */
	@Override
	public Attachment deleteRegistration(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return null;
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.remove(fileId);
		}
		Attachment attachment = find(fileId);
		try {
			redis.delete(KEY_PREFIX + fileId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_CONSUME, ex);
			throw new AiStateUnavailableException("附件登记删除失败（Redis 不可用；可重试）", ex);
		}
		try {
			redis.delete(CONSUMED_PREFIX + fileId);
		}
		catch (Exception ex) {
			log.warn("清理附件消费墓碑失败 fileId={}（墓碑会在 TTL 后自清）：{}", fileId, ex.getMessage());
		}
		removeFromIndexQuietly(redis, sessionIndexKey(attachment == null ? null : attachment.sessionId()), fileId);
		removeFromIndexQuietly(redis, formIndexKey(attachment == null ? null : attachment.runId(),
				attachment == null ? null : attachment.toolCallId()), fileId);
		return attachment;
	}

	/** 索引登记（失败抛出；调用方回滚登记）。key 为空 = 无法归属，跳过（不阻断上传）。 */
	private void addToIndex(StringRedisTemplate redis, String indexKey, String fileId) {
		if (indexKey == null) {
			return;
		}
		SetOperations<String, String> setOps = redis.opsForSet();
		if (setOps == null) {
			return;
		}
		setOps.add(indexKey, fileId);
		redis.expire(indexKey, this.ttl);
	}

	private static String sessionIndexKey(String sessionId) {
		return sessionId == null || sessionId.isBlank() ? null : SESSION_INDEX_PREFIX + sessionId;
	}

	private static String formIndexKey(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			return null;
		}
		return FORM_INDEX_PREFIX + runId + ":" + toolCallId;
	}

	/** 索引清理（best-effort：登记已删时 stale 成员不再指向任何文件）。 */
	private static void removeFromIndexQuietly(StringRedisTemplate redis, String indexKey, String fileId) {
		if (indexKey == null) {
			return;
		}
		try {
			SetOperations<String, String> setOps = redis.opsForSet();
			if (setOps != null) {
				setOps.remove(indexKey, fileId);
			}
		}
		catch (Exception ex) {
			log.warn("附件索引清理失败 key={} fileId={}（stale 成员会被枚举过滤）：{}", indexKey, fileId,
					ex.getMessage());
		}
	}

	/**
	 * 消费/touch 路径的登记读取（不存在/坏数据返回 null，基础设施故障抛出）。
	 *
	 * <p><b>只有"坏数据"才折成 null</b>（JSON 解析不了 ⇒ 按无登记处理，等同过期）；
	 * {@code Redis} 读失败<b>必须抛出</b>（{@link AiStateUnavailableException}）—— 把它吞成
	 * "登记不存在"会让消费返回 false，日志与上层把它报成"已被消费/已过期"，真正的原因（基础设施故障）
	 * 反而消失，用户看到的是"附件过期，请重新上传"。
	 */
	private Attachment readRegistrationOrNull(StringRedisTemplate redis, String fileId) {
		ValueOperations<String, String> values = redis.opsForValue();
		if (values == null) {
			return null;
		}
		String json;
		try {
			json = values.get(KEY_PREFIX + fileId);
		}
		catch (RuntimeException ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			throw new AiStateUnavailableException("附件读取失败（Redis 不可用；不得按无登记处理）", ex);
		}
		if (json == null) {
			return null;
		}
		try {
			return this.objectMapper.readValue(json, Attachment.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			log.warn("附件登记数据无法解析（按无登记处理）fileId={}：{}", fileId, ex.getMessage());
			return null;
		}
	}

	@Override
	public boolean wasConsumed(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return false;
		}
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.consumed.contains(fileId);
		}
		try {
			return Boolean.TRUE.equals(redis.hasKey(CONSUMED_PREFIX + fileId));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_TOMBSTONE, ex);
			log.error("附件墓碑读取 Redis 失败 fileId={}（fail-closed，不回落进程内）：{}", fileId, ex.getMessage());
			throw new AiStateUnavailableException("附件墓碑读取失败（Redis 不可用；不得回退进程内）", ex);
		}
	}

	/** 当前可用数据源（模板未装配返回 null；获取模板本身失败按 fail-closed 抛错）。 */
	private StringRedisTemplate redis() {
		try {
			return this.redisProvider.getIfAvailable();
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("无法获取附件登记簿的 Redis 模板", ex);
		}
	}

	/** 进程内登记簿快照（排障/用例用，只读）。 */
	public Map<String, Attachment> snapshot() {
		return new LinkedHashMap<>(this.memory);
	}

}
