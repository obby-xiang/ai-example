package com.example.configmgr.ai.history;

import com.example.configmgr.ai.memory.SessionKeyCodec;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 用户展示历史的规范读模型。entryId 对应 HASH 中的一个当前条目，ZSET 只保存首次写入顺序；
 * 更新不改变顺序。所有键共享 session hash tag，standalone 与 Cluster 使用同一套 Lua。
 */
@Slf4j
public class ConversationTranscriptStore {

	public static final int MAX_ENTRIES = 5000;

	public static final long MAX_BYTES = 8L * 1024L * 1024L;

	private static final int DEFAULT_PAGE_SIZE = 200;

	private static final int MAX_PAGE_SIZE = 500;

	private static final String PREFIX = "ai:tx:";

	private static final String TRUNCATED_SUFFIX = ":truncated";

	private static final String ORDER_SUFFIX = ":order";

	private static final String META_SUFFIX = ":meta";

	/**
	 * KEYS: data HASH, order ZSET, meta HASH, truncated。
	 * ARGV: ttl, maxEntries, maxBytes, 随后为 entryId/payload/runEpoch 三元组。
	 *
	 * <p>同 entryId 幂等更新；pending/approved/terminal 分级，低等级写不能覆盖高等级写。
	 * 顺序号和 revision 在 Redis 内分配，preflight 与段序在同脚本内继承，不在读侧重放日志。
	 */
	private static final RedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
			-- TRANSCRIPT_CANONICAL_UPSERT_FENCED
			local ttl = tonumber(ARGV[1])
			local maxEntries = tonumber(ARGV[2])
			local maxBytes = tonumber(ARGV[3])
			local terminal = {
			  EXECUTED=true, REJECTED=true, REJECTED_ARGUMENTS=true, TIMEOUT=true,
			  FRONTEND_RESULT=true, FRONTEND_CANCELLED=true, CANCELLED=true, BLOCKED=true,
			  SKIPPED=true, PRECHECK_FAILED=true, SHEET_DISABLED=true
			}
			local function statusRank(status)
			  if terminal[status] then return 2 end
			  if status == 'APPROVED' then return 1 end
			  return 0
			end
			local function carryPreflight(previous, newer)
			  if type(previous) ~= 'table' or type(previous.preflight) ~= 'table' then return end
			  if type(newer.preflight) ~= 'table' then
			    newer.preflight = previous.preflight
			    return
			  end
			  for _, key in ipairs({'target', 'impact', 'conditionSummary', 'publishMode'}) do
			    if newer.preflight[key] == nil then newer.preflight[key] = previous.preflight[key] end
			  end
			end
			local function mergeState(previous, newer, sameEpoch)
			  if type(previous) ~= 'table' then return newer end
			  if type(newer) ~= 'table' then return previous end
			  local previousRank = statusRank(previous.status)
			  local newerRank = statusRank(newer.status)
			  local merged = newer
			  if sameEpoch and previousRank == 2 and previous.status ~= newer.status then
			    merged = previous
			  elseif previousRank > newerRank then
			    merged = previous
			  end
			  local out = {}
			  for key, value in pairs(merged) do out[key] = value end
			  carryPreflight(previous, out)
			  return out
			end
			local function mergeToolCalls(previous, newer)
			  if type(previous) ~= 'table' then return newer end
			  if type(newer) ~= 'table' then return previous end
			  local out = {}
			  local seen = {}
			  for _, call in ipairs(newer) do
			    table.insert(out, call)
			    if type(call) == 'table' and call.id ~= nil then seen[call.id] = true end
			  end
			  for _, call in ipairs(previous) do
			    if type(call) ~= 'table' or call.id == nil or not seen[call.id] then
			      table.insert(out, call)
			    end
			  end
			  return out
			end
			local function mergeEntry(previous, newer, sameEpoch)
			  if previous.kind == 'tool' or newer.kind == 'tool' then
			    return mergeState(previous, newer, sameEpoch)
			  end
			  if previous.stopped == true and newer.stopped ~= true then newer.stopped = true end
			  if newer.errorCode == nil then newer.errorCode = previous.errorCode end
			  if newer.errorMessage == nil then newer.errorMessage = previous.errorMessage end
			  if newer.outcome == nil then newer.outcome = previous.outcome end
			  if newer.errorCardKey == nil then newer.errorCardKey = previous.errorCardKey end
			  if newer.unconfirmed == nil then newer.unconfirmed = previous.unconfirmed end
			  if newer.segments == nil then newer.segments = previous.segments end
			  newer.toolCalls = mergeToolCalls(previous.toolCalls, newer.toolCalls)
			  if type(previous.toolCallStates) == 'table' then
			    local states = type(newer.toolCallStates) == 'table' and newer.toolCallStates or {}
			    for id, oldState in pairs(previous.toolCallStates) do
			      states[id] = mergeState(oldState, states[id], sameEpoch)
			    end
			    newer.toolCallStates = states
			  end
			  return newer
			end
			local seq = tonumber(redis.call('HGET', KEYS[3], 'seq'))
			if not seq then
			  local last = redis.call('ZREVRANGE', KEYS[2], 0, 0, 'WITHSCORES')
			  seq = #last >= 2 and tonumber(last[2]) or 0
			end
			local bytes = tonumber(redis.call('HGET', KEYS[3], 'bytes'))
			if not bytes then
			  bytes = 0
			  local values = redis.call('HVALS', KEYS[1])
			  for _, value in ipairs(values) do bytes = bytes + #value end
			end
			if bytes < 0 then bytes = maxBytes end
			for i = 4, #ARGV, 3 do
			  local id = ARGV[i]
			  local raw = ARGV[i + 1]
			  local fence = tonumber(ARGV[i + 2])
			  local previousFence = tonumber(redis.call('HGET', KEYS[3], 'fence:' .. id)) or 0
			  if fence >= previousFence then
			    local newer = cjson.decode(raw)
			    local previousRaw = redis.call('HGET', KEYS[1], id)
			    local merged = raw
			    if previousRaw then
			      local previous = cjson.decode(previousRaw)
			      merged = cjson.encode(mergeEntry(previous, newer, fence == previousFence))
			    end
			    if merged ~= previousRaw then
			      if not previousRaw then
			        seq = seq + 1
			        redis.call('ZADD', KEYS[2], seq, id)
			      end
			      redis.call('HSET', KEYS[1], id, merged)
			      redis.call('HINCRBY', KEYS[3], 'rev', 1)
			      bytes = bytes + #merged - (previousRaw and #previousRaw or 0)
			    end
			    if fence > previousFence then redis.call('HSET', KEYS[3], 'fence:' .. id, fence) end
			  end
			end
			local function trimOldest(count)
			  local ids = redis.call('ZRANGE', KEYS[2], 0, count - 1)
			  for _, id in ipairs(ids) do
			    local raw = redis.call('HGET', KEYS[1], id)
			    if raw then bytes = bytes - #raw end
			    redis.call('HDEL', KEYS[1], id)
			    redis.call('HDEL', KEYS[3], 'fence:' .. id)
			    redis.call('ZREM', KEYS[2], id)
			  end
			  if #ids > 0 then redis.call('HINCRBY', KEYS[3], 'rev', #ids) end
			  return #ids
			end
			local truncated = false
			local count = redis.call('ZCARD', KEYS[2])
			if count > maxEntries then
			  local removed = trimOldest(count - maxEntries)
			  count = count - removed
			  truncated = removed > 0
			end
			while bytes > maxBytes and count > 1 do
			  local removed = trimOldest(math.min(count - 1, 200))
			  if removed == 0 then break end
			  count = count - removed
			  truncated = true
			end
			if bytes < 0 then bytes = 0 end
			redis.call('HSET', KEYS[3], 'bytes', bytes, 'seq', seq)
			if truncated then redis.call('SET', KEYS[4], '1', 'PX', ttl) end
			for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, ttl) end
			return 1
			""", Long.class);

	/** 一页规范条目和读取版本在同一脚本内物化；latest/before 都是真实游标分页。 */
	private static final RedisScript<List> READ_PAGE_SCRIPT = new DefaultRedisScript<>("""
			-- TRANSCRIPT_READ_PAGE
			local ttl = tonumber(ARGV[1])
			local mode = ARGV[2]
			local limit = tonumber(ARGV[4])
			local flat = {}
			if limit > 0 then
			  if mode == 'before' then
			    flat = redis.call('ZREVRANGEBYSCORE', KEYS[2], '(' .. ARGV[3], '-inf',
			      'WITHSCORES', 'LIMIT', 0, limit)
			  else
			    flat = redis.call('ZREVRANGE', KEYS[2], -limit, -1, 'WITHSCORES')
			  end
			end
			local ids = {}
			local scores = {}
			for i = #flat - 1, 1, -2 do
			  table.insert(ids, flat[i])
			  table.insert(scores, flat[i + 1])
			end
			local hasMore = 0
			local nextCursor = ''
			if #ids > 0 then
			  local rank = redis.call('ZRANK', KEYS[2], ids[1])
			  if rank and rank > 0 then hasMore = 1 end
			end
			if hasMore == 1 then nextCursor = scores[1] end
			local revision = redis.call('HGET', KEYS[3], 'rev') or '0'
			local total = redis.call('ZCARD', KEYS[2])
			local truncated = redis.call('EXISTS', KEYS[4])
			for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, ttl) end
			local out = {tostring(revision), tostring(total), tostring(hasMore), nextCursor, tostring(truncated)}
			if #ids > 0 then
			  local payloads = redis.call('HMGET', KEYS[1], unpack(ids))
			  for _, payload in ipairs(payloads) do table.insert(out, payload) end
			end
			return out
			""", List.class);

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	@Autowired(required = false)
	private SessionPurgeGuard purgeGuard;

	private final Duration ttl;

	private final int maxEntries;

	private final long maxBytes;

	public ConversationTranscriptStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
		this(redis, mapper, ttl, MAX_ENTRIES, MAX_BYTES);
	}

	ConversationTranscriptStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl, int maxEntries,
			long maxBytes) {
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = ttl;
		this.maxEntries = Math.max(1, maxEntries);
		this.maxBytes = Math.max(1L, maxBytes);
	}

	public record ReadPage(List<TranscriptEntry> entries, long total, long revision, String nextCursor,
			boolean hasMore, boolean truncated) {

		public static final ReadPage EMPTY = new ReadPage(List.of(), 0L, 0L, null, false, false);

	}

	/** runEpoch 来自写者已读取的快照；旧 epoch 的迟到写不得覆盖新主人的规范条目。 */
	public boolean append(String sessionId, long tenantId, long userId, List<TranscriptEntry> entries, long runEpoch) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			log.debug("transcript append skipped: no usable identity sessionId={}", sessionId);
			return false;
		}
		List<TranscriptEntry> filtered = filter(entries);
		if (filtered.isEmpty()) {
			return true;
		}
		try {
			if (this.purgeGuard != null && this.purgeGuard.isArmed(sessionId, tenantId, userId)) {
				return false;
			}
			List<String> args = new ArrayList<>(filtered.size() * 3 + 3);
			args.add(String.valueOf(this.ttl.toMillis()));
			args.add(String.valueOf(this.maxEntries));
			args.add(String.valueOf(this.maxBytes));
			for (TranscriptEntry entry : filtered) {
				args.add(entry.entryId());
				args.add(json(entry));
				args.add(String.valueOf(Math.max(0L, runEpoch)));
			}
			return appendWithBounds(sessionId, tenantId, userId, args);
		}
		catch (RuntimeException ex) {
			if (isTransientResourceFailure(ex)) {
				throw ex;
			}
			log.warn("transcript append failed sessionId={} error={} (display history does not affect this turn)",
					sessionId, ex.getMessage());
			return false;
		}
	}

	private static boolean isTransientResourceFailure(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof DataAccessResourceFailureException || current instanceof QueryTimeoutException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	public ReadPage readPage(String sessionId, Long before, Integer limit) {
		TenantUser identity = RequestContextUtils.require();
		return readPage(sessionId, identity.tenantId(), identity.userId(), before, limit);
	}

	public ReadPage readPage(String sessionId, long tenantId, long userId, Long before, Integer limit) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId) || this.ttl == null || this.ttl.isZero()
				|| this.ttl.isNegative()) {
			return ReadPage.EMPTY;
		}
		int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, Math.max(0, limit));
		String mode = before != null ? "before" : "latest";
		List<?> raw = this.redis.execute(READ_PAGE_SCRIPT,
				List.of(dataKey(sessionId, tenantId, userId), orderKey(sessionId, tenantId, userId),
						metaKey(sessionId, tenantId, userId), truncatedKey(sessionId, tenantId, userId)),
				String.valueOf(this.ttl.toMillis()), mode, before == null ? "" : String.valueOf(before),
				String.valueOf(pageSize));
		if (raw == null || raw.size() < 5) {
			throw new IllegalStateException("transcript page returned no metadata");
		}
		List<TranscriptEntry> entries = new ArrayList<>(Math.max(0, raw.size() - 5));
		for (int i = 5; i < raw.size(); i++) {
			if (raw.get(i) instanceof String payload) {
				TranscriptEntry entry = parse(payload, sessionId);
				if (entry != null) {
					entries.add(entry);
				}
			}
		}
		return new ReadPage(List.copyOf(entries), longValue(raw.get(1)), longValue(raw.get(0)),
				String.valueOf(raw.get(3)).isBlank() ? null : String.valueOf(raw.get(3)), longValue(raw.get(2)) == 1L,
				longValue(raw.get(4)) == 1L);
	}

	public static String dataKey(String sessionId) {
		return PREFIX + TenantKeyScope.currentSegment() + SessionKeyCodec.hashTag(sessionId);
	}

	public static String dataKey(String sessionId, long tenantId, long userId) {
		return PREFIX + TenantKeyScope.segmentOf(tenantId, userId) + SessionKeyCodec.hashTag(sessionId);
	}

	public static String orderKey(String sessionId) {
		return dataKey(sessionId) + ORDER_SUFFIX;
	}

	public static String orderKey(String sessionId, long tenantId, long userId) {
		return dataKey(sessionId, tenantId, userId) + ORDER_SUFFIX;
	}

	public static String metaKey(String sessionId) {
		return dataKey(sessionId) + META_SUFFIX;
	}

	public static String metaKey(String sessionId, long tenantId, long userId) {
		return dataKey(sessionId, tenantId, userId) + META_SUFFIX;
	}

	public static String truncatedKey(String sessionId, long tenantId, long userId) {
		return dataKey(sessionId, tenantId, userId) + TRUNCATED_SUFFIX;
	}

	public static String truncatedKey(String sessionId) {
		return dataKey(sessionId) + TRUNCATED_SUFFIX;
	}

	private boolean appendWithBounds(String sessionId, long tenantId, long userId, List<String> args) {
		if (this.ttl == null || this.ttl.isZero() || this.ttl.isNegative()) {
			log.warn("transcript append skipped: app.ai.session-ttl not configured sessionId={}", sessionId);
			return false;
		}
		this.redis.execute(APPEND_SCRIPT,
				List.of(dataKey(sessionId, tenantId, userId), orderKey(sessionId, tenantId, userId),
						metaKey(sessionId, tenantId, userId), truncatedKey(sessionId, tenantId, userId)), args.toArray());
		if (this.purgeGuard != null && this.purgeGuard.isArmed(sessionId, tenantId, userId)) {
			this.redis.delete(List.of(dataKey(sessionId, tenantId, userId), orderKey(sessionId, tenantId, userId),
					metaKey(sessionId, tenantId, userId), truncatedKey(sessionId, tenantId, userId)));
			return false;
		}
		return true;
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

	private static long longValue(Object value) {
		return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
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
