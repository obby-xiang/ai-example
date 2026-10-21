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
		this.states.put(sessionId, state.toFields());
	}

	@Override
	public void appendLedger(String sessionId, Map<String, Object> event) {
		this.ledgerCount++;
		this.ledgers.computeIfAbsent(sessionId, key -> new ArrayList<>()).add(0, event);
	}

	@Override
	public List<Map<String, Object>> contextLedger(String sessionId) {
		return this.ledgers.getOrDefault(sessionId, List.of());
	}

	@Override
	public void archive(String sessionId, int turnNo, List<String> messageJson) {
		this.archiveCount++;
		this.archives.put(sessionId + ":" + turnNo, messageJson);
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
