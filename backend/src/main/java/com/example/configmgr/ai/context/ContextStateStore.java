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

	/**
	 * 会话内单调压缩序号（B3）：{@code HINCRBY ai:ctx:<sid> compSeq 1}。
	 *
	 * <p>单键原子递增，跨实例唯一；只在<b>真实提交</b>时调用（no-op/失败/未提交不取号）。
	 */
	long nextCompactionSeq(String sessionId);

	/**
	 * 追加一条已提交的压缩事实（B3）：{@code ai:ctx:events:<sid>} LIST，LTRIM 200，TTL 同会话。
	 *
	 * <p>单键操作（Cluster 安全）；不扫描、不跨槽。
	 */
	void appendEvent(String sessionId, Map<String, Object> event);

	/** 读已提交压缩事实（新→旧；调用方负责按 atMs/eventId 排序）。 */
	List<Map<String, Object>> events(String sessionId);

}
