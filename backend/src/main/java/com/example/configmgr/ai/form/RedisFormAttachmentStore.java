package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *
 * <h2>身份隔离（2026-10-30）</h2>
 * 四族键全部带身份段（{@link TenantKeyScope}）：
 * <ul>
 * <li>{@code ai:form:att:<t>:<u>:<fileId>}（登记）与 {@code ai:form:att:used:<t>:<u>:<fileId>}（墓碑）；</li>
 * <li>{@code ai:form:att:sess:<t>:<u>:<sessionId>}（会话索引，销毁枚举面）；</li>
 * <li>{@code ai:form:att:form:<t>:<u>:<runId>:<toolCallId>}（表单索引，限额/收口回收面）。</li>
 * </ul>
 * 同一个 fileId 在别的身份下<b>根本不存在</b>：查不到 ⇒ 不能消费/不能删登记/不能删文件
 * （"附件归属与文件删除不能跨身份"由键形本身保证，而不是靠调用方自觉）。
 * 身份缺失时拼键直接失败：不存在不含身份段的旧键形，也不回落到任何无身份命名空间。
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

	/** 表单 → 附件登记 id 索引（{@code ai:form:att:form:<runId>:<toolCallId>}，SET，TTL 与登记同量级）。
	 *
	 * <p>两个用途：<b>①上传限额</b>（在册件数 ≤ 表单的 attachment 字段数，每个字段只装一个 fileId）；
	 * <b>②表单收口回收</b>（决策接受 / 用户放弃后，把"传了但没提交"的登记与落盘文件一并删掉 ——
	 * 否则它们只能等 TTL，文件成孤儿）。
	 */
	static final String FORM_INDEX_PREFIX = "ai:form:att:form:";

	/** 索引登记脚本标记（测试替身据此分派；不要改动与脚本正文不一致）。 */
	static final String INDEX_ADD_MARK = "ATTACHMENT_INDEX_ADD";

	/**
	 * 索引的<b>单键原子登记</b>：{@code SADD} 与 {@code PEXPIRE} 同一个脚本步。
	 *
	 * <p>为什么必须原子：两个索引都是"删文件"的枚举面。拆成 {@code SADD → EXPIRE} 时，进程死在
	 * 两步之间会留下<b>无 TTL 的索引</b> —— 成员永远指向一条早已过期的登记（stale 成员随会话数
	 * 无界增长，且键永不消失）。脚本只碰一个键（索引键不带 hash tag ⇒ 同槽），Cluster 下不引入
	 * {@code CROSSSLOT}。
	 *
	 * @param KEYS[1] 索引键
	 * @param ARGV[1] fileId / {@code ARGV[2]} TTL（毫秒）
	 */
	private static final RedisScript<Long> INDEX_ADD_SCRIPT = new DefaultRedisScript<>("""
			-- ATTACHMENT_INDEX_ADD
			redis.call('SADD', KEYS[1], ARGV[1])
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	private final ObjectProvider<StringRedisTemplate> redisProvider;

	private final ObjectMapper objectMapper;

	/** 登记/墓碑/索引的有效期（与 {@code app.ai.session-ttl} 同源）。 */
	private final Duration ttl;

	/** 降级/测试路径的进程内登记簿（Redis 不可用时唯一事实源）。 */
	private final Map<String, Attachment> memory = new ConcurrentHashMap<>();

	/** 进程内已消费墓碑（与 Redis 的 {@link #CONSUMED_PREFIX} 同义）。 */
	private final Set<String> consumed = ConcurrentHashMap.newKeySet();

	public RedisFormAttachmentStore(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
		this(redisProvider, objectMapper, new AiProperties());
	}

	@Autowired
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
		TenantUser identity = RequestContextUtils.require();
		String registration = registrationKey(identity, fileId);
		StringRedisTemplate redis = redis();
		if (redis == null) {
			this.memory.put(registration, attachment);
			return attachment;
		}
		try {
			redis.opsForValue().set(registration, this.objectMapper.writeValueAsString(attachment), this.ttl);
		}
		catch (Exception ex) {
			// B3：多实例下登记失败必须 fail-closed —— 回落进程内 Map 会让"上传在 A、回灌在 B"时
			// B 看不到登记（判定失真），且日志会把数据面丢失伪装成性能降级。
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_REGISTER, ex);
			log.error("failed to write attachment registration to Redis (fail-closed across instances; no in-process fallback) fileId={}", fileId,
					ex);
			throw new AiStateUnavailableException("Attachment registration failed because Redis is unavailable; in-process fallback is forbidden", ex);
		}
		// 两个索引都是"删文件"的枚举面：会话索引给会话销毁用，表单索引给表单收口回收与上传限额用。
		// 任一索引失败都必须回滚登记（否则销毁/收口时看不见这条登记，文件成孤儿）：先删数据键再抛出，
		// 调用方（上传端点）据此删除已落盘文件。
		try {
			addToIndex(redis, sessionIndexKey(identity, sessionId), fileId);
			addToIndex(redis, formIndexKey(identity, runId, toolCallId), fileId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_REGISTER, ex);
			log.error("attachment registration written but index failed (registration rolled back so destroy/closeout cannot miss files) fileId={} sessionId={} runId={} toolCallId={}", fileId, sessionId, runId, toolCallId,
					ex);
			try {
				redis.delete(registration);
				removeFromIndexQuietly(redis, sessionIndexKey(identity, sessionId), fileId);
				removeFromIndexQuietly(redis, formIndexKey(identity, runId, toolCallId), fileId);
			}
			catch (Exception cleanup) {
				log.warn("failed to roll back attachment registration (will expire via TTL) fileId={}", fileId, cleanup);
			}
			throw new AiStateUnavailableException("Attachment registration failed because an index is not writable; unenumerable registrations are forbidden", ex);
		}
		return attachment;
	}

	@Override
	public Attachment find(String fileId) {
		if (StringUtils.isBlank(fileId)) {
			return null;
		}
		TenantUser identity = RequestContextUtils.require();
		String registration = registrationKey(identity, fileId);
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.get(registration);
		}
		try {
			String json = redis.opsForValue().get(registration);
			return json == null ? null : this.objectMapper.readValue(json, Attachment.class);
		}
		catch (Exception ex) {
			// 读不出来 ≠ 不存在：多实例下必须让调用方知道无法校验，而不是回落本地 Map 误判。
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			log.error("failed to read attachment from Redis (fail-closed; no in-process fallback) fileId={}", fileId, ex);
			throw new AiStateUnavailableException("Attachment read failed because Redis is unavailable; in-process fallback is forbidden", ex);
		}
	}

	@Override
	public boolean consume(String fileId) {
		if (StringUtils.isBlank(fileId)) {
			return false;
		}
		TenantUser identity = RequestContextUtils.require();
		String registration = registrationKey(identity, fileId);
		String tombstone = consumedKey(identity, fileId);
		StringRedisTemplate redis = redis();
		if (redis == null) {
			if (this.memory.get(registration) == null) {
				return false;
			}
			// 进程内路径同为"墓碑先于删除"：先置已消费，再摘登记
			this.consumed.add(tombstone);
			this.memory.remove(registration);
			return true;
		}
		try {
			Attachment attachment = readRegistrationOrNull(redis, identity, fileId);
			if (attachment == null) {
				// 不存在/已过期：没有可消费的登记，也不该留墓碑（否则"从没上传过"会被报成"已消费"）
				return false;
			}
			// I5：墓碑（WAL）<b>先写</b>，数据键<b>后删</b>。
			// 反过来（先删数据键）时，墓碑写失败会留下最坏组合：登记已删 + 无墓碑 + 落盘文件成孤儿
			// （谁也枚举不到它）。现在墓碑写不进去就直接抛，数据键原样保留 —— 用户还能重试。
			redis.opsForValue().set(tombstone, "1", this.ttl);
			Boolean deleted = redis.delete(registration);
			if (Boolean.TRUE.equals(deleted)) {
				this.memory.remove(registration);
				removeFromIndexQuietly(redis, sessionIndexKey(identity, attachment.sessionId()), fileId);
				removeFromIndexQuietly(redis, formIndexKey(identity, attachment.runId(), attachment.toolCallId()),
						fileId);
				return true;
			}
			// 墓碑已写但数据键已被并发消费/过期：不是本次消费成功（返回 false 由调用方告警）
			return false;
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_CONSUME, ex);
			log.error("failed to write attachment consume to Redis (fail-closed; no in-process fallback) fileId={}", fileId, ex);
			throw new AiStateUnavailableException("Attachment consume failed because Redis is unavailable; in-process fallback is forbidden", ex);
		}
	}

	/**
	 * 该会话仍在册的附件（销毁枚举面）。
	 *
	 * <p>索引成员已过期/被并发消费时跳过；读失败或登记 sessionId 与索引归属不符均直接抛出，
	 * 不把归属污染折成空表或部分枚举。
	 */
	@Override
	public List<Attachment> findBySessionId(String sessionId) {
		if (StringUtils.isBlank(sessionId)) {
			return List.of();
		}
		TenantUser identity = RequestContextUtils.require();
		StringRedisTemplate redis = redis();
		if (redis == null) {
			// 进程内兜底同样按身份分桶：map 的键是带身份段的登记键，不能只看 sessionId。
			return this.memory.values()
				.stream()
				.filter(item -> sessionId.equals(item.sessionId())
						&& this.memory.containsKey(registrationKey(identity, item.fileId())))
				.toList();
		}
		SetOperations<String, String> setOps = redis.opsForSet();
		if (setOps == null) {
			return List.of();
		}
		Set<String> ids;
		try {
			ids = setOps.members(sessionIndexKey(identity, sessionId));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			throw new AiStateUnavailableException("Failed to read the attachment session index; destroy enumeration must not treat it as empty", ex);
		}
		if (CollectionUtils.isEmpty(ids)) {
			return List.of();
		}
		List<Attachment> out = new ArrayList<>();
		for (String fileId : ids) {
			Attachment attachment = find(fileId);
			if (attachment == null) {
				continue;
			}
			if (!sessionId.equals(attachment.sessionId())) {
				throw new AiStateUnavailableException("Attachment session index ownership pollution (fail-closed) fileId=" + fileId);
			}
			out.add(attachment);
		}
		return out.isEmpty() ? List.of() : out;
	}

	/**
	 * 续期该表单名下的登记、表单索引与会话索引（挂起续期链的一次 touch）。
	 *
	 * <p><b>RTT 收口（3N+2 → 3）</b>：旧实现对每件做 {@code EXPIRE + GET + EXPIRE} ——
	 * 一次挂起心跳在 N 件附件上要发 {@code 3N+2} 条命令。现在固定三次往返：
	 * <ol>
	 * <li>{@code SMEMBERS} 取表单索引（一次）；</li>
	 * <li>一条 pipeline 批量 {@code GET} 登记（一次；不再逐件 {@code GET}，也<b>不用</b> 跨键
	 * {@code MGET} —— 登记键不同槽，{@code MGET} 在 Cluster 下是 {@code CROSSSLOT}）；</li>
	 * <li>一条 pipeline 续期「在册登记 + <b>去重后</b>的会话索引（distinct sessionId）+ 表单索引」（一次）。</li>
	 * </ol>
	 *
	 * <p><b>逐件隔离不变</b>：pipeline 里每条命令独立下发，单件失败（键类型错/已删）不影响其余件；
	 * 连接类失败只告警（{@link RedisDegradation#B3_ATTACHMENT_READ} 计数），不让确认门心跳因一个键
	 * 失败而中断 —— 下一次心跳还会再试。陈旧成员（登记已过期/被消费）不再被"续"出一个空登记。
	 *
	 * <p><b>替身/包装层兜底</b>：批量读或 pipeline 没有被真正执行（单测替身返回空/形状不符）时，
	 * 退回原来的逐件 {@code EXPIRE + GET + EXPIRE} 路径 —— 语义完全一致，只多 RTT。
	 */
	@Override
	public void touch(String runId, String toolCallId) {
		TenantUser identity = RequestContextUtils.require();
		String indexKey = formIndexKey(identity, runId, toolCallId);
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
			return;
		}
		if (CollectionUtils.isEmpty(ids)) {
			return;
		}
		if (!isBatchTouchSuccessful(redis, identity, indexKey, ids, runId, toolCallId)) {
			touchPerItem(redis, identity, indexKey, ids, runId, toolCallId);
		}
	}

	/**
	 * 批量续期（正常生产路径）：一次 pipeline 读 + 一次 pipeline 写，共三次往返（含 SMEMBERS）。
	 *
	 * @return {@code false} = 批量面没有被真的执行（替身/包装层），调用方退回逐件路径；
	 *         {@code true} = 已处理（含"批量读失败只告警"的降级）
	 */
	private boolean isBatchTouchSuccessful(StringRedisTemplate redis, TenantUser identity, String indexKey, Set<String> ids,
			String runId, String toolCallId) {
		List<String> fileIds = new ArrayList<>(ids);
		List<String> registrationKeys = new ArrayList<>(fileIds.size());
		for (String fileId : fileIds) {
			registrationKeys.add(registrationKey(identity, fileId));
		}
		Optional<List<Object>> raw;
		try {
			raw = pipelineGet(redis, registrationKeys);
		}
		catch (Exception ex) {
			// 读失败 ≠ 不存在：只告警（续期是养活性维护，下一拍再试），不把附件当成已过期。
			// 表单索引本身仍尽力续一次 —— 索引先过期会让整张表单的附件一起从回收面消失。
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			expireIndexQuietly(redis, indexKey, runId, toolCallId);
			return true;
		}
		if (raw.isEmpty()) {
			return false;
		}
		List<String> liveRegistrationKeys = new ArrayList<>(fileIds.size());
		// distinct sessionId：同一会话的多件附件只续一次会话索引（旧实现逐件续，重复往返）
		Set<String> sessionIndexKeys = new LinkedHashSet<>();
		for (int i = 0; i < fileIds.size(); i++) {
			Attachment attachment = parseRegistration(asString(raw.get().get(i)), fileIds.get(i));
			if (attachment == null) {
				// 陈旧成员（登记已过期/被并发消费）：不续一条不存在的登记，枚举时的 find 会过滤掉它
				continue;
			}
			liveRegistrationKeys.add(registrationKeys.get(i));
			String sessionKey = sessionIndexKey(identity, attachment.sessionId());
			if (sessionKey != null) {
				sessionIndexKeys.add(sessionKey);
			}
		}
		List<String> keys = new ArrayList<>(liveRegistrationKeys.size() + sessionIndexKeys.size() + 1);
		keys.addAll(liveRegistrationKeys);
		keys.addAll(sessionIndexKeys);
		keys.add(indexKey);
		return isPipelineExpirySuccessful(redis, keys, runId, toolCallId);
	}

	/**
	 * 一次 pipeline 批量读登记（{@code GET} × N，<b>一次往返</b>）。
	 *
	 * <p>为什么不是 {@code MGET}：登记键<b>不带 hash tag</b>（每件一个槽），而 {@code MGET} 是多键命令
	 * —— Cluster 下会整条回 {@code CROSSSLOT}，一件都读不到。pipeline 里的<b>单键</b> {@code GET}
	 * 各自路由到自己的槽，合法且同样只需一次往返。
	 *
	 * @return 与 {@code keys} 同序的结果（缺失成员为 {@code null}）；{@code Optional.empty()} = 管道未被执行
	 *         （替身/包装层），调用方退回逐件路径
	 */
	private static Optional<List<Object>> pipelineGet(StringRedisTemplate redis, List<String> keys) {
		if (keys.isEmpty()) {
			return Optional.of(List.of());
		}
		List<Object> raw = redis.executePipelined((RedisCallback<Object>) connection -> {
			for (String key : keys) {
				connection.get(key.getBytes(StandardCharsets.UTF_8));
			}
			return null;
		});
		return raw == null || raw.size() < keys.size() ? Optional.empty() : Optional.of(raw);
	}

	/** pipeline 结果的字符串化（{@code String} / {@code byte[]} 都接受：模板/连接实现可能各给一种）。 */
	private static String asString(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof String text) {
			return text;
		}
		if (raw instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return String.valueOf(raw);
	}

	/**
	 * 一条 pipeline 续期全部键（{@code PEXPIRE}，<b>TTL 与登记同源</b>）。
	 *
	 * <p>为什么不是 Lua：这些键<b>跨槽</b>（登记/索引不带 hash tag）—— 多键脚本会被 Cluster 以
	 * {@code CROSSSLOT} 整条拒绝；pipeline 里每条命令独立路由，合法且天然逐件隔离。
	 *
	 * @return {@code false} = 流水线没有真的执行（替身返回空表），调用方退回逐件路径
	 */
	private boolean isPipelineExpirySuccessful(StringRedisTemplate redis, List<String> keys, String runId, String toolCallId) {
		StringRedisSerializer serializer = new StringRedisSerializer();
		long ttlMillis = this.ttl.toMillis();
		List<Object> results;
		try {
			results = redis.executePipelined((RedisCallback<Object>) connection -> {
				for (String key : keys) {
					connection.pExpire(serializer.serialize(key), ttlMillis);
				}
				return null;
			});
		}
		catch (Exception ex) {
			// 命令已逐条下发：单条失败不阻断其余（逐件隔离保留），这里只留痕；下一拍会再试
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			log.warn("attachment renewal pipeline failed (retry next tick) runId={} toolCallId={} keys={}", runId, toolCallId,
					keys.size(), ex);
			return true;
		}
		return CollectionUtils.isNotEmpty(results);
	}

	/**
	 * 逐件续期（替身/包装层兜底 + 历史语义）：单件失败隔离，不影响其余件。
	 *
	 * <p>每件三步：续登记、读登记（拿 sessionId）、续会话索引。失败留待下一拍重试，
	 * 不让确认门心跳因一个键失败而中断 —— 与批量路径的降级口径一致。
	 */
	private void touchPerItem(StringRedisTemplate redis, TenantUser identity, String indexKey, Set<String> ids,
			String runId, String toolCallId) {
		for (String fileId : ids) {
			try {
				redis.expire(registrationKey(identity, fileId), this.ttl);
				Attachment attachment = readRegistrationOrNull(redis, identity, fileId);
				if (attachment != null) {
					redis.expire(sessionIndexKey(identity, attachment.sessionId()), this.ttl);
				}
			}
			catch (Exception ex) {
				// 单件失败不影响其他附件。
			}
		}
		expireIndexQuietly(redis, indexKey, runId, toolCallId);
	}

	/** 表单索引单独续期（降级路径用：索引是"删文件"的枚举面，不能因为别的键读失败就跟着过期）。 */
	private void expireIndexQuietly(StringRedisTemplate redis, String indexKey, String runId, String toolCallId) {
		try {
			redis.expire(indexKey, this.ttl);
		}
		catch (Exception ex) {
			// 索引续期失败留待下一拍重试。
		}
	}

	/**
	 * 该表单仍在册的附件（上传限额面 + 表单收口回收面）。
	 *
	 * <p>索引成员已过期/被并发消费时跳过；读失败或登记 runId/toolCallId 与索引归属不符均直接抛出，
	 * 上传限额与收口回收不得把污染折成空表或部分枚举。
	 */
	@Override
	public List<Attachment> findByForm(String runId, String toolCallId) {
		if (StringUtils.isBlank(runId) || StringUtils.isBlank(toolCallId)) {
			return List.of();
		}
		TenantUser identity = RequestContextUtils.require();
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.values()
				.stream()
				.filter(item -> runId.equals(item.runId()) && toolCallId.equals(item.toolCallId())
						&& this.memory.containsKey(registrationKey(identity, item.fileId())))
				.toList();
		}
		SetOperations<String, String> setOps = redis.opsForSet();
		if (setOps == null) {
			return List.of();
		}
		Set<String> ids;
		try {
			ids = setOps.members(formIndexKey(identity, runId, toolCallId));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			throw new AiStateUnavailableException("Failed to read the attachment form index; upload limits and closeout cleanup must not treat it as empty", ex);
		}
		if (CollectionUtils.isEmpty(ids)) {
			return List.of();
		}
		List<Attachment> out = new ArrayList<>();
		for (String fileId : ids) {
			Attachment attachment = find(fileId);
			if (attachment == null) {
				continue;
			}
			if (!runId.equals(attachment.runId()) || !toolCallId.equals(attachment.toolCallId())) {
				throw new AiStateUnavailableException("Attachment form index ownership pollution (fail-closed) fileId=" + fileId);
			}
			out.add(attachment);
		}
		return out.isEmpty() ? List.of() : out;
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
		if (StringUtils.isBlank(fileId)) {
			return null;
		}
		return deleteKnownRegistration(find(fileId), fileId);
	}

	@Override
	public Attachment deleteRegistration(Attachment attachment) {
		return attachment == null ? null : deleteKnownRegistration(attachment, attachment.fileId());
	}

	private Attachment deleteKnownRegistration(Attachment attachment, String fileId) {
		TenantUser identity = RequestContextUtils.require();
		String registration = registrationKey(identity, fileId);
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.memory.remove(registration);
		}
		try {
			redis.delete(registration);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_CONSUME, ex);
			throw new AiStateUnavailableException("Failed to delete attachment registration because Redis is unavailable; retry is allowed", ex);
		}
		try {
			redis.delete(consumedKey(identity, fileId));
		}
		catch (Exception ex) {
			log.warn("failed to clean attachment consume tombstone (tombstone expires via TTL) fileId={}", fileId, ex);
		}
		removeFromIndexQuietly(redis, sessionIndexKey(identity,
				attachment == null ? null : attachment.sessionId()), fileId);
		removeFromIndexQuietly(redis, formIndexKey(identity, attachment == null ? null : attachment.runId(),
				attachment == null ? null : attachment.toolCallId()), fileId);
		return attachment;
	}

	@Override
	public void deleteSessionIndex(String sessionId) {
		TenantUser identity = RequestContextUtils.require();
		String key = sessionIndexKey(identity, sessionId);
		StringRedisTemplate redis = redis();
		if (redis == null || key == null) {
			return;
		}
		try {
			redis.delete(key);
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to delete the attachment session index; retry is allowed", ex);
		}
	}

	/**
	 * 索引登记（失败抛出；调用方回滚登记）。key 为空 = 无法归属，跳过（不阻断上传）。
	 *
	 * <p>{@code SADD + PEXPIRE} 走<b>单键 Lua</b>（见 {@link #INDEX_ADD_SCRIPT}）：成员的写入与
	 * 索引 TTL 同生同死，不会留下"有成员、无 TTL"的永久索引。
	 *
	 * <p><b>脚本不可用 ≠ 写失败</b>，两者必须分开处置：
	 * <ul>
	 * <li>只有"脚本面不支持"（{@link UnsupportedOperationException}、{@code unknown command} /
	 *     {@code not supported} 这类明确信号）或"脚本没被执行"（返回 null，替身/包装层）才退回
	 *     {@code SADD + EXPIRE}；</li>
	 * <li><b>连接类/未知 {@link RuntimeException} 一律上抛</b>：EVAL 可能已在服务端执行（只是回包丢了），
	 *     盲目改走两步式等于在故障中重复写、放大问题；让调用方回滚登记并重试整条上传更安全。</li>
	 * </ul>
	 */
	private void addToIndex(StringRedisTemplate redis, String indexKey, String fileId) {
		if (indexKey == null) {
			return;
		}
		Long applied;
		try {
			applied = redis.execute(INDEX_ADD_SCRIPT, List.of(indexKey), fileId,
					String.valueOf(this.ttl.toMillis()));
		}
		catch (RuntimeException ex) {
			if (!isScriptUnsupported(ex)) {
				throw ex;
			}
			log.warn("attachment index script unavailable (degrading to SADD+EXPIRE) key={}", indexKey, ex);
			applied = null;
		}
		if (applied == null) {
			SetOperations<String, String> setOps = redis.opsForSet();
			if (setOps == null) {
				return;
			}
			setOps.add(indexKey, fileId);
			redis.expire(indexKey, this.ttl);
		}
	}

	/**
	 * 该异常是否属于"脚本面不可用"（而不是连接/服务端执行失败）。
	 *
	 * <p>判据刻意收窄：只认 {@link UnsupportedOperationException}（包装层/替身不支持 EVAL）与
	 * 命令被明确拒绝的文案（{@code unknown command} / {@code not supported}）。连接超时、断连、
	 * {@code READONLY} 等一律不算 —— 它们必须上抛，不能触发第二步写。
	 */
	private static boolean isScriptUnsupported(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof UnsupportedOperationException) {
				return true;
			}
			String message = current.getMessage();
			if (message != null) {
				String lower = message.toLowerCase(Locale.ROOT);
				if (lower.contains("unknown command") || lower.contains("not supported")) {
					return true;
				}
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private static String sessionIndexKey(TenantUser identity, String sessionId) {
		String segment = TenantKeyScope.segmentOf(identity);
		return StringUtils.isBlank(sessionId) ? null : SESSION_INDEX_PREFIX + segment + sessionId;
	}

	private static String formIndexKey(TenantUser identity, String runId, String toolCallId) {
		String segment = TenantKeyScope.segmentOf(identity);
		if (StringUtils.isBlank(runId) || StringUtils.isBlank(toolCallId)) {
			return null;
		}
		return FORM_INDEX_PREFIX + segment + runId + ":" + toolCallId;
	}

	/** 登记键（带身份段；身份缺失即失败）。 */
	private static String registrationKey(TenantUser identity, String fileId) {
		return KEY_PREFIX + TenantKeyScope.segmentOf(identity) + fileId;
	}

	/** 已消费墓碑键（带身份段；身份缺失即失败）。 */
	private static String consumedKey(TenantUser identity, String fileId) {
		return CONSUMED_PREFIX + TenantKeyScope.segmentOf(identity) + fileId;
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
			log.warn("failed to clean attachment index key={} fileId={} (stale members are filtered on enumeration)", indexKey, fileId,
					ex);
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
	private Attachment readRegistrationOrNull(StringRedisTemplate redis, TenantUser identity, String fileId) {
		ValueOperations<String, String> values = redis.opsForValue();
		if (values == null) {
			return null;
		}
		String json;
		try {
			json = values.get(registrationKey(identity, fileId));
		}
		catch (RuntimeException ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_READ, ex);
			throw new AiStateUnavailableException("Attachment read failed because Redis is unavailable; it must not be treated as unregistered", ex);
		}
		if (json == null) {
			return null;
		}
		return parseRegistration(json, fileId);
	}

	/**
	 * 登记 JSON → {@link Attachment}；不存在/坏数据返回 {@code null}（等同"已过期"）。
	 *
	 * <p><b>只有"坏数据"才折成 null</b>：解析不了 ⇒ 按无登记处理。基础设施故障不在这里吞
	 * （调用方各自按"读失败 ≠ 不存在"处理，见 {@link #readRegistrationOrNull} 与 {@link #touch}）。
	 */
	private Attachment parseRegistration(String json, String fileId) {
		if (json == null) {
			return null;
		}
		try {
			return this.objectMapper.readValue(json, Attachment.class);
		}
		catch (JsonProcessingException ex) {
			log.warn("attachment registration payload is unparseable (treated as unregistered) fileId={}", fileId, ex);
			return null;
		}
	}

	@Override
	public boolean wasConsumed(String fileId) {
		if (StringUtils.isBlank(fileId)) {
			return false;
		}
		TenantUser identity = RequestContextUtils.require();
		String tombstone = consumedKey(identity, fileId);
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return this.consumed.contains(tombstone);
		}
		try {
			return Boolean.TRUE.equals(redis.hasKey(tombstone));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.B3_ATTACHMENT_TOMBSTONE, ex);
			log.error("failed to read attachment tombstone from Redis (fail-closed; no in-process fallback) fileId={}", fileId, ex);
			throw new AiStateUnavailableException("Attachment tombstone read failed because Redis is unavailable; in-process fallback is forbidden", ex);
		}
	}

	/** 当前可用数据源（模板未装配返回 null；获取模板本身失败按 fail-closed 抛错）。 */
	private StringRedisTemplate redis() {
		try {
			return this.redisProvider.getIfAvailable();
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to obtain the Redis template for the attachment registry", ex);
		}
	}

	/** 进程内登记簿快照（排障/用例用，只读）。 */
	public Map<String, Attachment> snapshot() {
		return this.memory.isEmpty() ? Map.of() : new LinkedHashMap<>(this.memory);
	}

}
