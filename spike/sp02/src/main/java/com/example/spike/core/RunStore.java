package com.example.spike.core;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 挂起态的 Redis 外置存储（SP-02 的“外置”本体）。
 *
 * <p>
 * 键结构（全部带 TTL，前缀 {@code sp02:}）：
 * <ul>
 * <li>{@code sp02:run:<runId>} STRING —— {@link RunSnapshot} JSON（会话上下文 + 挂起中的
 * assistant(tool_calls) + 状态）；</li>
 * <li>{@code sp02:runs} SET —— 全部 runId 索引（重启后“发现待续跑轮次”的入口）；</li>
 * <li>{@code sp02:hitl:<runId>} HASH —— field=toolCallId，value={@link PendingToolCall} JSON
 * （前端/确认门的输入落点，重启后仍可读）；</li>
 * <li>{@code sp02:ledger:<runId>} LIST —— 工具执行台账（每次真实执行一条，含 executedBy 实例标识），
 * 用于证明“不重放”；</li>
 * <li>{@code sp02:events:<runId>} LIST —— 事件时序外置副本（崩溃前的 SSE 事件不随进程消失）。</li>
 * </ul>
 */
public class RunStore {

	public static final String RUN_PREFIX = "sp02:run:";

	public static final String RUN_INDEX = "sp02:runs";

	public static final String HITL_PREFIX = "sp02:hitl:";

	public static final String LEDGER_PREFIX = "sp02:ledger:";

	public static final String EVENTS_PREFIX = "sp02:events:";

	/** 本实例标识：证明某次执行发生在“重启后的新进程”里。 */
	private final String instanceId;

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final Duration ttl;

	public RunStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = ttl;
		this.instanceId = "instance-" + host() + "-pid" + ProcessHandle.current().pid() + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
	}

	private static String host() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "unknown";
		}
	}

	public String instanceId() {
		return this.instanceId;
	}

	public String runKey(String runId) {
		return RUN_PREFIX + runId;
	}

	public String hitlKey(String runId) {
		return HITL_PREFIX + runId;
	}

	public String ledgerKey(String runId) {
		return LEDGER_PREFIX + runId;
	}

	public String eventsKey(String runId) {
		return EVENTS_PREFIX + runId;
	}

	// ---------------- run snapshot ----------------

	public RunSnapshot create(String runId, String sessionId, String mode, String systemPrompt) {
		RunSnapshot snap = new RunSnapshot();
		snap.runId = runId;
		snap.sessionId = sessionId;
		snap.mode = mode;
		snap.systemPrompt = systemPrompt;
		snap.createdBy = this.instanceId;
		snap.createdAtMs = System.currentTimeMillis();
		snap.updatedAtMs = snap.createdAtMs;
		save(snap);
		this.redis.opsForSet().add(RUN_INDEX, runId);
		return snap;
	}

	public void save(RunSnapshot snap) {
		snap.updatedAtMs = System.currentTimeMillis();
		try {
			this.redis.opsForValue().set(runKey(snap.runId), this.mapper.writeValueAsString(snap), this.ttl);
		}
		catch (Exception ex) {
			throw new IllegalStateException("cannot persist run snapshot", ex);
		}
	}

	public RunSnapshot get(String runId) {
		String raw = this.redis.opsForValue().get(runKey(runId));
		if (raw == null) {
			return null;
		}
		try {
			return this.mapper.readValue(raw, RunSnapshot.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("cannot read run snapshot " + runId, ex);
		}
	}

	/** 删除单轮的全部键（池饱和回滚时使用）。 */
	public void deleteRun(String runId) {
		this.redis.delete(List.of(runKey(runId), hitlKey(runId), ledgerKey(runId), eventsKey(runId)));
		this.redis.opsForSet().remove(RUN_INDEX, runId);
	}

	public List<String> allRunIds() {		Set<String> ids = this.redis.opsForSet().members(RUN_INDEX);
		if (ids == null) {
			return List.of();
		}
		List<String> live = new ArrayList<>();
		List<String> dead = new ArrayList<>();
		for (String id : ids) {
			if (Boolean.TRUE.equals(this.redis.hasKey(runKey(id)))) {
				live.add(id);
			}
			else {
				dead.add(id);
			}
		}
		if (!dead.isEmpty()) {
			this.redis.opsForSet().remove(RUN_INDEX, dead.toArray());
		}
		live.sort(String::compareTo);
		return live;
	}

	// ---------------- hitl / pending ----------------

	public void putPending(String runId, PendingToolCall item) {
		try {
			this.redis.opsForHash().put(hitlKey(runId), item.toolCallId, this.mapper.writeValueAsString(item));
			this.redis.expire(hitlKey(runId), this.ttl);
		}
		catch (Exception ex) {
			throw new IllegalStateException("cannot persist pending tool call", ex);
		}
	}

	public List<PendingToolCall> pendings(String runId) {
		Map<Object, Object> all = this.redis.opsForHash().entries(hitlKey(runId));
		List<PendingToolCall> out = new ArrayList<>();
		for (Object value : all.values()) {
			try {
				out.add(this.mapper.readValue(String.valueOf(value), PendingToolCall.class));
			}
			catch (Exception ex) {
				throw new IllegalStateException("cannot read pending tool call", ex);
			}
		}
		return out;
	}

	public PendingToolCall pending(String runId, String toolCallId) {
		for (PendingToolCall item : pendings(runId)) {
			if (item.toolCallId.equals(toolCallId)) {
				return item;
			}
		}
		return null;
	}

	// ---------------- ledger ----------------

	public void appendLedger(String runId, String toolCallId, String name, String kind, String resultText) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("atMs", System.currentTimeMillis());
		record.put("at", Instant.now().toString());
		record.put("executedBy", this.instanceId);
		record.put("toolCallId", toolCallId);
		record.put("name", name);
		record.put("kind", kind);
		record.put("resultText", resultText);
		try {
			this.redis.opsForList().rightPush(ledgerKey(runId), this.mapper.writeValueAsString(record));
			this.redis.expire(ledgerKey(runId), this.ttl);
		}
		catch (Exception ex) {
			throw new IllegalStateException("cannot append ledger", ex);
		}
	}

	public List<Map<String, Object>> ledger(String runId) {
		List<String> raw = this.redis.opsForList().range(ledgerKey(runId), 0, -1);
		List<Map<String, Object>> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (String item : raw) {
			try {
				out.add(this.mapper.readValue(item, Map.class));
			}
			catch (Exception ex) {
				throw new IllegalStateException("cannot read ledger", ex);
			}
		}
		return out;
	}

	/** 某个 toolCallId 是否已经真实执行过（跨进程口径）。 */
	public Map<String, Object> ledgerFor(String runId, String toolCallId) {
		for (Map<String, Object> record : ledger(runId)) {
			if (toolCallId != null && toolCallId.equals(record.get("toolCallId"))) {
				return record;
			}
		}
		return null;
	}

	public Map<String, Integer> ledgerCounts(String runId) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map<String, Object> record : ledger(runId)) {
			String name = String.valueOf(record.get("name"));
			out.merge(name, 1, Integer::sum);
		}
		return out;
	}

	// ---------------- events ----------------

	public void appendEvent(String runId, Map<String, Object> event) {
		try {
			this.redis.opsForList().rightPush(eventsKey(runId), this.mapper.writeValueAsString(event));
			// 保留足够长的时序（流式 delta 事件很多，窗口太小会把挂起期事件挤掉，导致事后无法取证）
			this.redis.opsForList().trim(eventsKey(runId), -3000, -1);
			this.redis.expire(eventsKey(runId), this.ttl);
		}
		catch (Exception ex) {
			// 事件留档失败不应影响主流程
		}
	}

	public List<Map<String, Object>> events(String runId) {
		List<String> raw = this.redis.opsForList().range(eventsKey(runId), 0, -1);
		List<Map<String, Object>> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (String item : raw) {
			try {
				out.add(this.mapper.readValue(item, Map.class));
			}
			catch (Exception ignored) {
			}
		}
		return out;
	}

}
