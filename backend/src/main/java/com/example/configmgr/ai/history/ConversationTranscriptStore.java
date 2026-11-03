package com.example.configmgr.ai.history;

import com.example.configmgr.ai.memory.SessionKeyCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.ai.chat.messages.Message;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>用户展示历史</b>（transcript）的 Redis 存储（B2）。
 *
 * <h2>它解决什么</h2>
 * {@code GET /api/ai/history} 此前直读 {@code chat:mem} —— 那是<b>模型工作上下文</b>，会被记忆窗口
 * 与 L1/L2/L3 改写：刷新后旧轮消失、工具原文变占位符、摘要冒充用户消息。本类保存一份
 * <b>append-only 的用户可见原文</b>，压缩/窗口永远不改它；模型仍只读 {@code chat:mem}。
 *
 * <h2>键与 TTL</h2>
 * <ul>
 * <li>{@code ai:tx:<tenantId>:<userId>:{<escapedId>}} — LIST，每元素一条 {@link TranscriptEntry} JSON
 * （会话 id 转义与 {@code chat:mem} 共用 {@link SessionKeyCodec}）；</li>
 * <li>{@code ...:ready} — 一次性 baseline 标记（STRING，SETNX）；</li>
 * <li>{@code ...:truncated} — 容量裁剪标记（STRING；只用于历史载荷的<b>中性</b>提示，不做顶部提示）。</li>
 * </ul>
 * 三把键的 hash tag 都是 {@code {<escapedId>}}（同槽）；TTL = {@code app.ai.session-ttl}，
 * <b>读也 touch</b>（与记忆同滑动）。
 *
 * <h2>无身份 = 不写 legacy</h2>
 * 写入必须带显式身份（{@code tenantId/userId} 来自 run 快照）或当前请求身份；两者都缺失时
 * <b>拒绝写</b>（返回 false + DEBUG），绝不落到无身份 {@code ai:tx:{<id>}} 命名空间 ——
 * 否则不同租户的同名 sessionId 会互相读到对方正文。
 *
 * <h2>幂等 / last-wins / 排序</h2>
 * 写入永远 append（无更新 API）；读取按 {@code entryId} 去重取<b>最后一条</b>（工具卡状态更新、
 * 挂起 pending → 终态），再按 {@code turnStartedAtMs} 稳定排序（挂起轮晚收尾仍排在它该在的位置，
 * 同轮按追加顺序）。
 *
 * <h2>容量</h2>
 * 单会话最多 {@value #MAX_ENTRIES} 条、约 {@value #MAX_BYTES} 字节；超出从最旧裁剪并置
 * {@code truncated} 标记。裁剪只影响 transcript，不影响模型记忆。
 *
 * <h2>失败姿态</h2>
 * 写失败只 WARN（展示历史不得让一轮对话失败）；读失败上抛，端点转成既有可见恢复错误（503），
 * 不伪装成"空历史"。
 */
@Slf4j
public class ConversationTranscriptStore {

	/** 单会话条目上限（超过从最旧裁）。 */
	public static final int MAX_ENTRIES = 5000;

	/** 单会话字节上限（按写入 payload 的 UTF-8 字节精确统计；超过从最旧裁）。 */
	public static final long MAX_BYTES = 8L * 1024L * 1024L;

	private static final String PREFIX = "ai:tx:";

	private static final String READY_SUFFIX = ":ready";

	private static final String TRUNCATED_SUFFIX = ":truncated";

	/** 精确字节计数（同 {@code {sid}} hash tag、同 TTL；只在写脚本里维护）。 */
	private static final String BYTES_SUFFIX = ":bytes";

	/**
	 * 有界追加 + 精确字节计数 + TTL，全部在一个单键脚本里原子完成。
	 *
	 * <p><b>为什么计数放在 Lua</b>：字节总量必须与 LIST 的真实内容同步，否则并发 append/裁剪会让
	 * 客户端持有的计数漂移。Lua 里 {@code #string} 就是 UTF-8 字节长度，且脚本串行执行，
	 * {@code INCR + 裁剪 + SET} 不会被别的写者插队。
	 *
	 * <p><b>兼容已有键</b>：计数器缺失（升级前写入 / 计数器先过期）、为 0/负值/非数字且 LIST
	 * 已存在时，一次 {@code LRANGE} 全量核算；LIST 不存在（新会话或 purge 后重建）则直接以本次
	 * payload 为准，不复用可能过期的旧计数。计数只会在同脚本里随 push/trim 同步加减，算出负值时
	 * 再核算一次，不会永久漂移。
	 *
	 * <p><b>只读待裁前缀</b>：条数/字节裁剪只 {@code LRANGE} 前缀块（≤200 条），不做整表读取。
	 *
	 * <p>KEYS：1 = LIST，2 = 字节计数，3 = ready 标记，4 = truncated 标记（同槽）。
	 * ARGV：1 = TTL 毫秒，2 = 条数上限，3 = 字节上限，4 = 本次 payload 的 UTF-8 字节和，
	 * 5.. = payload（保持写入顺序）。
	 */
	private static final RedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
			-- TRANSCRIPT_APPEND_BOUNDED
			local ttl = tonumber(ARGV[1])
			local maxEntries = tonumber(ARGV[2])
			local maxBytes = tonumber(ARGV[3])
			local added = tonumber(ARGV[4])
			local existed = redis.call('EXISTS', KEYS[1])
			for i = 5, #ARGV do
			  redis.call('RPUSH', KEYS[1], ARGV[i])
			end
			redis.call('PEXPIRE', KEYS[1], ttl)
			redis.call('PEXPIRE', KEYS[3], ttl)
			redis.call('PEXPIRE', KEYS[4], ttl)
			local size = redis.call('LLEN', KEYS[1])
			local bytes
			local recount = false
			if existed == 0 then
			  -- 新会话/重建：丢弃可能残留的旧计数
			  bytes = added
			elseif redis.call('EXISTS', KEYS[2]) == 1 then
			  local prev = tonumber(redis.call('GET', KEYS[2]))
			  if prev and prev > 0 then
			    bytes = prev + added
			  else
			    -- 0/负值/非数字：LIST 非空时计数不可信，标记重算（不加 added，重算含本次 push）
			    bytes = 0
			    recount = true
			  end
			else
			  bytes = 0
			  recount = true
			end
			local trimmed = false
			if size > maxEntries then
			  local drop = size - maxEntries
			  local head = redis.call('LRANGE', KEYS[1], 0, drop - 1)
			  for i = 1, #head do bytes = bytes - #head[i] end
			  redis.call('LTRIM', KEYS[1], drop, -1)
			  size = maxEntries
			  trimmed = true
			end
			if size > 0 and (recount or bytes <= 0) then
			  bytes = 0
			  local all = redis.call('LRANGE', KEYS[1], 0, -1)
			  for i = 1, #all do bytes = bytes + #all[i] end
			end
			while bytes > maxBytes and size > 1 do
			  local chunk = math.min(size - 1, 200)
			  local head = redis.call('LRANGE', KEYS[1], 0, chunk - 1)
			  if #head == 0 then break end
			  for i = 1, #head do bytes = bytes - #head[i] end
			  redis.call('LTRIM', KEYS[1], chunk, -1)
			  size = size - chunk
			  trimmed = true
			end
			if bytes < 0 then bytes = 0 end
			redis.call('SET', KEYS[2], string.format('%.0f', bytes), 'PX', ttl)
			if trimmed then
			  redis.call('SET', KEYS[4], '1', 'PX', ttl)
			end
			return 1
			""", Long.class);

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final Duration ttl;

	private final int maxEntries;

	private final long maxBytes;

	public ConversationTranscriptStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
		this(redis, mapper, ttl, MAX_ENTRIES, MAX_BYTES);
	}

	/** 测试用：可收紧条目/字节上限（生产走上面的默认值）。 */
	ConversationTranscriptStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl, int maxEntries,
			long maxBytes) {
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = ttl;
		this.maxEntries = Math.max(1, maxEntries);
		this.maxBytes = Math.max(1L, maxBytes);
	}

	/** 读取结果（{@code truncated} = 更早的条目曾因容量被裁，只用于中性提示）。 */
	public record ReadResult(List<TranscriptEntry> entries, boolean truncated) {

		public static final ReadResult EMPTY = new ReadResult(List.of(), false);

		public boolean isEmpty() {
			return this.entries.isEmpty();
		}

	}

	/** 追加条目（当前请求身份；无身份 ⇒ 不写、返回 false）。 */
	public boolean append(String sessionId, List<TranscriptEntry> entries) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		return identity != null && append(sessionId, identity.tenantId(), identity.userId(), entries);
	}

	/** 追加条目（显式身份；run 快照的 tenantId/userId）。 */
	public boolean append(String sessionId, long tenantId, long userId, List<TranscriptEntry> entries) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			log.debug("transcript append skipped: no usable identity sessionId={} (legacy namespace not used)", sessionId);
			return false;
		}
		List<TranscriptEntry> filtered = filter(entries);
		if (filtered.isEmpty()) {
			return true;
		}
		try {
			List<String> payloads = new ArrayList<>(filtered.size());
			for (TranscriptEntry entry : filtered) {
				payloads.add(json(entry));
			}
			// TTL 无效时 appendWithBounds 拒写并返回 false —— 不把"没写"报成成功。
			return appendWithBounds(sessionId, tenantId, userId, payloads);
		}
		catch (RuntimeException ex) {
			log.warn("transcript append failed sessionId={} error={} (display history does not affect this turn)", sessionId, ex.getMessage());
			return false;
		}
	}

	/** 读取（当前请求身份；无身份 ⇒ 空结果，端点回落记忆）。 */
	public ReadResult read(String sessionId) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		return identity == null ? ReadResult.EMPTY
				: read(sessionId, identity.tenantId(), identity.userId());
	}

	/**
	 * 读取（显式身份）。
	 *
	 * <p>Redis/序列化异常<b>上抛</b>：调用方必须把它变成可见恢复错误，不能返回"空历史"。
	 */
	public ReadResult read(String sessionId, long tenantId, long userId) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			return ReadResult.EMPTY;
		}
		String key = dataKey(sessionId, tenantId, userId);
		List<String> raw = this.redis.opsForList().range(key, 0, -1);
		if (raw == null || raw.isEmpty()) {
			return ReadResult.EMPTY;
		}
		Map<String, TranscriptEntry> byId = new LinkedHashMap<>();
		for (String payload : raw) {
			TranscriptEntry entry = parse(payload, sessionId);
			if (entry != null && entry.entryId() != null && !entry.entryId().isBlank()) {
				// LinkedHashMap 替换保留首次插入位置：last-wins 的值 + 原始时序位置
				byId.put(entry.entryId(), entry);
			}
		}
		List<TranscriptEntry> sorted = new ArrayList<>(byId.values());
		sorted.sort(Comparator.comparingLong(TranscriptEntry::turnStartedAtMs));
		touch(key, readyKey(sessionId, tenantId, userId), truncatedKey(sessionId, tenantId, userId));
		boolean truncated = Boolean.TRUE.equals(this.redis.hasKey(truncatedKey(sessionId, tenantId, userId)));
		return new ReadResult(List.copyOf(sorted), truncated);
	}

	/**
	 * 首次写入前把既有 {@code chat:mem} 复制一份 baseline（仅当还没有 {@code :ready} 标记）。
	 *
	 * <p>幂等：多实例同时初始化时可能各复制一份，但 entryId 相同 ⇒ 读取 last-wins 不产生重复气泡。
	 */
	public void ensureBaseline(String sessionId, List<Message> memory) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity != null) {
			ensureBaseline(sessionId, identity.tenantId(), identity.userId(), memory);
		}
	}

	public void ensureBaseline(String sessionId, long tenantId, long userId, List<Message> memory) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId) || memory == null || memory.isEmpty()) {
			return;
		}
		String key = dataKey(sessionId, tenantId, userId);
		String ready = readyKey(sessionId, tenantId, userId);
		try {
			if (Boolean.TRUE.equals(this.redis.hasKey(ready))) {
				touch(key, ready, truncatedKey(sessionId, tenantId, userId));
				return;
			}
			List<TranscriptEntry> baseline = TranscriptEntries.baseline(memory);
			if (!baseline.isEmpty()) {
				List<String> payloads = new ArrayList<>(baseline.size());
				for (TranscriptEntry entry : baseline) {
					payloads.add(json(entry));
				}
				appendWithBounds(sessionId, tenantId, userId, payloads);
			}
			this.redis.opsForValue().setIfAbsent(ready, "1", this.ttl);
			touch(key, ready, truncatedKey(sessionId, tenantId, userId));
		}
		catch (RuntimeException ex) {
			log.warn("transcript baseline copy failed sessionId={} error={} (no write-back, turn not blocked)", sessionId, ex.getMessage());
		}
	}

	/** 当前身份的数据键（purge/诊断用；无身份时为 legacy 形，但本类从不往那里写）。 */
	public static String dataKey(String sessionId) {
		return PREFIX + TenantKeyScope.currentSegment() + SessionKeyCodec.hashTag(sessionId);
	}

	public static String dataKey(String sessionId, long tenantId, long userId) {
		return PREFIX + TenantKeyScope.segmentOf(tenantId, userId) + SessionKeyCodec.hashTag(sessionId);
	}

	public static String readyKey(String sessionId, long tenantId, long userId) {
		return dataKey(sessionId, tenantId, userId) + READY_SUFFIX;
	}

	public static String readyKey(String sessionId) {
		return dataKey(sessionId) + READY_SUFFIX;
	}

	public static String truncatedKey(String sessionId, long tenantId, long userId) {
		return dataKey(sessionId, tenantId, userId) + TRUNCATED_SUFFIX;
	}

	public static String truncatedKey(String sessionId) {
		return dataKey(sessionId) + TRUNCATED_SUFFIX;
	}

	public static String bytesKey(String sessionId, long tenantId, long userId) {
		return dataKey(sessionId, tenantId, userId) + BYTES_SUFFIX;
	}

	/** 当前身份下的计数键（purge/诊断用；与 {@link #dataKey(String)} 同一分段来源）。 */
	public static String bytesKey(String sessionId) {
		return dataKey(sessionId) + BYTES_SUFFIX;
	}

	// ── 容量 ────────────────────────────────────────────────────────────────

	private static long bytesOf(String payload) {
		return payload == null ? 0L : payload.getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * 有界追加：push、精确字节计数、条数/字节裁剪、TTL 全在 {@link #APPEND_SCRIPT} 内原子完成。
	 *
	 * <p>热路径不再 {@code LRANGE 0 -1}：只有计数器缺失（升级前旧键）或算出负值时才做一次全量核算；
	 * 常规裁剪只读待丢的前缀块（≤200 条）。
	 */
	private boolean appendWithBounds(String sessionId, long tenantId, long userId, List<String> payloads) {
		if (payloads == null || payloads.isEmpty()) {
			return true;
		}
		if (this.ttl == null || this.ttl.isZero() || this.ttl.isNegative()) {
			// 无 TTL 的展示历史正是本批要消除的永久键：配置缺失时拒写（展示面降级，不制造永久键）。
			// 返回值必须如实为 false —— 否则调用方会把"没写"当成"已落盘"。
			log.warn("transcript append skipped: app.ai.session-ttl not configured sessionId={}", sessionId);
			return false;
		}
		long added = 0L;
		for (String payload : payloads) {
			added += bytesOf(payload);
		}
		List<Object> args = new ArrayList<>(payloads.size() + 4);
		args.add(String.valueOf(this.ttl.toMillis()));
		args.add(String.valueOf(this.maxEntries));
		args.add(String.valueOf(this.maxBytes));
		args.add(String.valueOf(added));
		args.addAll(payloads);
		this.redis.execute(APPEND_SCRIPT,
				List.of(dataKey(sessionId, tenantId, userId), bytesKey(sessionId, tenantId, userId),
						readyKey(sessionId, tenantId, userId), truncatedKey(sessionId, tenantId, userId)),
				args.toArray());
		return true;
	}

	// ── TTL / 序列化 ────────────────────────────────────────────────────────

	private void touch(String dataKey, String ready, String truncated) {
		if (this.ttl == null || this.ttl.isZero() || this.ttl.isNegative()) {
			return;
		}
		try {
			this.redis.expire(dataKey, this.ttl);
			// 计数键与数据同寿：读也续期，避免"数据还在、计数先过期"频繁触发一次全量核算。
			this.redis.expire(dataKey + BYTES_SUFFIX, this.ttl);
			this.redis.expire(ready, this.ttl);
			this.redis.expire(truncated, this.ttl);
		}
		catch (RuntimeException ex) {
			log.debug("transcript TTL refresh failed key={} error={}", dataKey, ex.getMessage());
		}
	}

	private String json(TranscriptEntry entry) {
		try {
			return this.mapper.writeValueAsString(entry);
		}
		catch (Exception ex) {
			throw new IllegalStateException("transcript 条目序列化失败：" + ex.getMessage(), ex);
		}
	}

	private TranscriptEntry parse(String payload, String sessionId) {
		try {
			return this.mapper.readValue(payload, TranscriptEntry.class);
		}
		catch (Exception ex) {
			log.warn("transcript entry parse failed (skipped) sessionId={} error={}", sessionId, ex.getMessage());
			return null;
		}
	}

	private static List<TranscriptEntry> filter(Collection<TranscriptEntry> entries) {
		if (entries == null || entries.isEmpty()) {
			return List.of();
		}
		List<TranscriptEntry> out = new ArrayList<>(entries.size());
		for (TranscriptEntry entry : entries) {
			if (entry != null && entry.entryId() != null && !entry.entryId().isBlank()) {
				out.add(entry);
			}
		}
		return out;
	}

}
