package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附件登记簿的 Redis 实现（{@code ai:form:att:<fileId>}，TTL 见 {@link #TTL}）。
 *
 * <p>降级口径与 AI 模块其他 Redis 设施一致：{@code StringRedisTemplate} 不可用时退回进程内 Map
 * （单实例/测试路径），日志注明降级；<b>不</b>因此拒绝上传 —— 附件是表单体验的一部分，
 * 不是安全边界本身（安全边界由回灌时的存在/归属/一次性三重校验承担）。
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
			log.warn("附件登记写 Redis 失败，降级进程内登记 fileId={}：{}", fileId, ex.getMessage());
			this.memory.put(fileId, attachment);
		}
		return attachment;
	}

	@Override
	public Attachment find(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return null;
		}
		StringRedisTemplate redis = redis();
		if (redis != null) {
			try {
				String json = redis.opsForValue().get(KEY_PREFIX + fileId);
				if (json != null) {
					return this.objectMapper.readValue(json, Attachment.class);
				}
			}
			catch (Exception ex) {
				log.warn("附件读取 Redis 失败，回落进程内登记 fileId={}：{}", fileId, ex.getMessage());
			}
		}
		return this.memory.get(fileId);
	}

	@Override
	public boolean consume(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return false;
		}
		StringRedisTemplate redis = redis();
		if (redis != null) {
			try {
				Boolean deleted = redis.delete(KEY_PREFIX + fileId);
				if (Boolean.TRUE.equals(deleted)) {
					redis.opsForValue().set(CONSUMED_PREFIX + fileId, "1", TTL);
					this.memory.remove(fileId);
					return true;
				}
			}
			catch (Exception ex) {
				log.warn("附件消费写 Redis 失败，回落进程内登记 fileId={}：{}", fileId, ex.getMessage());
			}
		}
		if (this.memory.remove(fileId) != null) {
			this.consumed.add(fileId);
			return true;
		}
		return false;
	}

	@Override
	public boolean wasConsumed(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return false;
		}
		StringRedisTemplate redis = redis();
		if (redis != null) {
			try {
				if (Boolean.TRUE.equals(redis.hasKey(CONSUMED_PREFIX + fileId))) {
					return true;
				}
			}
			catch (Exception ex) {
				log.warn("附件墓碑读取 Redis 失败 fileId={}：{}", fileId, ex.getMessage());
			}
		}
		return this.consumed.contains(fileId);
	}

	/** 当前可用数据源（Redis 不可用返回 null）。 */
	private StringRedisTemplate redis() {
		try {
			return this.redisProvider.getIfAvailable();
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** 进程内登记簿快照（排障/用例用，只读）。 */
	public Map<String, Attachment> snapshot() {
		return new LinkedHashMap<>(this.memory);
	}

}
