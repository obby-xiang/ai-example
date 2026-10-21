package com.example.configmgr.ai.context;

import java.util.List;
import java.util.Map;

/**
 * 上下文记账的存储面（设计稿 §3.6 / §4 的三个键族）。
 *
 * <p>
 * 生产实现是 {@code RunStore}（键族与既有 run 级外置键同住一处：{@code ai:ctx:<sessionId>} /
 * {@code ai:ctx:ledger:<sessionId>} / {@code ai:ctx:archive:<sessionId>:<turnNo>}，
 * TTL 一律 {@code app.ai.session-ttl} 且随读写滑动）。抽成接口是为了让压缩逻辑可单测 ——
 * 压缩判据、归档顺序、熔断计数都是纯逻辑，不该为了测它们起 Spring + Redis。
 */
public interface ContextStateStore {

	/** 读会话状态（键不存在 ⇒ {@link ContextState#EMPTY}）。 */
	ContextState read(String sessionId);

	/** 整份覆盖写（HSET + EXPIRE）。 */
	void write(String sessionId, ContextState state);

	/** 台账追加一条压缩事件（LIST，最近 20 条，TTL 同会话）。 */
	void appendLedger(String sessionId, Map<String, Object> event);

	/** 台账读回（新→旧；运维面经端点读）。 */
	List<Map<String, Object>> contextLedger(String sessionId);

	/** 压缩前原文归档（{@code ai:ctx:archive:<sessionId>:<turnNo>}，TTL 同会话）。 */
	void archive(String sessionId, int turnNo, List<String> messageJson);

}
