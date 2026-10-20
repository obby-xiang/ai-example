package com.example.configmgr.ai.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件日志替身（B23L1 批① 单测）：按 run 一条 {@code seq → 帧} 有序表，取号口径与生产一致
 * （自 1 起、单调、不重），并<b>把每次调用记进共享 journal</b>——于是
 * "取号 → 落档 → 发布 → 本地扇出"的顺序成为可断言事实（顺序断言的锚点在流水，不在实现细节）。
 */
final class InMemoryFrameStore implements FrameStore {

	private final Map<String, TreeMap<Long, Map<String, Object>>> logs = new ConcurrentHashMap<>();

	private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();

	/** 共享操作流水（跨替身：{@code next:} / {@code append:} / {@code publish:} / {@code send:}）。 */
	private final List<String> journal;

	/** 下一次 {@link #readFrom} 收集完帧后执行一次的钩子（I-1：在"回读"与"返回"之间落一帧）。 */
	private volatile Runnable afterReadHook;

	InMemoryFrameStore() {
		this(new CopyOnWriteArrayList<>());
	}

	InMemoryFrameStore(List<String> journal) {
		this.journal = journal;
	}

	@Override
	public long nextSeq(String runId) {
		long seq = this.seqs.computeIfAbsent(runId, k -> new AtomicLong()).incrementAndGet();
		this.journal.add("next:" + runId);
		return seq;
	}

	@Override
	public void append(String runId, Map<String, Object> frame) {
		this.journal.add("append:" + runId);
		if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
			return;
		}
		long seq = seqOf(frame);
		if (seq <= 0) {
			return;
		}
		log(runId).put(seq, new LinkedHashMap<>(frame));
	}

	@Override
	public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<Long, Map<String, Object>> entry : log(runId).entrySet()) {
			if (entry.getKey() <= afterSeq) {
				continue;
			}
			out.add(new LinkedHashMap<>(entry.getValue()));
			if (limit > 0 && out.size() >= limit) {
				break;
			}
		}
		Runnable hook = this.afterReadHook;
		if (hook != null) {
			this.afterReadHook = null;
			hook.run();
		}
		return out;
	}

	/** 登记"下一次回读之后触发一次"的钩子（用于把 I-1 的交接窗口确定化）。 */
	void onNextRead(Runnable hook) {
		this.afterReadHook = hook;
	}

	@Override
	public long lastSeq(String runId) {
		TreeMap<Long, Map<String, Object>> log = log(runId);
		return log.isEmpty() ? 0L : log.lastKey();
	}

	/** 直接铺一段日志（"另一个实例已经写过的帧"）。 */
	@SafeVarargs
	final void seed(String runId, Map<String, Object>... frames) {
		for (Map<String, Object> frame : frames) {
			long seq = seqOf(frame);
			log(runId).put(seq, new LinkedHashMap<>(frame));
			this.seqs.computeIfAbsent(runId, k -> new AtomicLong()).updateAndGet(prev -> Math.max(prev, seq));
		}
	}

	private TreeMap<Long, Map<String, Object>> log(String runId) {
		return this.logs.computeIfAbsent(runId, k -> new TreeMap<>());
	}

	private static long seqOf(Map<String, Object> frame) {
		Object seq = frame.get("seq");
		return seq instanceof Number number ? number.longValue() : 0L;
	}

}
