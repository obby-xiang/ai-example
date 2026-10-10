package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内 {@link RunStore} 替身（帧序一致性用例的外置状态面）。
 *
 * <h2>为什么不用 {@code mock(RunStore.class)}</h2>
 * 帧协议的一半事实活在<b>外置状态</b>里：{@code appendEvent} 跳过心跳、{@code lastEventSeq}
 * 取自归档末帧（跨进程续号的锚点）、{@code pending}/{@code claimExecution} 的幂等语义。
 * 用逐方法打桩的 mock 会把这几条"存的语义"变成"我在测试里写的假设"，于是断言就只是自证。
 * 本类按真实实现的语义重写这些方法（心跳不落档、末帧取号、认领先于执行、台账即重放判据），
 * 让 {@link com.example.configmgr.ai.run.SseChatEmitter}、{@code ConfirmGate}、
 * {@code SpToolCallingManager} 跑在<b>同一份</b>语义上。
 *
 * <h2>与 Redis 实现的<b>有意</b>差异（断言前必须知道）</h2>
 * <ol>
 * <li><b>对象同一性</b>：真实 {@code pending()} 每次从 Redis JSON 反序列化出一个新对象，
 * 本替身返回<b>同一个</b>对象引用。可观测的帧内容一致（状态转换的先后语义未变），
 * 但"改一份不影响另一份"这类隔离性断言<b>不</b>在本替身的能力范围内；</li>
 * <li><b>无 TTL、无 3000 帧窗口裁剪</b>：用例里都不触及这两个边界；</li>
 * <li><b>messageJson 不落</b>：本替身不调 {@code MessageJsonCodec}（用例断言的是帧，不是历史条数）。</li>
 * </ol>
 *
 * <h2>T3-2 语义同步</h2>
 * {@code appendEvent} 与真实实现同口径跳过 {@code delta}（与心跳同待遇，只留活动戳）；
 * {@code ai:seq:<runId>} 语义由 {@link #lastIssuedSeq}/{@link #recordIssuedSeq} 的进程内表复刻
 * （发放即登记、跨实例续号可读），{@code deleteRun} 一并清表。
 */
public class InMemoryRunStore extends RunStore {

	private final Map<String, RunSnapshot> runs = new ConcurrentHashMap<>();

	private final Map<String, Map<String, PendingToolCall>> pendings = new ConcurrentHashMap<>();

	private final Map<String, Map<String, String>> claims = new ConcurrentHashMap<>();

	private final Map<String, List<Map<String, Object>>> ledger = new ConcurrentHashMap<>();

	private final Map<String, List<Map<String, Object>>> events = new ConcurrentHashMap<>();

	private final Map<String, Long> beats = new ConcurrentHashMap<>();

	/** session → 当前挂起轮（T2b-D#1 索引的进程内替身；无 TTL）。 */
	private final Map<String, String> sessionCurrent = new ConcurrentHashMap<>();

	/** T3-2：{@code ai:seq:<runId>} 的进程内替身（已发放最大业务号）。 */
	private final Map<String, Long> issuedSeqs = new ConcurrentHashMap<>();

	public InMemoryRunStore() {
		super(null, new ObjectMapper(), new AiProperties());
	}

	// ── 标识与键名（与真实实现的键结构同形） ─────────────────────────────────

	@Override
	public String instanceId() {
		return "instance-conformance";
	}

	@Override
	public String runKey(String runId) {
		return RUN_PREFIX + runId;
	}

	@Override
	public String pendingKey(String runId) {
		return PENDING_PREFIX + runId;
	}

	@Override
	public String claimKey(String runId) {
		return CLAIM_PREFIX + runId;
	}

	@Override
	public String ledgerKey(String runId) {
		return LEDGER_PREFIX + runId;
	}

	@Override
	public String eventsKey(String runId) {
		return EVENTS_PREFIX + runId;
	}

	@Override
	public String beatKey(String runId) {
		return BEAT_PREFIX + runId;
	}

	@Override
	public String seqKey(String runId) {
		return SEQ_PREFIX + runId;
	}

	// ── 快照 ────────────────────────────────────────────────────────────────

	@Override
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context == null ? AiContext.empty().toMap() : context.toMap());
		snapshot.setCreatedBy(instanceId());
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		save(snapshot);
		return snapshot;
	}

	@Override
	public void save(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		this.runs.put(snapshot.getRunId(), snapshot);
		// 与真实实现同一联动口径（T2b-D#1）：SUSPENDED 建索引、终态清索引、RUNNING 保留
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		}
		else if (RunSnapshot.DONE.equals(status) || RunSnapshot.FAILED.equals(status)
				|| RunSnapshot.CANCELLED.equals(status)) {
			clearSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		}
	}

	@Override
	public RunSnapshot get(String runId) {
		return this.runs.get(runId);
	}

	@Override
	public void deleteRun(String runId) {
		this.runs.remove(runId);
		this.pendings.remove(runId);
		this.claims.remove(runId);
		this.ledger.remove(runId);
		this.events.remove(runId);
		this.issuedSeqs.remove(runId);
	}

	@Override
	public List<String> allRunIds() {
		List<String> ids = new ArrayList<>(this.runs.keySet());
		Collections.sort(ids);
		return ids;
	}

	// ── 挂起 ────────────────────────────────────────────────────────────────

	@Override
	public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> items,
			List<Message> history, MessageJsonCodec codec) {
		for (PendingToolCall pending : items) {
			pending.setRequestedAtMs(System.currentTimeMillis());
			putPending(runId, pending);
		}
		RunSnapshot snapshot = get(runId);
		if (snapshot == null) {
			throw new IllegalStateException("挂起前找不到轮次快照 " + runId);
		}
		snapshot.setAssistantContent(assistantContent);
		snapshot.setInFlightToolCalls(new ArrayList<>(items));
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		save(snapshot);
	}

	@Override
	public void putPending(String runId, PendingToolCall pending) {
		pendingTable(runId).put(pending.getToolCallId(), pending);
	}

	@Override
	public List<PendingToolCall> pendings(String runId) {
		return new ArrayList<>(pendingTable(runId).values());
	}

	// ── session → current 轮索引（T2b-D#1 的进程内替身，语义与真实实现一致） ──

	@Override
	public void markSessionCurrent(String sessionId, String runId) {
		if (sessionId != null && runId != null) {
			this.sessionCurrent.put(sessionId, runId);
		}
	}

	@Override
	public void renewSessionCurrent(String sessionId, String runId) {
		// 无 TTL 可续；值匹配校验保留（语义对齐：不属于自己的轮不动作）
		if (runId != null && runId.equals(this.sessionCurrent.get(sessionId))) {
			this.sessionCurrent.put(sessionId, runId);
		}
	}

	@Override
	public void clearSessionCurrent(String sessionId, String runId) {
		if (runId != null && runId.equals(this.sessionCurrent.get(sessionId))) {
			this.sessionCurrent.remove(sessionId, runId);
		}
	}

	@Override
	public String sessionCurrentRunId(String sessionId) {
		return sessionId == null ? null : this.sessionCurrent.get(sessionId);
	}

	@Override
	public PendingToolCall pending(String runId, String toolCallId) {
		return pendingTable(runId).get(toolCallId);
	}

	// ── 认领与台账 ──────────────────────────────────────────────────────────

	@Override
	public boolean claimExecution(String runId, String toolCallId, String executedBy) {
		String previous = claimTable(runId).putIfAbsent(toolCallId, executedBy);
		boolean won = previous == null;
		if (won) {
			PendingToolCall pending = pending(runId, toolCallId);
			if (pending != null) {
				pending.setClaimedAtMs(System.currentTimeMillis());
				pending.setExecuted(true);
				pending.setExecutedBy(executedBy);
				putPending(runId, pending);
			}
		}
		return won;
	}

	@Override
	public String claimedBy(String runId, String toolCallId) {
		return claimTable(runId).get(toolCallId);
	}

	@Override
	public void appendLedger(String runId, PendingToolCall pending, String resultText) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("atMs", System.currentTimeMillis());
		record.put("executedBy", pending.getExecutedBy() == null ? instanceId() : pending.getExecutedBy());
		record.put("toolCallId", pending.getToolCallId());
		record.put("name", pending.getName());
		record.put("kind", pending.getKind());
		record.put("resultText", resultText);
		ledgerList(runId).add(record);
	}

	@Override
	public List<Map<String, Object>> ledger(String runId) {
		return new ArrayList<>(ledgerList(runId));
	}

	@Override
	public Map<String, Object> ledgerFor(String runId, String toolCallId) {
		if (toolCallId == null) {
			return null;
		}
		for (Map<String, Object> record : ledgerList(runId)) {
			if (toolCallId.equals(record.get("toolCallId"))) {
				return record;
			}
		}
		return null;
	}

	@Override
	public Map<String, Integer> ledgerCounts(String runId) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map<String, Object> record : ledgerList(runId)) {
			out.merge(String.valueOf(record.get("name")), 1, Integer::sum);
		}
		return out;
	}

	@Override
	public int ledgerCountSince(String runId, long sinceMs) {
		int count = 0;
		for (Map<String, Object> record : ledgerList(runId)) {
			Object atMs = record.get("atMs");
			if (atMs instanceof Number number && number.longValue() >= sinceMs) {
				count++;
			}
		}
		return count;
	}

	@Override
	public int cancelPendings(String runId) {
		int changed = 0;
		for (PendingToolCall pending : pendings(runId)) {
			if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
				continue;
			}
			pending.setStatus(PendingToolCall.CANCELLED);
			pending.setReason("RUN_CANCELLED");
			pending.setExecuted(false);
			pending.setResolvedAtMs(System.currentTimeMillis());
			pending.setResultText("本轮对话已被用户取消，该工具未执行。");
			putPending(runId, pending);
			changed++;
		}
		return changed;
	}

	// ── 帧归档（心跳/delta 不落档 + 末帧取号 + 发放序号登记，与真实实现同语义） ──

	@Override
	public void appendEvent(String runId, Map<String, Object> frame) {
		if (HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))
				|| DELTA_TYPE.equals(String.valueOf(frame.get("type")))) {
			touchActivity(runId);
			return;
		}
		Map<String, Object> copy = new LinkedHashMap<>(frame);
		// T4-3 后半：atMs 主赋值在 SseChatEmitter#frame（帧构造时刻），此处 putIfAbsent 仅为
		// 与真实 RunStore#appendEvent 的兜底语义保持镜像（直塞帧才走这里）。
		copy.putIfAbsent("atMs", System.currentTimeMillis());
		eventList(runId).add(copy);
	}

	@Override
	public void touchActivity(String runId) {
		this.beats.put(runId, System.currentTimeMillis());
	}

	@Override
	public long lastEventAtMs(String runId) {
		List<Map<String, Object>> archived = eventList(runId);
		long last = 0L;
		if (!archived.isEmpty()) {
			Object atMs = archived.get(archived.size() - 1).get("atMs");
			last = atMs instanceof Number number ? number.longValue() : 0L;
		}
		return Math.max(last, lastBeatAtMs(runId));
	}

	@Override
	public long lastBeatAtMs(String runId) {
		return this.beats.getOrDefault(runId, 0L);
	}

	@Override
	public long lastEventSeq(String runId) {
		List<Map<String, Object>> archived = eventList(runId);
		if (archived.isEmpty()) {
			return 0L;
		}
		Object seq = archived.get(archived.size() - 1).get("seq");
		return seq instanceof Number number ? number.longValue() : 0L;
	}

	@Override
	public List<Map<String, Object>> events(String runId) {
		return new ArrayList<>(eventList(runId));
	}

	@Override
	public long lastIssuedSeq(String runId) {
		return this.issuedSeqs.getOrDefault(runId, 0L);
	}

	@Override
	public void recordIssuedSeq(String runId, long seq) {
		this.issuedSeqs.merge(runId, seq, Math::max);
	}

	// ── 取证辅助（只给用例用，不属于 RunStore 契约） ─────────────────────────

	/** 归档帧的 seq（含心跳留下的空洞）。 */
	public List<Long> archivedSeqs(String runId) {
		List<Long> out = new ArrayList<>();
		for (Map<String, Object> frame : eventList(runId)) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

	/** 归档帧的类型序列。 */
	public List<String> archivedTypes(String runId) {
		List<String> out = new ArrayList<>();
		for (Map<String, Object> frame : eventList(runId)) {
			out.add(String.valueOf(frame.get("type")));
		}
		return out;
	}

	/** 直接铺一段归档（重挂/续号用例的语料）。 */
	public void seedArchive(String runId, Map<String, Object>... frames) {
		for (Map<String, Object> frame : frames) {
			eventList(runId).add(new LinkedHashMap<>(frame));
		}
	}

	/** 直接铺一条待决条目（跨进程/续跑语料的骨架）。 */
	public void seedPending(String runId, PendingToolCall pending) {
		putPending(runId, pending);
	}

	/** 直接铺一条台账记录（跨进程"已执行过"的判据）。 */
	public void seedLedger(String runId, String toolCallId, String name, String resultText) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(name);
		appendLedger(runId, pending, resultText);
	}

	/** 铺一个轮次快照（运行中）。 */
	public RunSnapshot seedRun(String runId, String sessionId) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		save(snapshot);
		return snapshot;
	}

	// ── 内部表 ──────────────────────────────────────────────────────────────

	private Map<String, PendingToolCall> pendingTable(String runId) {
		return this.pendings.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	private Map<String, String> claimTable(String runId) {
		return this.claims.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	private List<Map<String, Object>> ledgerList(String runId) {
		return this.ledger.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
	}

	private List<Map<String, Object>> eventList(String runId) {
		return this.events.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
	}

}
