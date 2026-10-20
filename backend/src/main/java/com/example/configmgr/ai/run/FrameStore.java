package com.example.configmgr.ai.run;

import java.util.List;
import java.util.Map;

/**
 * 轮次事件日志的抽象（B23L1 批① / 设计稿 §2.1）。
 *
 * <p>
 * 三个职责、四个方法：<b>取号</b>（{@link #nextSeq}）、<b>落档</b>（{@link #append}）、
 * <b>按号回读</b>（{@link #readFrom}）与<b>末号</b>（{@link #lastSeq}）。生产装配只有一个实现
 * ——{@link RedisFrameStore}（Redis Stream + {@code INCR ai:seq:<runId>}），
 * <b>单实例与多实例完全同一实现</b>（不引内存环，消灭"单机走内存、集群走 Redis"的分支）。
 *
 * <h2>为什么取号与落档分开</h2>
 * 帧的单帧字节上限（T3-10 / A19）判定口径是<b>整帧 JSON 含 {@code seq}</b> 的 UTF-8 字节数，
 * 而截断必须发生在<b>落档之前</b>（否则事件日志里躺着未截断帧，回放与实时流分叉）。
 * 于是出帧路径固定为「取号 → 注入 {@code seq} → 截断/序列化 → 落档」，
 * 取号与落档因此必须是两个可分别调用的步骤（{@code append(runId, frame) → seq} 的单方法形态
 * 会把落档提前到截断之前）。
 *
 * <h2>心跳不入日志</h2>
 * {@code heartbeat} 帧不取业务号、不落档（T7 口径原样保留）：{@link #append} 收到心跳帧是 no-op，
 * 调用方（{@link SseChatEmitter}）也不为它取号。
 *
 * <h2>序号语义</h2>
 * 业务帧 {@code seq} 自 1 起、单调、跨实例不重（Redis {@code INCR} 保证）；
 * 心跳有独立号空间（{@code heartbeatSeq}），与事件日志无关。
 */
public interface FrameStore {

	/** 唤醒批量上限（每次 wake 最多补多少帧；正常远小于事件日志窗口）。 */
	int WAKE_BATCH_LIMIT = 6000;

	/**
	 * 取下一个业务序号（并把它交给调用方注入帧体）。
	 *
	 * <p>生产实现 = {@code INCR ai:seq:<runId>}（Redis 原子，跨实例不重不回退）；失败时降级为
	 * 进程内单调计数（仅 WARN，不中断出帧 —— 与既有 S2-4"韧性优先"同口径；降级期间与已发放号
	 * 空间可能重叠，登记为 Redis 整体不可用下的观察项，不是续号锚点）。
	 */
	long nextSeq(String runId);

	/**
	 * 落档一帧（帧体已带 {@code seq}）。心跳帧 no-op。写失败只记日志，不抛出 ——
	 * 外置是"更耐久"，不是"更正确"的前提（与 B1 起 {@code RunStore#appendEvent} 同口径）。
	 */
	void append(String runId, Map<String, Object> frame);

	/**
	 * 按序号回读：返回 {@code seq > afterSeq} 的帧（升序）。
	 *
	 * <p>兼容期双读：先读 LIST 尾巴（{@code ai:events:<runId>}，只读），再读 Stream，
	 * 按 {@code seq} 拼接去重（同号以 Stream 为准）；<b>没有 {@code seq} 的老帧一律保留</b>
	 * （无法判定孤儿，宁可不发不可丢帧，与 {@code isOrphan} 同口径）。
	 *
	 * @param afterSeq 前端已知的最大序号（0 = 全量）
	 * @param limit    最多返回多少帧；{@code <= 0} = 不限（回放批用）
	 */
	List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit);

	/** 事件日志里<b>已落档</b>的最大序号（无帧 = 0）。注意：不含"已发放但落档失败"的号。 */
	long lastSeq(String runId);

}
