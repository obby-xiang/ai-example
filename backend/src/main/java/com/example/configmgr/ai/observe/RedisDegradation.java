package com.example.configmgr.ai.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 降级事件的<b>统一计数面</b>（Cluster / 5.0 可观测批）。
 *
 * <p>背景：S7 报告把 31 处 catch 按"异常被翻译成什么"分级，A 级六处会把"读不出来"翻译成
 * 空/0/null —— 语义上等于"没有数据"。这些降级不能都抛异常（挂起重连、唤醒补帧、看门狗节拍
 * 都不能被一次抖动打断），但也不能只有一行日志：日志会被轮询节拍刷掉，且无法按事件聚合。
 *
 * <p>本类只做两件事：<b>按事件名累加次数</b>与记录最近一次发生时刻。进程内口径（多实例各一份，
 * 与 {@code /health.security} 同姿态）；不引 Micrometer / OTel 等新依赖，也不改变任何调用路径的
 * 返回值 —— 它只让"降级发生了"变成可读、可断言、可进 {@code /health} 的事实。
 *
 * <p>为什么是静态表而不是 Spring Bean：五个调用点分布在 {@code RunStore} /
 * {@code RedisFrameStore} / {@code CancellationRegistry}，其中多个类在单测里直接
 * {@code new} 出来；注入一个新依赖会改动大量既有构造器与用例。静态表保持构造面不变，
 * 用例按"事件前后的增量"断言，不依赖全局清零。
 */
public final class RedisDegradation {

	/** A1：{@code ai:fence:run:<runId>} 读取失败（认领侧 fail-closed）。 */
	public static final String A1_FENCE_READ = "a1.fence.read";

	/** A3：事件日志 Stream 的 {@code XRANGE} 读取失败。 */
	public static final String A3_FRAME_RANGE_READ = "a3.frameRange.read";

	/** A4：事件日志 Stream 的末号探测（{@code XREVRANGE}）失败。 */
	public static final String A4_FRAME_TAIL_READ = "a4.frameTail.read";

	/** A5：取消标志读取失败（调用方据此判"未知"，不再折成"未取消"）。 */
	public static final String A5_CANCEL_READ = "a5.cancel.read";

	/** A5：取消状态回答为 UNKNOWN（含 5s 退避期未访问 Redis 的拍）。 */
	public static final String A5_CANCEL_UNKNOWN = "a5.cancelUnknown";

	/** A6：决策 guard（{@code ai:decided:<runId>}）读取失败（读侧 fail-closed）。 */
	public static final String A6_DECISION_GUARD_READ = "a6.decisionGuard.read";

	/** A6：待决条目本体（{@code ai:pending:<runId>}）读取失败（非抛读口降级为空/原始视图）。 */
	public static final String A6_PENDING_READ = "a6.pending.read";

	/** L-139：挂起期续期的某个键 {@code EXPIRE} 失败（逐键 try，其余键照常续期）。 */
	public static final String L139_RENEW_KEY = "l139.renewKey";

	/** L-139：连接类失败后提前收手，本面剩余键<b>未尝试</b>续期（不再逐个等超时）。 */
	public static final String L139_RENEW_NOT_ATTEMPTED = "l139.renewNotAttempted";

	/** B3：附件登记写 Redis 失败（登记侧 fail-closed，不再回落进程内 Map）。 */
	public static final String B3_ATTACHMENT_REGISTER = "b3.attachmentRegister";

	/** B3：附件读取失败（find / 元数据）。 */
	public static final String B3_ATTACHMENT_READ = "b3.attachmentRead";

	/** B3：附件消费失败（consume）。 */
	public static final String B3_ATTACHMENT_CONSUME = "b3.attachmentConsume";

	/** B3：附件墓碑读取失败（wasConsumed）。 */
	public static final String B3_ATTACHMENT_TOMBSTONE = "b3.attachmentTombstone";

	/**
	 * C1：事件日志的<b>字节预算</b>成为实际约束（有效窗口 &lt; 条数窗口，流被裁得更短）。
	 *
	 * <p>Redis 容量专项 A：{@code ai:xevents} 的条数窗口（6000）在单帧上限 64KB 下并不封顶字节。
	 * 本事件每次"被字节预算约束的落档"计一次（量级 = 有多少写入被容量治理限制过），
	 * 日志每轮只 WARN 一次。运维据 {@code /api/ai/health} 的 {@code redis.degradations} 判断
	 * 是否要调 {@code app.ai.stream.byte-budget}。
	 */
	public static final String C1_STREAM_BYTE_CAP = "c1.streamByteCap";

	/** C1：预算连下限窗口（{@code RedisFrameStore#MIN_WINDOW}）都撑不住 —— 保守界不可兑现，显式可见。 */
	public static final String C1_STREAM_BYTE_FLOOR = "c1.streamByteFloor";

	/** C1：回放的 {@code afterSeq} 早于幸存窗口（本次回放跳过了一段），不得伪装成"没有新帧"。 */
	public static final String C1_STREAM_REPLAY_GAP = "c1.streamReplayGap";

	/** C1：{@code ai:runs} 索引惰性清理失败（快照键探测/成员剔除），本轮跳过、下一拍重试。 */
	public static final String C1_RUN_INDEX_PRUNE = "c1.runIndexPrune";

	/**
	 * W1：会话记忆<b>整窗写回</b>失败（{@code ChatMemoryRepository#saveAll} 或旧 {@code ChatMemory} 通道）。
	 *
	 * <p>此前只有 WARN：刷新后可能丢掉本轮历史，属于必须可观测的降级。一次失败的写只计一次
	 * （不重试、不改变写协议）；WARN 原文保留（带 stage 与 sessionId）。
	 */
	public static final String W1_MEMORY_WRITE = "w1.memoryWrite";

	private static final String[] KNOWN = { A1_FENCE_READ, A3_FRAME_RANGE_READ, A4_FRAME_TAIL_READ, A5_CANCEL_READ,
			A5_CANCEL_UNKNOWN, A6_DECISION_GUARD_READ, A6_PENDING_READ, L139_RENEW_KEY, L139_RENEW_NOT_ATTEMPTED,
			B3_ATTACHMENT_REGISTER, B3_ATTACHMENT_READ, B3_ATTACHMENT_CONSUME, B3_ATTACHMENT_TOMBSTONE,
			C1_STREAM_BYTE_CAP, C1_STREAM_BYTE_FLOOR, C1_STREAM_REPLAY_GAP, C1_RUN_INDEX_PRUNE, W1_MEMORY_WRITE };

	private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();

	static {
		for (String event : KNOWN) {
			COUNTERS.put(event, new Counter());
		}
	}

	private RedisDegradation() {
	}

	/** 记一次降级（无异常对象；用于"已知失败但只关心次数"的路径）。 */
	public static void record(String event) {
		counter(event).increment();
	}

	/** 记一次降级并带异常（异常只用于触发计数；不保存异常对象，避免持有栈/连接）。 */
	public static void record(String event, Throwable ex) {
		record(event);
	}

	/** 某事件的累计次数（未知事件名返回 0）。 */
	public static long count(String event) {
		Counter counter = COUNTERS.get(event);
		return counter == null ? 0L : counter.count.get();
	}

	/** 快照：按登记顺序给出每个事件（固定键集 ⇒ "从未降级"与"没接线"可区分）。 */
	public static Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String event : KNOWN) {
			Counter counter = COUNTERS.get(event);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("count", counter.count.get());
			item.put("lastAtMs", counter.lastAtMs.get());
			out.put(event, item);
		}
		return out;
	}

	private static Counter counter(String event) {
		if (event == null || event.isBlank()) {
			throw new IllegalArgumentException("降级事件名不能为空");
		}
		return COUNTERS.computeIfAbsent(event, key -> new Counter());
	}

	private static final class Counter {

		private final AtomicLong count = new AtomicLong();

		private final AtomicLong lastAtMs = new AtomicLong();

		private void increment() {
			this.lastAtMs.set(System.currentTimeMillis());
			this.count.incrementAndGet();
		}

	}

}
