package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.SessionActivityRenewal;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisContextStateStore implements ContextStateStore {

	private static final String CONTEXT_PREFIX = "ai:ctx:";
	private static final String CONTEXT_LEDGER_PREFIX = "ai:ctx:ledger:";
	private static final String CONTEXT_EVENTS_PREFIX = "ai:ctx:events:";
	private static final int CONTEXT_LEDGER_MAX = 20;
	private static final int CONTEXT_EVENTS_MAX = 200;

	private static final RedisScript<Long> CAS_WRITE = new DefaultRedisScript<>("""
			local current = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
			local expected = tonumber(ARGV[1])
			local compactionId = ARGV[3]
			if current ~= expected then
				if compactionId ~= '' and redis.call('HGET', KEYS[1], 'lastCompactionId') == compactionId then
					redis.call('PEXPIRE', KEYS[1], ARGV[2])
					return 2
				end
				return 0
			end
			for i = 5, #ARGV, 2 do
				redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
			end
			redis.call('HSET', KEYS[1], 'version', current + 1)
			if compactionId ~= '' then
				redis.call('HSET', KEYS[1], 'lastCompactionId', compactionId, 'pendingCompaction', ARGV[4])
			end
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	private static final RedisScript<Long> RECORD_TURN = new DefaultRedisScript<>("""
			redis.call('HSET', KEYS[1],
				'used', ARGV[1], 'completion', ARGV[2], 'est', ARGV[3], 'atMs', ARGV[4],
				'systemTokens', ARGV[5], 'toolsTokens', ARGV[6], 'inputTokens', ARGV[7])
			if ARGV[8] ~= '' then redis.call('HSET', KEYS[1], 'model', ARGV[8]) end
			if ARGV[9] ~= '' then redis.call('HSET', KEYS[1], 'providerId', ARGV[9]) end
			redis.call('HINCRBY', KEYS[1], 'turnNo', 1)
			redis.call('HINCRBY', KEYS[1], 'version', 1)
			redis.call('PEXPIRE', KEYS[1], ARGV[10])
			return 1
			""", Long.class);

	private static final RedisScript<List> RECORD_FUSE_FAILURE = new DefaultRedisScript<>("""
			local fails = redis.call('HINCRBY', KEYS[1], 'fails', 1)
			local circuit = 'closed'
			local openUntil = 0
			if fails >= tonumber(ARGV[1]) then
				circuit = 'open'
				openUntil = tonumber(ARGV[2]) + tonumber(ARGV[3])
			end
			redis.call('HSET', KEYS[1], 'circuit', circuit, 'openUntil', openUntil)
			local version = redis.call('HINCRBY', KEYS[1], 'version', 1)
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			return {circuit, tostring(fails), tostring(openUntil), tostring(version)}
			""", List.class);

	private static final RedisScript<Long> APPEND_LEDGER = new DefaultRedisScript<>("""
			local payload = ARGV[1]
			local ok, decoded = pcall(cjson.decode, payload)
			if ok and decoded['commitEntryId'] then
				local existing = redis.call('LRANGE', KEYS[1], 0, tonumber(ARGV[2]) - 1)
				for _, item in ipairs(existing) do
					local itemOk, itemDecoded = pcall(cjson.decode, item)
					if itemOk and itemDecoded['commitEntryId'] == decoded['commitEntryId'] then
						redis.call('PEXPIRE', KEYS[1], ARGV[3])
						return 1
					end
				end
			end
			redis.call('LPUSH', KEYS[1], payload)
			redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
			redis.call('PEXPIRE', KEYS[1], ARGV[3])
			return 1
			""", Long.class);

	private static final RedisScript<Long> APPEND_EVENT = new DefaultRedisScript<>("""
			local payload = ARGV[1]
			local ok, decoded = pcall(cjson.decode, payload)
			if ok and decoded['eventId'] then
				local existing = redis.call('LRANGE', KEYS[1], 0, tonumber(ARGV[2]) - 1)
				for _, item in ipairs(existing) do
					local itemOk, itemDecoded = pcall(cjson.decode, item)
					if itemOk and itemDecoded['eventId'] == decoded['eventId'] then
						redis.call('PEXPIRE', KEYS[1], ARGV[3])
						return 1
					end
				end
			end
			redis.call('LPUSH', KEYS[1], payload)
			redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
			redis.call('PEXPIRE', KEYS[1], ARGV[3])
			return 1
			""", Long.class);

	private static final RedisScript<Long> COMPLETE_COMPACTION = new DefaultRedisScript<>("""
			local raw = redis.call('HGET', KEYS[1], 'pendingCompaction')
			if raw then
				local ok, pending = pcall(cjson.decode, raw)
				if ok and pending['compactionId'] == ARGV[1] then
					redis.call('HDEL', KEYS[1], 'pendingCompaction')
				end
			end
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final AiProperties properties;

	private SessionActivityRenewal activityRenewal;

	@Autowired(required = false)
	void setActivityRenewal(SessionActivityRenewal activityRenewal) {
		this.activityRenewal = activityRenewal;
	}

	@Override
	public ContextState read(String sessionId) {
		return readStrict(sessionId);
	}

	@Override
	public ContextState readStrict(String sessionId) {
		String key = contextKey(sessionId);
		Map<Object, Object> raw = this.redis.opsForHash().entries(key);
		if (MapUtils.isEmpty(raw)) {
			return ContextState.EMPTY;
		}
		if (this.activityRenewal == null) {
			touchQuietly(key);
		}
		Map<String, String> fields = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> entry : raw.entrySet()) {
			fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		return ContextState.fromFields(fields);
	}

	@Override
	public void write(String sessionId, ContextState state) {
		if (writeState(sessionId, state, "", "") != 1L) {
			throw new IllegalStateException("Context state version CAS conflict sessionId=" + sessionId);
		}
	}

	@Override
	public ContextState compareAndSet(String sessionId, ContextState replacement) {
		if (writeState(sessionId, replacement, "", "") != 1L) {
			return null;
		}
		return replacement.withVersion(replacement.version() + 1L);
	}

	private long writeState(String sessionId, ContextState state, String compactionId, String pendingJson) {
		ContextState base = ObjectUtils.defaultIfNull(state, ContextState.EMPTY);
		List<String> args = new ArrayList<>();
		args.add(String.valueOf(base.version()));
		args.add(String.valueOf(ttlMillis()));
		args.add(compactionId);
		args.add(pendingJson);
		base.toFields().forEach((field, value) -> {
			args.add(field);
			args.add(value);
		});
		Long applied = this.redis.execute(CAS_WRITE, List.of(contextKey(sessionId)), args.toArray());
		return ObjectUtils.defaultIfNull(applied, 0L);
	}

	@Override
	public void recordTurn(String sessionId, Long providerId, String model, int usedTokens, int completionTokens,
			int turnNo, boolean estimated, long atMs, int systemTokens, int toolsTokens, int inputTokens) {
		this.redis.execute(RECORD_TURN, List.of(contextKey(sessionId)), String.valueOf(Math.max(0, usedTokens)),
				String.valueOf(Math.max(0, completionTokens)), String.valueOf(estimated), String.valueOf(atMs),
				String.valueOf(Math.max(0, systemTokens)), String.valueOf(Math.max(0, toolsTokens)),
				String.valueOf(Math.max(0, inputTokens)), StringUtils.defaultString(model),
				providerId == null || providerId <= 0L ? "" : String.valueOf(providerId), String.valueOf(ttlMillis()));
	}

	@Override
	public ContextState recordFuseFailure(String sessionId, ContextState failed, int threshold, long cooldownMs,
			long atMs) {
		@SuppressWarnings("unchecked")
		List<Object> result = this.redis.execute(RECORD_FUSE_FAILURE, List.of(contextKey(sessionId)),
				String.valueOf(Math.max(1, threshold)), String.valueOf(atMs), String.valueOf(Math.max(0L, cooldownMs)),
				String.valueOf(ttlMillis()));
		if (result == null || result.size() != 4) {
			throw new IllegalStateException("Incomplete summary circuit-breaker counter result sessionId=" + sessionId);
		}
		return failed.withFuse(String.valueOf(result.getFirst()), parseInt(result.get(1)), parseLong(result.get(2)))
				.withVersion(parseLong(result.get(3)));
	}

	@Override
	public CompactionCommitStatus commitCompaction(String sessionId, String compactionId, ContextState compacted,
			List<Map<String, Object>> ledgerEvents, Map<String, Object> event) {
		PendingCompaction pending = new PendingCompaction(compactionId, ledgerEvents, event);
		long cas = writeState(sessionId, compacted, compactionId, pendingJson(pending));
		if (cas == 0L) {
			return CompactionCommitStatus.CONFLICT;
		}
		if (cas == 2L) {
			String raw = pendingRaw(sessionId);
			if (raw == null) {
				return CompactionCommitStatus.ALREADY_APPLIED;
			}
			pending = readPending(raw);
		}
		appendFacts(sessionId, pending);
		completeCompaction(sessionId, compactionId);
		return cas == 1L ? CompactionCommitStatus.APPLIED : CompactionCommitStatus.ALREADY_APPLIED;
	}

	@Override
	public boolean reconcileCompaction(String sessionId) {
		String raw = pendingRaw(sessionId);
		if (raw == null) {
			return false;
		}
		PendingCompaction pending = readPending(raw);
		appendFacts(sessionId, pending);
		completeCompaction(sessionId, pending.compactionId());
		return true;
	}

	private void appendFacts(String sessionId, PendingCompaction pending) {
		for (Map<String, Object> ledgerEvent : pending.ledgerEvents()) {
			appendLedger(sessionId, ledgerEvent);
		}
		appendEvent(sessionId, pending.event());
	}

	private String pendingRaw(String sessionId) {
		Object raw = this.redis.opsForHash().get(contextKey(sessionId), "pendingCompaction");
		return raw == null ? null : String.valueOf(raw);
	}

	private String pendingJson(PendingCompaction pending) {
		try {
			return this.mapper.writeValueAsString(pending);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize pending compaction", ex);
		}
	}

	private PendingCompaction readPending(String raw) {
		try {
			return this.mapper.readValue(raw, PendingCompaction.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to read pending compaction", ex);
		}
	}

	private void completeCompaction(String sessionId, String compactionId) {
		this.redis.execute(COMPLETE_COMPACTION, List.of(contextKey(sessionId)), compactionId, String.valueOf(ttlMillis()));
	}

	private record PendingCompaction(@JsonProperty("compactionId") String compactionId,
			@JsonProperty("ledgerEvents") @NonNull List<Map<String, Object>> ledgerEvents,
			@JsonProperty("event") @NonNull Map<String, Object> event) {

		public PendingCompaction {
			ledgerEvents = ledgerEvents == null ? List.of() : List.copyOf(ledgerEvents);
			event = Collections.unmodifiableMap(new LinkedHashMap<>(event == null ? Map.of() : event));
		}
	}

	@Override
	public void appendLedger(String sessionId, Map<String, Object> event) {
		try {
			this.redis.execute(APPEND_LEDGER, List.of(contextLedgerKey(sessionId)), this.mapper.writeValueAsString(event),
					String.valueOf(CONTEXT_LEDGER_MAX), String.valueOf(ttlMillis()));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to write context ledger sessionId=" + sessionId, ex);
		}
	}

	@Override
	public List<Map<String, Object>> contextLedger(String sessionId) {
		return readEvents(sessionId, contextLedgerKey(sessionId), CONTEXT_LEDGER_MAX, false);
	}

	@Override
	public void appendEvent(String sessionId, Map<String, Object> event) {
		try {
			this.redis.execute(APPEND_EVENT, List.of(contextEventsKey(sessionId)), this.mapper.writeValueAsString(event),
					String.valueOf(CONTEXT_EVENTS_MAX), String.valueOf(ttlMillis()));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to write compaction event sessionId=" + sessionId, ex);
		}
	}

	@Override
	public List<Map<String, Object>> events(String sessionId) {
		return readEvents(sessionId, contextEventsKey(sessionId), CONTEXT_EVENTS_MAX, true);
	}

	private List<Map<String, Object>> readEvents(String sessionId, String key, int limit, boolean touch) {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			List<String> raw = this.redis.opsForList().range(key, 0, limit - 1L);
			if (raw != null) {
				for (String item : raw) {
					out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() { }));
				}
			}
			if (touch && !out.isEmpty() && this.activityRenewal != null) {
				TenantUser identity = RequestContextUtils.currentOrNull();
				if (identity != null) {
					this.activityRenewal.isActivityRenewed(identity.tenantId(), identity.userId(), sessionId, null, key);
				}
			}
			else if (touch && this.activityRenewal == null) {
				touchQuietly(key);
			}
		}
		catch (Exception ex) {
			log.warn("context event read failed key={} (treating as empty)", key, ex);
		}
		return out.isEmpty() ? List.of() : out;
	}

	private String contextKey(String sessionId) {
		return TenantKeyScope.scoped(CONTEXT_PREFIX, sessionId);
	}

	private String contextLedgerKey(String sessionId) {
		return TenantKeyScope.scoped(CONTEXT_LEDGER_PREFIX, sessionId);
	}

	private String contextEventsKey(String sessionId) {
		return TenantKeyScope.scoped(CONTEXT_EVENTS_PREFIX, sessionId);
	}

	private long ttlMillis() {
		return this.properties.getEffectiveSessionTtl().toMillis();
	}

	private void touchQuietly(String key) {
		try {
			this.redis.expire(key, this.properties.getEffectiveSessionTtl());
		}
		catch (RuntimeException ex) {
			// 续期失败不影响本次读取，后续请求会重试。
		}
	}

	private static int parseInt(Object raw) {
		return Integer.parseInt(String.valueOf(raw));
	}

	private static long parseLong(Object raw) {
		return Long.parseLong(String.valueOf(raw));
	}

}
