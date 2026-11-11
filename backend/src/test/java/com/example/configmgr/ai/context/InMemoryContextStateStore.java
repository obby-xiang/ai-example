package com.example.configmgr.ai.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文记账的**内存实现**（单测替身）。
 *
 * <p>
 * 生产实现是 {@code RunStore}（Redis 三键族）。这里用同一份 {@link ContextState} 编解码来回跑
 * （{@link ContextState#toFields()}/{@link ContextState#fromFields}）—— 于是"字段名写错/漏写"这类
 * 缺陷在单测里就能红，而不必等到起 Redis 才发现。
 */
public class InMemoryContextStateStore implements ContextStateStore {

	private final Map<String, Map<String, String>> states = new LinkedHashMap<>();

	private final Map<String, List<Map<String, Object>>> ledgers = new LinkedHashMap<>();

	private final Map<String, List<String>> archives = new LinkedHashMap<>();

	private final Map<String, Long> compSeqs = new LinkedHashMap<>();

	private final Map<String, List<Map<String, Object>>> events = new LinkedHashMap<>();

	/** 最近一次已应用的压缩提交 id（生产只记单值 lastCompactionId；旧 id 重放不得被当成幂等）。 */
	private final Map<String, String> lastCompactionId = new LinkedHashMap<>();

	/** 待补写的压缩事实（模拟"状态 CAS 已提交、台账/事件写失败"的 pending 语义）。 */
	private final Map<String, List<Map<String, Object>>> pendingLedger = new LinkedHashMap<>();

	private final Map<String, List<Map<String, Object>>> pendingEvents = new LinkedHashMap<>();

	/** 归档写入次数（"未压缩就不归档"的判据）。 */
	private int archiveCount;

	/** 台账写入次数。 */
	private int ledgerCount;

	@Override
	public ContextState read(String sessionId) {
		return ContextState.fromFields(this.states.get(sessionId));
	}

	@Override
	public void write(String sessionId, ContextState state) {
		ContextState current = readStrict(sessionId);
		if (state == null || current.version() != state.version()) {
			throw new IllegalStateException("Context state version CAS conflict sessionId=" + sessionId);
		}
		this.states.put(sessionId, state.withVersion(state.version() + 1L).toFields());
	}

	@Override
	public void appendLedger(String sessionId, Map<String, Object> event) {
		Object entryId = event == null ? null : event.get("commitEntryId");
		List<Map<String, Object>> ledger = this.ledgers.computeIfAbsent(sessionId, key -> new ArrayList<>());
		if (entryId != null) {
			for (Map<String, Object> existing : ledger) {
				if (entryId.equals(existing.get("commitEntryId"))) {
					return;
				}
			}
		}
		this.ledgerCount++;
		ledger.add(0, event);
		while (ledger.size() > 20) {
			ledger.remove(ledger.size() - 1);
		}
	}

	@Override
	public List<Map<String, Object>> contextLedger(String sessionId) {
		return this.ledgers.getOrDefault(sessionId, List.of());
	}

	public long nextCompactionSeq(String sessionId) {
		long next = this.compSeqs.getOrDefault(sessionId, 0L) + 1L;
		this.compSeqs.put(sessionId, next);
		return next;
	}

	@Override
	public ContextState readStrict(String sessionId) {
		return ContextState.fromFields(this.states.get(sessionId));
	}

	@Override
	public ContextState compareAndSet(String sessionId, ContextState replacement) {
		ContextState current = readStrict(sessionId);
		if (current.version() != replacement.version()) {
			return null;
		}
		ContextState next = replacement.withVersion(replacement.version() + 1L);
		this.states.put(sessionId, next.toFields());
		return next;
	}

	@Override
	public void recordTurn(String sessionId, Long providerId, String model, int usedTokens, int completionTokens,
			int turnNo, boolean estimated, long atMs, int systemTokens, int toolsTokens, int inputTokens) {
		// 对齐 RECORD_TURN：used/completion/system/tools/input 为绝对值 HSET；turnNo 与 version 为 HINCRBY；
		// providerId 与 model 成对写入（端点按 (providerId, model) 解析分母，不能丢身份）。
		ContextState current = readStrict(sessionId);
		ContextState next = current.withUsage(Math.max(0, usedTokens), Math.max(0, completionTokens),
				current.turnNo() + 1, estimated, atMs, model, providerId, Math.max(0, systemTokens),
				Math.max(0, toolsTokens), Math.max(0, inputTokens)).withVersion(current.version() + 1L);
		this.states.put(sessionId, next.toFields());
	}

	@Override
	public ContextState recordFuseFailure(String sessionId, ContextState failed, int threshold, long cooldownMs,
			long atMs) {
		ContextState base = failed == null ? readStrict(sessionId) : failed;
		int fails = base.fuseFails() + 1;
		String circuit = fails >= Math.max(1, threshold) ? "open" : base.circuit();
		long openUntil = fails >= Math.max(1, threshold) ? atMs + Math.max(0L, cooldownMs) : base.openUntilMs();
		ContextState next = base.withFuse(circuit, fails, openUntil).withVersion(base.version() + 1L);
		this.states.put(sessionId, next.toFields());
		return next;
	}

	@Override
	public CompactionCommitStatus commitCompaction(String sessionId, String compactionId, ContextState compacted,
			List<Map<String, Object>> ledgerEvents, Map<String, Object> event) {
		if (compactionId != null && compactionId.equals(this.lastCompactionId.get(sessionId))) {
			// 同一压缩 id 重试：只补写缺失事实，状态不重复推进（对齐 CAS 返回 2 的 ALREADY_APPLIED）
			applyFacts(sessionId, ledgerEvents, event);
			return CompactionCommitStatus.ALREADY_APPLIED;
		}
		ContextState current = readStrict(sessionId);
		if (compacted == null || current.version() != compacted.version()) {
			return CompactionCommitStatus.CONFLICT;
		}
		this.states.put(sessionId, compacted.withVersion(compacted.version() + 1L).toFields());
		this.lastCompactionId.put(sessionId, compactionId);
		try {
			applyFacts(sessionId, ledgerEvents, event);
			clearPending(sessionId);
		} catch (RuntimeException ex) {
			// 与生产一致：状态已 CAS，事实留在 pending 里由 reconcile 补齐；失败向上抛
			this.pendingLedger.put(sessionId, ledgerEvents == null ? List.of() : new ArrayList<>(ledgerEvents));
			this.pendingEvents.put(sessionId, event == null ? List.of() : new ArrayList<>(List.of(event)));
			throw ex;
		}
		return CompactionCommitStatus.APPLIED;
	}

	@Override
	public boolean reconcileCompaction(String sessionId) {
		List<Map<String, Object>> ledger = this.pendingLedger.get(sessionId);
		List<Map<String, Object>> pendingEvent = this.pendingEvents.get(sessionId);
		if ((ledger == null || ledger.isEmpty()) && (pendingEvent == null || pendingEvent.isEmpty())) {
			return false;
		}
		applyFacts(sessionId, ledger, pendingEvent == null || pendingEvent.isEmpty() ? null : pendingEvent.get(0));
		clearPending(sessionId);
		return true;
	}

	private void applyFacts(String sessionId, List<Map<String, Object>> ledgerEvents, Map<String, Object> event) {
		for (Map<String, Object> ledgerEvent : ledgerEvents == null ? List.<Map<String, Object>>of() : ledgerEvents) {
			appendLedger(sessionId, ledgerEvent);
		}
		if (event != null) {
			appendEvent(sessionId, event);
		}
	}

	private void clearPending(String sessionId) {
		this.pendingLedger.remove(sessionId);
		this.pendingEvents.remove(sessionId);
	}

	@Override
	public void appendEvent(String sessionId, Map<String, Object> event) {
		Object eventId = event == null ? null : event.get("eventId");
		List<Map<String, Object>> bucket = this.events.computeIfAbsent(sessionId, key -> new ArrayList<>());
		if (eventId != null) {
			for (Map<String, Object> existing : bucket) {
				if (eventId.equals(existing.get("eventId"))) {
					return;
				}
			}
		}
		bucket.add(0, event);
	}

	@Override
	public List<Map<String, Object>> events(String sessionId) {
		return this.events.getOrDefault(sessionId, List.of());
	}

	public int archiveCount() {
		return this.archiveCount;
	}

	public int ledgerCount() {
		return this.ledgerCount;
	}

	public List<String> archived(String sessionId, int turnNo) {
		return this.archives.get(sessionId + ":" + turnNo);
	}

}
