package com.example.configmgr.ai.context;

import java.util.List;
import java.util.Map;

/** 当前上下文记账协议；生产实现为 {@link RedisContextStateStore}。 */
public interface ContextStateStore {

	ContextState read(String sessionId);

	void write(String sessionId, ContextState state);

	void appendLedger(String sessionId, Map<String, Object> event);

	List<Map<String, Object>> contextLedger(String sessionId);

	void appendEvent(String sessionId, Map<String, Object> event);

	List<Map<String, Object>> events(String sessionId);

	ContextState readStrict(String sessionId);

	ContextState compareAndSet(String sessionId, ContextState replacement);

	void recordTurn(String sessionId, Long providerId, String model, int usedTokens, int completionTokens,
			int turnNo, boolean estimated, long atMs, int systemTokens, int toolsTokens, int inputTokens);

	ContextState recordFuseFailure(String sessionId, ContextState failed, int threshold, long cooldownMs, long atMs);

	enum CompactionCommitStatus {
		APPLIED,
		ALREADY_APPLIED,
		CONFLICT
	}

	CompactionCommitStatus commitCompaction(String sessionId, String compactionId, ContextState compacted,
			List<Map<String, Object>> ledgerEvents, Map<String, Object> event);

	boolean reconcileCompaction(String sessionId);

}
