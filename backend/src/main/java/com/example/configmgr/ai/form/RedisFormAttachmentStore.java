package com.example.configmgr.ai.form;

import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 附件登记簿的 Redis 实现（{@code ai:form:att:<fileId>}，TTL 见 {@link #TTL}）。
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

	/** 登记有效期：足够用户填完一张表，又不至于让未消费的临时件长期滞留。 */
	static final Duration TTL = Duration.ofHours(2);

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

	private final ObjectProvider<StringRedisTemplate> redisProvider;

	private final ObjectMapper objectMapper;

	/** 降级/测试路径的进程内登记簿（Redis 不可用时唯一事实源）。 */
	private final Map<String, Attachment> memory = new ConcurrentHashMap<>();

	/** 进程内已消费墓碑（与 Redis 的 {@link #CONSUMED_PREFIX} 同义）。 */
	private final java.util.Set<String> consumed = ConcurrentHashMap.newKeySet();

	public RedisFormAttachmentStore(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
		this.redisProvider = redisProvider;
		this.objectMapper = objectMapper;
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
			redis.opsForValue().set(KEY_PREFIX + fileId, this.objectMapper.writeValueAsString(attachment), TTL);
		}
		catch (Exception ex) {
			// B3：多实例下登记失败必须 fail-closed —— 回落进程内 Map 会让"上传在 A、回灌在 B"时
			// B 看不到登记（判定失真），且日志会把数据面丢失伪装成性能降级。
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_REGISTER, ex);
			log.error("附件登记写 Redis 失败 fileId={}（多实例下 fail-closed，不回落进程内）：{}", fileId,
					ex.getMessage());
			throw new AiStateUnavailableException("附件登记失败（Redis 不可用；不得回退进程内）", ex);
		}
		// 会话销毁的枚举面：登记成功后把它挂进该会话的索引。
		// 索引失败必须回滚登记（否则销毁时看不到这条登记，文件会成孤儿）：先删数据键再抛出，
		// 调用方（上传端点）据此删除已落盘文件。
		try {
			addToSessionIndex(redis, sessionId, fileId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_REGISTER, ex);
			log.error("附件登记已写入但会话索引失败 fileId={} sessionId={}（回滚登记，避免销毁时漏删文件）：{}",
					fileId, sessionId, ex.getMessage());
			try {
				redis.delete(KEY_PREFIX + fileId);
			}
			catch (Exception cleanup) {
				log.warn("回滚附件登记失败 fileId={}（登记会在 TTL 后自清）：{}", fileId, cleanup.getMessage());
			}
			throw new AiStateUnavailableException("附件登记失败（会话索引不可写；不得留下不可枚举的登记）", ex);
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
			if (this.memory.remove(fileId) != null) {
				this.consumed.add(fileId);
				return true;
			}
			return false;
		}
		try {
			String sessionId = sessionIdOfQuietly(redis, fileId);
			Boolean deleted = redis.delete(KEY_PREFIX + fileId);
			if (Boolean.TRUE.equals(deleted)) {
				redis.opsForValue().set(CONSUMED_PREFIX + fileId, "1", TTL);
				this.memory.remove(fileId);
				removeFromSessionIndexQuietly(redis, sessionId, fileId);
				return true;
			}
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
			ids = setOps.members(SESSION_INDEX_PREFIX + sessionId);
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
	 * 删除一条登记（销毁用）：数据键 + 墓碑键 + 会话索引成员。
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
		removeFromSessionIndexQuietly(redis, attachment == null ? null : attachment.sessionId(), fileId);
		return attachment;
	}

	/** 会话索引登记（失败抛出；调用方回滚登记）。sessionId 为空 = 无法归属，跳过（不阻断上传）。 */
	private static void addToSessionIndex(StringRedisTemplate redis, String sessionId, String fileId) {
		if (sessionId == null || sessionId.isBlank()) {
			return;
		}
		SetOperations<String, String> setOps = redis.opsForSet();
		if (setOps == null) {
			return;
		}
		setOps.add(SESSION_INDEX_PREFIX + sessionId, fileId);
		redis.expire(SESSION_INDEX_PREFIX + sessionId, TTL);
	}

	/** 会话索引清理（best-effort：登记已删时 stale 成员不再指向任何文件）。 */
	private static void removeFromSessionIndexQuietly(StringRedisTemplate redis, String sessionId, String fileId) {
		if (sessionId == null || sessionId.isBlank()) {
			return;
		}
		try {
			SetOperations<String, String> setOps = redis.opsForSet();
			if (setOps != null) {
				setOps.remove(SESSION_INDEX_PREFIX + sessionId, fileId);
			}
		}
		catch (Exception ex) {
			log.warn("附件会话索引清理失败 sessionId={} fileId={}（stale 成员会被枚举过滤）：{}", sessionId,
					fileId, ex.getMessage());
		}
	}

	/** 消费路径的会话归属读取：只用于清索引，读不出来不影响消费本身。 */
	private String sessionIdOfQuietly(StringRedisTemplate redis, String fileId) {
		try {
			if (redis.opsForValue() == null) {
				return null;
			}
			String json = redis.opsForValue().get(KEY_PREFIX + fileId);
			if (json == null) {
				return null;
			}
			Attachment attachment = this.objectMapper.readValue(json, Attachment.class);
			return attachment.sessionId();
		}
		catch (Exception ex) {
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
